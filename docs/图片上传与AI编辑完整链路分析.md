# 图片上传与 AI 编辑 — 完整链路分析

> 覆盖 Java 后端 + Python Agent，从图片上传到 AI 编辑/合并的全链路。
> 编辑/合并部分只讨论异步（RabbitMQ）模式。

---

## 总体链路一览

```
┌─────────────────────────────────────────────────────────────────┐
│  第一部分：图片上传（同步，无 MQ）                                  │
│                                                                 │
│  用户选择图片                                                     │
│      │                                                          │
│      ▼                                                          │
│  POST /api/file/upload/image                                    │
│      │                                                          │
│      ├─ ① Controller 接收 MultipartFile + JWT 鉴权              │
│      ├─ ② Service: validateFile() 基础校验                      │
│      ├─ ③ Python 内容审核（Qwen-VL-Max 描述 + Qwen-Plus 判定）   │
│      ├─ ④ 保存到本地 uploads/ 目录                               │
│      ├─ ⑤ 上传到阿里云 OSS                                      │
│      ├─ ⑥ MySQL 写 file_info 表                                 │
│      └─ ⑦ Python 登记 CLIP 向量到 Redis                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  第二部分：AI 编辑/合并（异步，RabbitMQ）                          │
│                                                                 │
│  用户提交编辑/合并任务                                            │
│      │                                                          │
│      ▼                                                          │
│  POST /api/task/submit-edit  (或 /submit-merge)                 │
│      │                                                          │
│      ├─ ⑧ Controller 接收请求                                   │
│      ├─ ⑨ Service 鉴权 + 生成 taskId + Redis 写 PENDING         │
│      ├─ ⑩ Producer 发 MQ 消息，立即返回 taskId                   │
│      │                                                          │
│      ▼                                                          │
│  RabbitMQ (DirectExchange → Queue)                              │
│      │                                                          │
│      ▼                                                          │
│      ├─ ⑪ Consumer 消费，Redis → PROCESSING                     │
│      ├─ ⑫ 分发：handleEditTask / handleMergeTask                │
│      ├─ ⑬ Java RestClient → Flask POST /api/skill/image        │
│      │       │                                                  │
│      │       ├─ 下载 OSS 原图 → multipart 发给 Python            │
│      │       ├─ Flask skill_image() 构造 prompt                 │
│      │       ├─ LLM (Qwen-Plus) 决策调用哪个 @tool               │
│      │       ├─ edit_image_tool / merge_image_tool             │
│      │       ├─ DashScope qwen-image-edit-plus API              │
│      │       └─ 返回 {"success": true, "url": "..."}            │
│      │                                                          │
│      ├─ ⑭ 下载 AI 结果图 → 二次上传 OSS 持久化                   │
│      ├─ ⑮ Redis 更新 SUCCESS（resultUrl + saveUrl）             │
│      └─ ⑯ MySQL 写 records 操作记录                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ⑰ 前端轮询 GET /api/task/status/{taskId}
                        Redis → 拿到结果 → 展示图片
```

---

# 第一部分：图片上传链路（同步）

图片上传是整条链路的起点——用户先上传图片到 OSS，后续才能编辑或合并。

---

## 步骤 1：Controller 接收上传请求

用户选择一张图片，前端通过 `<input type="file">` + `FormData` 发起 POST 请求。Controller 只做参数绑定和路由，不包含业务逻辑。

**代码文件**：`loomai-main/src/main/java/com/lsq/controller/FileController.java`

```java
@RestController
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileService fileService;

    @ApiLog
    @PostMapping("/upload/image")
    public Result<FileUploadResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("图片上传成功", fileService.uploadImage(file, authorization));
    }
}
```

注意是 `@RequestParam("file")` 不是 `@RequestBody`——因为前端发的是 `multipart/form-data`，Spring 用 `MultipartFile` 接收文件字节流。如果这里写错了（比如写成了 `@RequestBody`），Spring 会抛 `Content-Type not supported` 异常。

### 面试官深挖

**Q: `MultipartFile` 的工作原理是什么？大文件上传会撑爆内存吗？**

Spring Boot 默认用 `StandardServletMultipartResolver`，它把上传的文件暂存到临时目录（Tomcat 的 `location` 配置或系统临时目录），`MultipartFile` 只是一个句柄，`file.getBytes()` 时才真正读到内存。如果文件超过 `spring.servlet.multipart.max-file-size`（默认 1MB），Spring 直接抛 `MaxUploadSizeExceededException`。对于大文件上传，应该用 `file.transferTo()` 直接写到磁盘而不是 `file.getBytes()` 读到内存。

**Q: `@ApiLog` 做了什么？**

自定义注解，配合 AOP `@Around` 切面，在 Controller 方法执行前后自动记录入参、出参、耗时。这样不需要在每个 Controller 方法里手写重复的 `log.info`。切面里可以通过 `ProceedingJoinPoint` 的 `getArgs()` 拿参数，`proceed()` 拿返回值。

---

## 步骤 2：Service 层 — 校验 + OSS 上传 + 写库 + AI 登记

这是上传链路的**核心编排方法**，串联了文件校验、Python 审核、本地存储、OSS 上传、数据库写入、CLIP 向量登记六个步骤。

**代码文件**：`loomai-main/src/main/java/com/lsq/service/impl/FileServiceImpl.java`

```java
@Override
@Transactional(rollbackFor = Exception.class)
public FileUploadResponse uploadImage(MultipartFile file, String authorization) {
    // ===== 1. 基础校验：文件不为空、不超大小、是图片 =====
    validateFile(file);

    // ===== 2. JWT 鉴权，拿到 userId =====
    String token = jwtUtil.parseToken(authorization);
    Long userId = jwtUtil.getUserId(token);

    // ===== 3. 生成 OSS 存储路径 =====
    String extension = getFileExtension(file.getOriginalFilename());
    String datePath = DATE_FORMATTER.format(LocalDate.now());
    String objectKey = "images/" + datePath + "/" + UUID.randomUUID() + extension;
    // 例: images/2026/08/10/a1b2c3d4-....png

    // ===== 4. 写入临时文件 → 调 Python 审核 =====
    Path tempPath = Files.createTempFile("upload-", suffix);
    file.transferTo(tempPath.toFile());

    if (!pythonAiIntegrationService.validateImage(tempPath)) {
        throw new RuntimeException("图片只能是人物或衣服图片！");
    }

    // ===== 5. 审核通过 → 保存到本地 uploads/ 目录 =====
    saveToLocalFromPath(tempPath, objectKey);

    // ===== 6. 上传到 OSS =====
    String ossUrl = uploadToOssFromLocal(objectKey);

    // ===== 7. MySQL 写 file_info 表 =====
    saveFileRecord(userId, fileName, file.getSize(), ossUrl);

    // ===== 8. Python 登记 CLIP 向量到 Redis =====
    pythonAiIntegrationService.registerUploadedImage(userId, ossUrl);

    // ===== 9. 返回结果 =====
    FileUploadResponse response = new FileUploadResponse();
    response.setUrl(ossUrl);
    response.setFileName(fileName);
    response.setFileSize(file.getSize());
    return response;
}
```

关键设计点：

- **先审核再存盘**：临时文件交给 Python 审核，审核通过才写到本地 `uploads/` 和 OSS。审核不通过直接抛异常，磁盘上不留垃圾文件。
- **`@Transactional`**：MySQL 写入失败时会自动回滚。但 OSS 上传和 Python 调用不在事务范围内（OSS SDK 和 HTTP 请求不受 Spring 事务管理），所以可能出现"OSS 上传成功但 MySQL 写入失败"的不一致。需要补偿逻辑（比如 catch 异常后删 OSS 文件）。
- **双写**：本地 `uploads/` 目录 + OSS 各存一份。本地文件作为备份，OSS URL 对外暴露。如果 OSS 挂了，至少本地还有。

### 面试官深挖

**Q: `validateFile()` 校验了哪些内容？**

```java
private void validateFile(MultipartFile file) {
    if (file == null || file.isEmpty())
        throw new RuntimeException("图片文件不能为空");
    if (ossProperties.getMaxSize() != null && file.getSize() > ossProperties.getMaxSize())
        throw new RuntimeException("图片大小超过限制");
    String contentType = file.getContentType();
    if (!StringUtils.hasText(contentType) || !contentType.startsWith("image/"))
        throw new RuntimeException("仅支持图片文件上传");
}
```

只校验了三样：不为空、不超大小、Content-Type 是 `image/*`。**没有校验文件内容是否真的是图片**——恶意用户可以改 Content-Type 为 `image/png` 但实际上传一个 `.exe`。用 ImageIO 读取文件头（magic bytes）才是可靠的格式判断。

**Q: 为什么不用 `file.getBytes()` 直接用 `file.transferTo()`？**

`getBytes()` 把整个文件读到 JVM 堆内存，2MB 图片就是 2MB 堆内存。`transferTo()` 是流式写入，用操作系统的零拷贝或小缓冲区，内存占用极小。大文件上传场景 `transferTo()` 是标准做法。

**Q: OSS 上传和 MySQL 写入不在同一个事务里，怎么保证一致性？**

做不到严格一致性。OSS SDK 不支持 XA 分布式事务。实际做法：
- 先写 MySQL（file_info 记录），再上传 OSS。OSS 失败时 MySQL 有记录但没有实际文件，前端展示时会 404。
- 先上传 OSS，再写 MySQL。MySQL 失败时 OSS 上有"孤儿文件"，需要定时任务清理。
- 这里选了后者，代码里 catch RuntimeException 不会回滚 OSS，生产环境建议加定时清理。
- 或者引入 `file_info` 的 `status` 字段（0=上传中, 1=完成），做最终一致性。

**Q: objectKey 里的日期路径 `images/2026/08/10/...` 有什么用？**

阿里云 OSS 按目录分层，日期分区有两个好处：
1. 方便按天排查和清理（比如删掉 30 天前的旧图片）
2. 避免单目录文件过多（OSS 虽然不限文件数，但目录扁平化不利于管理）

---

## 步骤 3：Python 内容审核 — 两道 AI 防线

Java 端做完基础校验后，调 Python `/api/validate-image` 接口做**内容审核**。这一步是两道 AI 防线串联。

**代码文件**：`loomai-main/src/main/java/com/lsq/service/impl/PythonAiIntegrationServiceImpl.java`

```java
@Override
public boolean validateImage(Path filePath) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new FileSystemResource(filePath.toFile()));

    ResponseEntity<String> response = restClient.post()
            .uri("/api/validate-image")
            .body(body)
            .retrieve()
            .toEntity(String.class);

    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
        return false;
    }
    return objectMapper.readTree(response.getBody()).path("allow").asBoolean(false);
}
```

Java 把本地临时文件通过 `multipart/form-data` 发给 Flask，Flask 返回 `{"code": 200, "allow": true/false}`。

**Java 端的设计值得注意**：HTTP 调用失败或 Python 返回 500 时，`validateImage` 返回 `false`（拒绝上传），而不是抛异常。这是**偏保守的安全策略**——宁可误拦也不能放行。

### 3.1 Flask 侧：Qwen-VL-Max 生成图片描述

**代码文件**：`loomai-agent/server.py`

```python
@app.route("/api/validate-image", methods=["POST"])
def validate_image_api():
    file = request.files["file"]
    image_data = file.read()
    desc = describe_image(image_data)      # 第一道：Qwen-VL-Max
    allow = validate_image(desc)           # 第二道：Qwen-Plus
    return jsonify({"code": 200, "allow": allow})
```

**第一道防线 — `describe_image()`**：调用多模态大模型 **Qwen-VL-Max** 对图片内容做描述。

```python
def describe_image(image_data: bytes) -> str:
    data_uri = process_image(image_data)  # bytes → base64 data URI

    human_content = [
        {"image": data_uri},
        {"text": "用一句话简要地概括这张图片的内容，并给出3到5个关键词（使用逗号分隔开），不要过多地解释"}
    ]

    vl_llm = ChatTongyi(
        model_name="qwen-vl-max",
        temperature=0.0,
        dashscope_api_key=API_KEY
    )

    resp = vl_llm.invoke([HumanMessage(content=human_content)])
    return resp.content[0]['text']
```

Qwen-VL-Max 是多模态模型，能"看"图片。这里用它把图片转成文字描述 + 关键词，例如：

```
图片描述: "一位年轻女性穿着白色T恤和蓝色牛仔裤，站在公园里"
关键词: 女性, 白色T恤, 蓝色牛仔裤, 公园, 穿搭
```

`temperature=0.0` 保证了描述的稳定性——同一张图每次返回的描述几乎一样。

**为什么先描述再判定，而不是直接让 VL 模型判定？**

因为 Qwen-VL-Max 的视觉理解能力强但逻辑判定能力弱，Qwen-Plus 反之。把 VL 的描述交给文本模型做分类判定，比直接让 VL 回答"是/否"更准。这是**多模型协作**的经典范式：一个模型负责理解、一个模型负责决策。

### 3.2 第二道防线：Qwen-Plus 判定内容是否合规

```python
def validate_image(image_desc: str) -> bool:
    prompt = ChatPromptTemplate.from_messages([
        (
            "system",
            "你是一个图片审核助手，当前的业务只允许两种图片："
            "1) 衣服/服装/穿搭相关，只要图片中有人物/衣服就算合格;"
            "2) 人物人像(人脸照、半身照、全身照).\n"
            "需要你来判断当前图片的内容是否是以上两类图片，如果是输出是，如果否输出否。"
            "请严格只输出是或者否，不要别的内容"
        ),
        ("human", f"图片文字描述: {image_desc}")
    ])

    llm = ChatTongyi(
        model_name="qwen-plus",
        temperature=0.7,
        dashscope_api_key=API_KEY
    )

    resp = llm.invoke(prompt.format_messages())
    return resp.content.startswith("是")
```

拿到 VL 的描述后，Qwen-Plus 判断是否属于"服装/穿搭/人像"范畴。只输出"是"或"否"，代码用 `startswith("是")` 判定。

这里 temperature 却设了 0.7——可能是个值得商榷的点。审核场景需要确定性输出，temperature 设为 0 更合适，避免 LLM 不稳定导致同一个描述第一次判"是"第二次判"否"。

### 面试官深挖

**Q: 为什么要用两道 AI 审核，直接 Qwen-VL-Max 一步判定不行吗？**

技术上可以，但解耦后更灵活：
- Qwen-VL-Max 的描述结果可以被**复用**：上传时生成一次描述，后续 CLIP 向量登记和图片搜索都能用。
- 判定规则可以独立修改：如果以后业务允许"宠物照片"了，只改 Qwen-Plus 的 system prompt 就行，VL 生成描述的环节不需要动。
- 两个模型的角色不同：VL 是"眼睛"（理解视觉内容），Plus 是"大脑"（做业务决策）。

**Q: temperature=0.7 对审核场景合适吗？**

不太合适。审核需要一致性，同一张图片第一次和第二次判定应该一样。高 temperature 可能导致同一条描述有时返回"是"有时返回"否"。审核场景 temperature 建议设 0。

**Q: `resp.content.startswith("是")` 这种判断方式可靠吗？**

基本可靠，因为 prompt 里明确说了"严格只输出是或者否"。但 LLM 偶尔会不听话，输出"是。"、"\n是\n" 之类的。用 `"是" in resp.content` 比 `startswith` 更稳健。最严谨的做法是要求 LLM 输出 JSON `{"result": true/false}` 然后解析，JSON 格式约束力更强。

**Q: 如果 Python 服务挂了，`validateImage` 返回 false，合法用户的图片会被拒绝上传，怎么办？**

是的，当前逻辑下 Python 不可用 = 所有上传都被拒绝。生产环境应该加降级：Python 超时或返回 500 时，用 Java 侧的简单校验兜底（比如只检查文件扩展名和 Content-Type），或者放行但标记为"未审核"等待人工审核。

---

## 步骤 4：保存本地 + 上传 OSS

审核通过后，图片被保存到**两个位置**：本地磁盘 `uploads/` 目录（备份），阿里云 OSS（对外暴露）。

```java
// 保存到本地
private void saveToLocalFromPath(Path sourceFile, String objectKey) {
    Path targetPath = Paths.get(System.getProperty("user.dir"), "uploads", objectKey);
    Path parent = targetPath.getParent();
    Files.createDirectories(parent);                         // 自动建目录
    Files.copy(sourceFile, targetPath, REPLACE_EXISTING);    // 流式拷贝
}

// 上传 OSS
private String uploadToOssFromLocal(String objectKey) {
    Path localPath = Paths.get(System.getProperty("user.dir"), "uploads", objectKey);
    OSS ossClient = new OSSClientBuilder().build(
            "https://" + ossProperties.getEndpoint(),
            ossProperties.getAccessKeyId(),
            ossProperties.getAccessKeySecret()
    );
    try (InputStream inputStream = Files.newInputStream(localPath)) {
        ossClient.putObject(ossProperties.getBucketName(), objectKey, inputStream);
        return "https://" + ossProperties.getBucketName() + "." +
               ossProperties.getEndpoint() + "/" + objectKey;
    } finally {
        ossClient.shutdown();
    }
}
```

Java 端本地写 `uploads/`、再读出来上传 OSS。其实可以**跳过本地存储步骤**——直接从临时文件上传 OSS，因为本地 `uploads/` 目录目前没有再被读取过（所有图片展示都走 OSS URL）。保留本地存储的唯一价值是 OSS 挂了时有备份可查。

### 面试官深挖

**Q: OSS 上传为什么没用分片上传？**

`ossClient.putObject()` 是简单上传，适用于小文件（<5MB）。如果图片很大（比如单反原图几十 MB），应该用分片上传（`InitiateMultipartUpload` + `UploadPart` + `CompleteMultipartUpload`），支持断点续传、并行上传、进度回调。OSS Java SDK 提供了 `UploadFileRequest` 封装好的分片上传方法。

**Q: 本地 uploads/ 目录会无限膨胀吗？**

会。代码里没有清理逻辑，图片只增不删，磁盘迟早会满。生产环境应该加定时清理（比如每天凌晨清理 30 天前的文件），或者直接用 OSS 作为主存储，本地 `uploads/` 干脆去掉。

---

## 步骤 5：写 MySQL + 登记 CLIP 向量

图片上传的最后两步是写数据库和登记 AI 特征向量，供后续搜索使用。

```java
// MySQL 写入
private void saveFileRecord(Long userId, String fileName, Long fileSize, String ossUrl) {
    FileInfo fileInfo = new FileInfo();
    fileInfo.setUserId(userId);
    fileInfo.setFileName(fileName);
    fileInfo.setFileSize(fileSize);
    fileInfo.setOssUrl(ossUrl);
    fileMapper.insert(fileInfo);  // MyBatis-Plus
}
```

```java
// 调 Python 登记 CLIP 向量
pythonAiIntegrationService.registerUploadedImage(userId, ossUrl);
```

`registerUploadedImage` 调 Python `/api/upload-image`，Flask 侧流程：

```python
@app.route("/api/upload-image", methods=["POST"])
def upload_image_api():
    user_id = _extract_user_id()
    oss_url = _extract_oss_url()

    # 1. 从 OSS 下载图片
    resp = requests.get(oss_url, timeout=20)
    image_data = resp.content

    # 2. Qwen-VL-Max 生成描述（这里又调了一次）
    description = describe_image(image_data)

    # 3. CLIP 生成 512 维向量
    embedding = clip_image_to_512d(image_data)

    # 4. 写入 Redis
    image_id = uuid.uuid4().hex
    payload = {
        "userId": user_id,
        "imageId": image_id,
        "ossUrl": oss_url,
        "description": description,
        "embedding512": embedding,
    }
    _redis_client.set(f"clip:image:{image_id}", json.dumps(payload, ensure_ascii=False))
    _redis_client.sadd(f"clip:user:{user_id}:image_ids", image_id)

    return jsonify({"success": True, "imageId": image_id, "description": description})
```

这一步做了四件事：
1. **再次下载图片**：因为 Python 侧没有保存上传时的临时文件，只能从 OSS 重新下载。这是一次**冗余网络 I/O**，优化方法是在 `validate_image` 时就把图片 bytes 缓存在 Redis 里，登记时直接用。
2. **再次调 Qwen-VL-Max 生成描述**：和 validate 步骤产生了重复调用（同一张图描述了两次）。优化：validate 时把描述缓存下来。
3. **CLIP ViT-B/16 生成 512 维向量**：将图片转成语义向量，用于后续图搜图。
4. **写入 Redis**：`clip:image:{id}` 存完整信息（含向量），`clip:user:{userId}:image_ids` 用 Set 维护该用户的图片索引。

**Redis 里存了两套和图片相关的数据**：

| Key 模式 | 用途 | 写入时机 |
|---------|------|---------|
| `clip:image:{imageId}` | CLIP 向量 + 描述 + OSS URL | 上传完成后 |
| `clip:user:{userId}:image_ids` | 该用户的图片 ID 集合（Set） | 上传完成后 |
| `task:{taskId}` | 异步任务状态 | 提交编辑/合并任务时 |
| `jwt:user:{userId}` | JWT Token 缓存 | 登录时 |

### 面试官深挖

**Q: CLIP 是什么？为什么用 CLIP 而不是 ResNet/EfficientNet？**

CLIP（Contrastive Language-Image Pre-training）是 OpenAI 开源的多模态模型。核心优势：它能将图片和文字映射到**同一个语义空间**，所以既可以图搜图（传图片找相似图片）、也可以文搜图（传文字描述找匹配图片）。ResNet 只能做图像分类，没法处理"用文字搜图片"。本项目用的是 `clip-vit-base-patch16` 版本，输出 512 维向量，本地加载模型文件。

**Q: 为什么 CLIP 向量存在 Redis 而不是专门的向量数据库（Milvus/Pinecone）？**

当前数据量小（用户级别的图片，不是全库级别的），Redis 够用：
- 查询时从 `clip:user:{userId}:image_ids` 拿到该用户的图片 ID 集合
- 遍历每个 `clip:image:{id}` 取出向量
- Java 端（或 Python 端）在内存中做余弦相似度计算和排序

缺点：遍历计算 O(n)，图片多了会慢。如果以后支持跨用户搜索或图片量到百万级别，迁移到 Milvus 可以做到 O(log n) 的 ANN 检索。

**Q: validate_image 和 registerUploadedImage 都调了 describe_image，怎么优化？**

validate 时把描述存 Redis（key = 文件 MD5，value = 描述文本），registerUploadedImage 先查 Redis 有没有缓存，没有再调 API。或者干脆让 validate 接口返回 `{"allow": true, "description": "..."}` ，把描述带回 Java 侧，Java 再传给 register 接口，避免重复调用。

---

# 第二部分：AI 编辑/合并异步链路（RabbitMQ）

上传好的图片存在 OSS 上，用户可以对其进行编辑（换背景、去水印等）或合并（两张图合成一套穿搭）。

## 为什么编辑/合并要引入 RabbitMQ？

图片编辑调用阿里云 DashScope 的 `qwen-image-edit-plus` 模型，一次推理 **30 秒到几分钟不等**。如果不加消息队列，HTTP 请求直接同步等待 AI 返回：

- **Tomcat 线程被长时间阻塞**。默认 200 个线程，200 个用户同时提交 → 线程池打满 → 新请求被拒绝。
- **用户体验差**。浏览器一直 loading，不知道是卡死了还是在处理中。
- **无法削峰**。高峰期大量请求同时打 DashScope API，可能触发限流。

引入 MQ 后：用户提交 → 发一条消息 → **立即返回 taskId**（毫秒级）→ 后台 Consumer 逐个消费 → 完成后更新 Redis → 前端轮询查结果。

---

## 步骤 6：RabbitMQ 架构设计

### 6.1 Exchange / Queue / RoutingKey

**代码文件**：`loomai-main/src/main/java/com/lsq/config/RabbitMqConfig.java`

```java
@Configuration
public class RabbitMqConfig {

    public static final String TASK_EXCHANGE   = "loomai.task.exchange";
    public static final String TASK_QUEUE      = "loomai.task.queue";
    public static final String ROUTING_KEY_EDIT  = "task.edit";
    public static final String ROUTING_KEY_MERGE = "task.merge";

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(TASK_EXCHANGE, true, false);
        // durable=true: MQ 重启 Exchange 还在
        // autoDelete=false: 没有 Queue 绑定时不自动删除
    }

    @Bean
    public Queue taskQueue() {
        return new Queue(TASK_QUEUE, true);
    }

    @Bean
    public Binding editBinding() {
        return BindingBuilder.bind(taskQueue()).to(taskExchange()).with(ROUTING_KEY_EDIT);
    }

    @Bean
    public Binding mergeBinding() {
        return BindingBuilder.bind(taskQueue()).to(taskExchange()).with(ROUTING_KEY_MERGE);
    }
}
```

拓扑结构：一个 DirectExchange + 一个 Queue + 两个 RoutingKey（编辑和合并共用 Queue）。

```
                  DirectExchange
                loomai.task.exchange
                      │
          ┌───────────┴───────────┐
          │                       │
    routingKey:              routingKey:
    task.edit                task.merge
          │                       │
          └───────────┬───────────┘
                      │
                同一个 Queue
            loomai.task.queue
                      │
                一个 Consumer
```

### 面试官深挖

**Q: Direct、Topic、Fanout 的区别？这里为什么选 Direct？**

Direct 精确匹配 RoutingKey，Topic 支持通配符，Fanout 广播。当前只有两种固定类型，用最简单的 Direct。如果未来任务类型变多且有层次关系（如 `task.image.edit`、`task.image.merge`、`task.video.edit`），可以升级为 Topic。

**Q: 编辑和合并共用 Queue 有什么问题？**

如果某天爆发大量编辑请求，Queue 里全是 EDIT 消息，合并请求需要排长队。两个 RoutingKey 已经分开了，拆成两个 Queue + 两个 Consumer 只需要改 RabbitMqConfig 和 TaskMessageConsumer，Producer 不用动。

**Q: durable=true 就保证消息不丢？**

不是。durable 只保证 Exchange/Queue 元数据持久化。消息本身需要 `delivery_mode=2`（持久化消息）。Spring AMQP 默认 `MessageProperties.isPersistent()` = true，所以消息也是持久化的。但真正防丢还需要 Publisher Confirm + 手动 ACK + 镜像队列。

### 6.2 消息体

**代码文件**：`loomai-main/src/main/java/com/lsq/dto/TaskMessage.java`

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskMessage {
    private String taskId;              // UUID v4
    private Long userId;
    private String type;                // "EDIT" 或 "MERGE"
    private Map<String, String> params; // 灵活扩展参数
}
```

编辑任务 JSON：

```json
{
    "taskId": "a1b2c3d4-...",
    "userId": 10000001,
    "type": "EDIT",
    "params": {
        "image": "https://xxx.oss-cn-beijing.aliyuncs.com/images/2026/08/10/abc.png",
        "instruction": "把背景换成白色"
    }
}
```

合并任务 JSON（注意 params 里是 image1 + image2）：

```json
{
    "taskId": "b2c3d4e5-...",
    "userId": 10000001,
    "type": "MERGE",
    "params": {
        "image1": "https://xxx.oss-cn-beijing.aliyuncs.com/images/2026/08/10/上衣.png",
        "image2": "https://xxx.oss-cn-beijing.aliyuncs.com/images/2026/08/10/裤子.png",
        "instruction": "把上衣和裤子搭配成一套穿搭"
    }
}
```

### 面试官深挖

**Q: 为什么 params 用 `Map<String, String>` 而不是强类型子类？**

编辑和合并的参数不同。用 Map 一套结构承载两种任务。代价是 Consumer 端靠硬编码 `params.get("image")` 取值，key 拼错编译器不报错。如果参数结构稳定，推荐定义 `EditTaskPayload` 和 `MergeTaskPayload` 两个类，通过 JSON 多态反序列化（`@JsonTypeInfo`）。

**Q: taskId 用 UUID 有什么影响？**

UUID v4 生成简单，无碰撞风险，不需要中心发号器。缺点是作为 Redis key 是长字符串，比自增数字多占内存。不过 Redis key 总共几十个字符，影响忽略不计。

### 6.3 消息序列化

```java
@Bean
public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
}

@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter());
    return template;
}
```

Spring AMQP 默认序列化是 Java 原生 `Serializable`（二进制），改成 `Jackson2JsonMessageConverter` 后输出 JSON。JSON 的好处：体积更小、跨语言兼容（以后可以用 Go/Python 消费）、可视化调试。

---

## 步骤 7：Controller 接收异步任务

**代码文件**：`loomai-main/src/main/java/com/lsq/controller/TaskController.java`

```java
@RestController
@RequestMapping("/api/task")
public class TaskController {

    @Autowired
    private FileService fileService;

    @ApiLog
    @PostMapping("/submit-edit")
    public Result<TaskSubmitResponse> submitEdit(
            @RequestBody @Validated EditImageRequest request,
            @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("任务已提交", fileService.submitEditAsync(authorization, request));
    }

    @ApiLog
    @PostMapping("/submit-merge")
    public Result<TaskSubmitResponse> submitMerge(
            @RequestBody @Validated MergeImageRequest request,
            @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("任务已提交", fileService.submitMergeAsync(authorization, request));
    }

    @GetMapping("/status/{taskId}")
    public Result<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        return Result.success("查询成功", fileService.getTaskStatus(taskId));
    }
}
```

注意这里是 `@RequestBody`（JSON body），对比上传接口的 `@RequestParam("file")`（multipart）。编辑/合并不传文件，只传图片的 OSS URL 和文字 instruction。

---

## 步骤 8：Service 层 — 鉴权 + 生成任务 + 发消息

**代码文件**：`loomai-main/src/main/java/com/lsq/service/impl/FileServiceImpl.java`

```java
@Override
public TaskSubmitResponse submitEditAsync(String authorization, EditImageRequest request) {
    // 1. 鉴权：用户只能编辑自己上传的图片
    List<FileInfo> imageFiles = myImages(authorization);
    List<String> urls = imageFiles.stream().map(FileInfo::getOssUrl).toList();
    if (!urls.contains(request.getImage())) {
        throw new RuntimeException("只能编辑自己上传的图片！");
    }

    // 2. JWT 解析 userId
    Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));

    // 3. 生成 UUID taskId
    String taskId = UUID.randomUUID().toString();

    // 4. 构造消息 params
    Map<String, String> params = new HashMap<>();
    params.put("image", request.getImage());
    params.put("instruction", request.getInstruction());

    // 5. 组装消息体
    TaskMessage message = new TaskMessage(taskId, userId, "EDIT", params);

    // 6. Redis 写 PENDING（必须在发 MQ 之前）
    saveInitialTaskStatus(taskId);

    // 7. 发 MQ 消息
    taskMessageProducer.sendEditTask(message);

    // 8. 立即返回 taskId
    return new TaskSubmitResponse(taskId, "PENDING");
}
```

**为什么先写 Redis 再发 MQ？**

如果反过来（先发 MQ 再写 Redis），Consumer 消费极快，可能在 Redis 写完之前就开始处理了。此时用户轮询 `getTaskStatus` 会查不到记录 → 返回"任务不存在"。先写 Redis PENDING → 再发 MQ，保证用户拿到 taskId 后一定能查到状态（至少是 PENDING）。

### 面试官深挖

**Q: 鉴权方式是查全量数据在内存中比对，用户图片多了怎么办？**

当前 `myImages()` 是 `SELECT * FROM file_info WHERE user_id = ?`，图片多了浪费内存和 MySQL IO。优化：用 SQL 直查——`SELECT COUNT(*) FROM file_info WHERE user_id = ? AND oss_url = ?`，MySQL 走联合索引，O(1) 完成鉴权。

**Q: Redis 写 PENDING 成功了但 MQ 发送失败怎么办？**

当前代码没有处理。Redis 里会永久残留 PENDING（虽然 1 小时 TTL），用户一直看到 PENDING 但永远完不成。修复：MQ 发送失败时 catch 异常，把 Redis 里 PENDING 更新为 FAILED 或直接删除 key。

**Q: 为什么不在鉴权时查 JWT 缓存在 Redis 里的 token？这里的 JWT 和 Redis 的关系是什么？**

```java
// JwtUtil
public String generateToken(User user) {
    // 如果 Redis 已有 token，直接返回缓存的（一个用户只保留一个有效 token）
    if (redisTemplate.hasKey("jwt:user:" + user.getId())) {
        return redisTemplate.opsForValue().get("jwt:user:" + user.getId());
    }
    // 生成新 token → 写 Redis → 返回
}

public String parseToken(String authorization) {
    return authorization.substring(7);  // 去掉 "Bearer " 前缀
}
```

JWT 本身是无状态的（token 自包含 userId + 过期时间），但这里把 token 存进 Redis 实现了一个能力：**一个用户只能有一个有效 token**。第二次登录时返回旧 token 而不是发新 token，防止 token 泛滥。同时可以从 Redis 删除 key 实现"强制下线"。这是 JWT + Redis 的混合模式，牺牲了 JWT 的无状态性换取了 token 管控能力。

---

## 步骤 9：写 Redis 初始状态

任务刚提交，必须有一个 PENDING 状态让前端轮询。

```java
private void saveInitialTaskStatus(String taskId) {
    TaskStatusResponse status = new TaskStatusResponse();
    status.setTaskId(taskId);
    status.setStatus("PENDING");
    stringRedisTemplate.opsForValue().set(
            "task:" + taskId,
            objectMapper.writeValueAsString(status),
            Duration.ofHours(1)  // 1 小时后自动清理
    );
}
```

### 面试官深挖

**Q: 为什么用 Redis 而不是 MySQL 存任务状态？**

- 任务状态是临时的（1 小时过期），MySQL 行级别数据是永久的
- Redis 读写性能远高于 MySQL（微秒 vs 毫秒），适合前端高频轮询
- TTL 自动清理，不需要定时任务扫描删除

**Q: TTL 1 小时够吗？如果 AI 处理超过 1 小时怎么办？**

大部分图片编辑在 1-3 分钟完成。如果真的超过 1 小时，说明出了问题（DashScope 挂了、网络断了），此时让任务过期也是合理的——没有用户会等 1 小时。如果需要更长的 TTL，可以在 Consumer 每次更新状态时刷新：`stringRedisTemplate.expire(key, Duration.ofHours(1))`，TTL 从最后一次更新起算。

---

## 步骤 10：发送 MQ 消息

**代码文件**：`loomai-main/src/main/java/com/lsq/service/TaskMessageProducer.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendEditTask(TaskMessage message) {
        log.info("发送编辑任务消息, taskId={}", message.getTaskId());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TASK_EXCHANGE,    // "loomai.task.exchange"
                RabbitMqConfig.ROUTING_KEY_EDIT, // "task.edit"
                message
        );
    }

    public void sendMergeTask(TaskMessage message) {
        log.info("发送合并任务消息, taskId={}", message.getTaskId());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TASK_EXCHANGE,     // "loomai.task.exchange"
                RabbitMqConfig.ROUTING_KEY_MERGE, // "task.merge"
                message
        );
    }
}
```

`convertAndSend` 内部：Jackson2JsonMessageConverter 把 TaskMessage → JSON 字节数组 → 设置 `__TypeId__` 消息头 → TCP 发送到 Broker → Broker 根据 RoutingKey 路由到 Queue。

此时 HTTP 响应返回给前端：

```json
{
    "code": 200,
    "message": "任务已提交",
    "data": { "taskId": "a1b2c3d4-...", "status": "PENDING" }
}
```

Tomcat 线程释放，可以做别的事了。

### 面试官深挖

**Q: `convertAndSend` 是同步还是异步？有没有确认机制？**

同步——等待 TCP 写入 Broker 的 socket 缓冲区完成。不等待 Broker 持久化或路由。当前没有开启 Publisher Confirm（`rabbitTemplate.setConfirmCallback()`），消息可能因为 Exchange 不存在等原因静默丢失，Producer 无感知。

**Q: RabbitMQ 挂了会怎样？**

抛 `AmqpConnectException`，一路往上到 Controller → 全局异常处理器返回 500。但 Redis 里 PENDING 已经写了，留下脏数据。

---

## 步骤 11：Consumer 消费消息

消息到达 Queue 后，`@RabbitListener` 驱动消费。

**代码文件**：`loomai-main/src/main/java/com/lsq/service/impl/TaskMessageConsumer.java`

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskMessageConsumer {

    private final PythonAiIntegrationService pythonAiIntegrationService;
    private final RecordService recordService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMqConfig.TASK_QUEUE)
    public void handleTask(TaskMessage message) {
        String taskId = message.getTaskId();
        log.info("收到任务消息, taskId={}, type={}", taskId, message.getType());

        try {
            // 立即更新为 PROCESSING
            updateTaskStatus(taskId, buildStatus("PROCESSING", null, null, null));

            // 按 type 分发
            if ("EDIT".equals(message.getType())) {
                handleEditTask(message);
            } else if ("MERGE".equals(message.getType())) {
                handleMergeTask(message);
            } else {
                updateTaskStatus(taskId,
                    buildStatus("FAILED", null, null, "未知任务类型: " + message.getType()));
            }

        } catch (Exception e) {
            log.error("任务处理失败, taskId={}", taskId, e);
            updateTaskStatus(taskId, buildStatus("FAILED", null, null, e.getMessage()));
        }
    }
}
```

关键设计：外层 catch 所有 Exception 并更新 FAILED → 方法正常返回 → Spring AMQP ACK 消息。**消息不会重试**，避免无效重试消耗资源。

`application.yml` 配置了 `prefetch: 1`，一次只取一条消息，串行消费。好处是保护下游 DashScope API（不并发调用），代价是任务排队等待。

### 面试官深挖

**Q: 为什么要 catch 所有异常不让消息重试？**

因为 AI 调用失败通常不是重试能解决的——OSS URL 无效、DashScope API 返回错误、图片格式损坏——重试多次结果一样。与其让消息在队列里反复投递浪费资源，不如直接标记失败让用户感知。但更好的做法是区分"可重试异常"和"不可重试异常"：网络超时可以重试，图片 URL 无效不应重试。

**Q: prefetch=1 意味着什么？如果 10 个用户同时提交，第 10 个要等多久？**

prefetch=1 表示 Consumer 一次只从 Queue 取一条消息，处理完 ACK 后才取下一跳。如果每个任务平均 1 分钟，第 10 个用户要等 9 分钟。可以通过 `concurrency` 参数启动多个 Consumer 线程并行消费，或者部署多个实例。

---

## 步骤 12：分发处理 — handleEditTask / handleMergeTask

```java
private void handleEditTask(TaskMessage message) {
    Map<String, String> params = message.getParams();
    EditImageRequest request = new EditImageRequest();
    request.setImage(params.get("image"));
    request.setInstruction(params.get("instruction"));

    // 最耗时的步骤：调 Python Agent
    EditImageResponse response = pythonAiIntegrationService.edit(request);

    // 成功 → Redis 更新 SUCCESS
    updateTaskStatus(message.getTaskId(),
            buildStatus("SUCCESS", response.getUrl(), response.getSaveUrl(), null));

    // MySQL 写操作记录
    recordService.editSave(message.getUserId(), request, response);
}

private void handleMergeTask(TaskMessage message) {
    Map<String, String> params = message.getParams();
    MergeImageRequest request = new MergeImageRequest();
    request.setImage1(params.get("image1"));
    request.setImage2(params.get("image2"));
    request.setInstruction(params.get("instruction"));

    MergeImageResponse response = pythonAiIntegrationService.merge(request);

    updateTaskStatus(message.getTaskId(),
            buildStatus("SUCCESS", response.getUrl(), response.getSaveUrl(), null));

    recordService.mergeSave(message.getUserId(), request, response);
}
```

编辑和合并的区别：参数不同（1 张图 vs 2 张图），但都走同一个 Flask 接口 `/api/skill/image`。Agent 内部根据 prompt 里有没有 `image_path1` + `image_path2` 决定调用哪个 tool。

---

## 步骤 13：Java 通过 HTTP 调用 Python Agent

这是整条链路**最耗时**的一步。Java 从 OSS 下载原图 → multipart 发给 Python → 等 Python 调完 AI 返回结果 → 下载结果图 → 重新上传 OSS。

**代码文件**：`loomai-main/src/main/java/com/lsq/service/impl/PythonAiIntegrationServiceImpl.java`

```java
public EditImageResponse edit(EditImageRequest editImageRequest) {
    String sourceImageUrl = editImageRequest.getImage();
    String instruction = editImageRequest.getInstruction();
    Path sourceTemp = null;
    Path editTemp = null;

    try {
        // 1. OSS URL → 下载到本地临时文件
        sourceTemp = downloadToTempFile(sourceImageUrl);

        // 2. 构造 multipart/form-data
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(sourceTemp.toFile()));
        body.add("instruction", instruction);

        // 3. POST → Flask :5000/api/skill/image（这一步可能等几分钟）
        ResponseEntity<String> response = restClient.post()
                .uri("/api/skill/image")
                .body(body)
                .retrieve()
                .toEntity(String.class);

        // 4. 解析 Python 返回: {"success": true, "url": "https://dashscope.aliyuncs.com/temp/xxx.png"}
        JsonNode result = objectMapper.readTree(response.getBody());
        String pythonUrl = result.path("url").asText("");

        // 5. 下载 AI 生成的图片到本地
        editTemp = downloadToTempFile(pythonUrl);

        // 6. 二次上传到自己的 OSS 持久化
        String objectKey = "image/edited" + UUID.randomUUID() + ".png";
        String saveUrl = uploadEditedFileToOss(objectKey, editTemp,
                                 Files.probeContentType(editTemp));

        // 7. 返回两个 URL
        EditImageResponse resp = new EditImageResponse();
        resp.setUrl(pythonUrl);    // DashScope 临时 URL（几小时后过期）
        resp.setSaveUrl(saveUrl);  // 自己的 OSS URL（永久）
        return resp;

    } finally {
        // 无论成败，清理临时文件
        Files.deleteIfExists(sourceTemp);
        Files.deleteIfExists(editTemp);
    }
}
```

数据流：**OSS 原图 → Java 临时文件 → Flask → DashScope API → DashScope 临时 URL → Java 临时文件 → 自己的 OSS**

### 面试官深挖

**Q: 为什么不直接把 OSS URL 传给 Python，让 Python 自己下载？**

当前 Flask 接口 `/api/skill/image` 接收 `multipart/form-data`，Agent tool 函数 `edit_image_tool(image_path: str, ...)` 需要本地文件路径。如果改传 URL，Python 要加下载逻辑，且 OSS 可能是内网地址 Flask 访问不到。当前设计让 Python 只负责"接收文件 → 调 AI → 返回结果"，职责纯粹。但也带来了额外开销：同一张图 Java 下载一次、传给 Python、Python 再读到内存转 base64。

**Q: 为什么要二次上传到自己的 OSS？**

DashScope 返回的图片 URL 是阿里云临时存储，有效期通常几小时到几天。用户需要永久保存编辑结果。`pythonUrl` 短期可用，`saveUrl` 是永久地址——前端展示优先用 `saveUrl`。

**Q: `finally` 里 `deleteIfExists` 抛异常会覆盖原始异常吗？**

会。如果 try 里业务抛异常 A，进入 finally 后 deleteIfExists 抛异常 B，异常 B 覆盖异常 A。调用方看到的是"删除临时文件失败"而不是真正的业务异常。正确写法：

```java
} finally {
    try { Files.deleteIfExists(sourceTemp); } catch (IOException ignored) {}
    try { Files.deleteIfExists(editTemp); } catch (IOException ignored) {}
}
```

---

## 步骤 14：Python Agent 处理 — LLM + Tool Calling

这是整条链路中最有技术含量的一环：Flask 收到 Java 的文件 + instruction → 构造 prompt → LLM 决策调用哪个 tool → 执行 tool → DashScope API → 返回结果。

### 14.1 Flask 入口 + 构造 Agent 参数

**代码文件**：`loomai-agent/server.py`

```python
@app.route("/api/skill/image", methods=["POST"])
def skill_image_api():
    out = skill_image()
    return jsonify({"success": True, "url": out})


def skill_image() -> str:
    if deep_agent is None:
        return ""

    instruction = request.form.get("instruction")
    if not instruction:
        return ""

    tmp_dir = tempfile.gettempdir()
    tmp_paths = []

    prompt_lines = [
        "你必须调用一个工具，并且只输出 JSON格式",
        f"instruction: {instruction}"
    ]

    # 不靠 AI 判断编辑还是合并，直接检查 form 中有几个 file 字段
    if "file1" in request.files and "file2" in request.files:
        # === 合并模式 ===
        data1 = request.files["file1"].read()
        data2 = request.files["file2"].read()
        p1 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_1.bin")
        p2 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_2.bin")
        with open(p1, "wb") as f: f.write(data1)
        with open(p2, "wb") as f: f.write(data2)
        prompt_lines.append(f"image_path1: {p1}")
        prompt_lines.append(f"image_path2: {p2}")
    else:
        # === 编辑模式 ===
        data = request.files["file"].read()
        p = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}.bin")
        with open(p, "wb") as f: f.write(data)
        prompt_lines.append(f"image_path: {p}")

    out = invoke_agent("\n".join(prompt_lines))
    return out.get("url", "")
```

最终发给 Agent 的 prompt 示例（编辑）：

```
你必须调用一个工具，并且只输出 JSON格式
instruction: 把背景换成白色
image_path: /tmp/aiwear_abc123.bin
```

或（合并）：

```
你必须调用一个工具，并且只输出 JSON格式
instruction: 把上衣和裤子搭配成一套穿搭
image_path1: /tmp/aiwear_abc123_1.bin
image_path2: /tmp/aiwear_abc123_2.bin
```

### 14.2 Agent 初始化

```python
llm = ChatTongyi(model_name="qwen-plus", temperature=0.1, dashscope_api_key=API_KEY)

@tool
def edit_image_tool(image_path: str, instruction: str) -> str:
    """编辑单张图片"""

@tool
def merge_image_tool(image_path1: str, image_path2: str, instruction: str) -> str:
    """合并两张图片"""

skills_tools = [edit_image_tool, merge_image_tool]

deep_agent = create_deep_agent(
    model=llm,
    tools=skills_tools,
    skills=["/skills/"],
    system_prompt=(
        "你是一个智能图片处理助手，你可以调用一个工具：\n"
        "- edit_image_tool：编辑单张图片 \n"
        "- merge_image_tool：合并两张图片 \n"
        "最终的输出格式JSON: {\"success\":true, \"url\":\"字符串类型\"} ..."
    )
)
```

`temperature=0.1` 是关键——Agent 场景需要精确的工具选择，高温度会让 LLM 随机选错 tool 或编造参数。

### 14.3 LLM 决策过程

```
输入 Prompt:
  "你必须调用一个工具，并且只输出 JSON格式
   instruction: 把背景换成白色
   image_path: /tmp/aiwear_abc123.bin"

            │
            ▼
Qwen-Plus 推理:
  1. 读到 image_path → 单图，不是合并
  2. 单图 → 应该调用 edit_image_tool
  3. 提取参数: image_path="/tmp/aiwear_abc123.bin", instruction="把背景换成白色"
  4. 输出 function_call: edit_image_tool(...)

            │
            ▼
LangChain 框架自动执行 edit_image_tool()
  → 读文件 → 转 base64 → DashScope API → 返回新图片 URL

            │
            ▼
LLM 最终输出 JSON:
  {"success": true, "url": "https://dashscope.aliyuncs.com/temp/gen_xxx.png"}
```

### 14.4 Tool 内部实现

```python
@tool
def edit_image_tool(image_path: str, instruction: str) -> str:
    with open(image_path, "rb") as f:
        image_data = f.read()

    # bytes → base64 data URI
    image_data_uri = process_image(image_data)

    messages = [{
        "role": "user",
        "content": [
            {"image": image_data_uri},
            {"text": instruction}
        ]
    }]

    response = MultiModalConversation.call(
        model="qwen-image-edit-plus",
        messages=messages
    )

    url = response['output']['choices'][0]['message']['content'][0]['image']
    return json.dumps({"success": True, "url": url})
```

merge_image_tool 逻辑一样，区别是 content 数组放两张图：

```python
messages = [{
    "role": "user",
    "content": [
        {"image": image_data_uri1},
        {"image": image_data_uri2},
        {"text": instruction},
    ]
}]
```

**同一个模型 `qwen-image-edit-plus` 同时支持单图编辑和多图合并**——DashScope 根据消息中的图片数量自动处理。

### 面试官深挖

**Q: 为什么需要 Agent（LLM + Tool Calling）？直接 if-else 判断然后调 API 不行吗？**

当前场景 if-else 确实够用——`skill_image()` 已经用 `"file1" in request.files` 判断了。Agent 的价值在于**未来扩展性**：
- 如果新增 5 种图片操作（去水印、换脸、美颜、调色、抠图），if-else 会变得臃肿，而 Agent 只需加一个新的 `@tool` 函数，LLM 自动学习何时调用它。
- 自然语言 instruction（"把这个人P瘦一点"）→ LLM 理解意图 → 选择合适 tool → 提取参数。纯 if-else 做不到。

**Q: base64 传图和传 URL 各有什么利弊？**

- base64：数据自包含，不需要外部存储。但编码膨胀约 33%，大图 JSON body 巨大。
- URL：JSON body 小，Python 按需下载。但图片需公网可访问，加了网络 I/O。
- 当前用 base64 是因为 DashScope `MultiModalConversation.call()` API 原生支持 `{"image": "data:image/png;base64,..."}` 格式。

**Q: 为什么 DashScope 用 SDK 而不是 HTTP API？**

`dashscope` Python SDK 封装了鉴权、重试、超时。手动调 HTTP API 需要自己处理 API Key 签名和错误码。但 SDK 引入了一个依赖，如果版本不兼容会出问题（就像本次项目的 deepagents 和 langgraph 版本冲突）。

**Q: `skill_image()` 写临时文件后没有清理，会不会泄露？**

是的。`tmp_paths` 列表记录了文件路径，但函数结束前没有 `os.remove()`。这些临时文件会堆积在系统 temp 目录，直到操作系统清理。长时间运行的 Agent 服务可能撑满磁盘。应该在函数末尾加清理逻辑。

---

## 步骤 15：更新 Redis + 写 MySQL 操作记录

Consumer 在 AI 调用成功后，更新 Redis 状态为 SUCCESS，同时写 MySQL 操作记录。

```java
// Redis 状态变化
PENDING  →  PROCESSING  →  SUCCESS
                              │
                    {"taskId": "...",
                     "status": "SUCCESS",
                     "resultUrl": "https://dashscope.aliyuncs.com/temp/xxx.png",
                     "saveUrl": "https://lsq67.oss-cn-beijing.aliyuncs.com/image/edited/xxx.png"}
```

```java
// MySQL records 表
public void editSave(Long userId, EditImageRequest request, EditImageResponse response) {
    Record record = new Record();
    record.setUserId(userId);
    record.setAction("edit");
    record.setInputOssUrl1(request.getImage());
    record.setInstruction(request.getInstruction());
    record.setResultUrl(response.getUrl());
    record.setOutputOssUrl(response.getSaveUrl());
    recordMapper.insert(record);
}
```

### 面试官深巡

**Q: 三个状态（PENDING → PROCESSING → SUCCESS）有必要吗？两个不行吗？**

三个状态给用户进度感知。PROCESSING 告诉用户"正在处理中，不是卡死了"。如果只有 PENDING → SUCCESS，中间几分钟无任何状态变化，体验差。

**Q: Consumer 处理到一半进程 crash 了，Redis 永远卡在 PROCESSING？**

TTL 1 小时兜底。但更好的做法：加定时任务扫描 PROCESSING 超过 N 分钟的任务自动标为 FAILED；或者 Consumer 启动时检查未完成的遗留任务。

**Q: `result_url` 是 DashScope 临时 URL，存 MySQL 有意义吗？**

短期有意义（几小时内可二次处理），长期失效。`output_oss_url` 是永久的。未来可只保留永久 URL。

---

## 步骤 16：前端轮询查询状态

```java
@GetMapping("/status/{taskId}")
public Result<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
    return Result.success("查询成功", fileService.getTaskStatus(taskId));
}

public TaskStatusResponse getTaskStatus(String taskId) {
    String json = stringRedisTemplate.opsForValue().get("task:" + taskId);
    if (json == null) {
        throw new RuntimeException("任务不存在或已过期");
    }
    return objectMapper.readValue(json, TaskStatusResponse.class);
}
```

前端伪代码：

```javascript
async function pollTask(taskId) {
    const { data } = await fetch(`/api/task/status/${taskId}`).then(r => r.json());
    if (data.status === 'SUCCESS') showImage(data.saveUrl);
    else if (data.status === 'FAILED') showError(data.errorMessage);
    else setTimeout(() => pollTask(taskId), 2000);  // 2秒重试
}
```

### 面试官深挖

**Q: 轮询有什么问题？更好的方案？**

- **延迟**：AI 在 2 秒间隔中完成，要等到下一个周期才知道。
- **无效请求**：大部分返回 PENDING/PROCESSING，空耗资源。

改进：
- **WebSocket**：服务端主动推送，零延迟。`spring-boot-starter-websocket`。
- **SSE（Server-Sent Events）**：更轻量，单向推送。`SseEmitter` + `EventSource`。
- **指数退避轮询**：1s → 2s → 4s → 8s... 减少后端压力。

---

# 第三部分：可靠性问题汇总

## 问题 1：Auto-ACK 导致消息可能丢失

handleTask catch 了所有异常，方法正常返回 → 消息一定被 ACK。如果 Consumer 进程在 handleEditTask 执行中被 kill（已调 DashScope 但来不及更新 Redis），消息已投递但连接断开 → NACK → 重回队列 → 再次投递 → **重复调 DashScope API**（浪费钱）。

**改进**：MANUAL ACK + 在 Redis 记录幂等 key（`SETNX task:{taskId}:consumed 1`），消费前先检查是否已处理过。

## 问题 2：没有死信队列

处理失败的消息被 ACK 后丢弃，无法追溯。

**改进**：定义死信 Exchange + DLQ Queue，`x-dead-letter-exchange` 自动路由失败消息到 DLQ。

## 问题 3：Redis 写成功但 MQ 发失败

Redis 残留 PENDING，用户轮询永远完成不了。

**改进**：MQ 发送失败时删除 Redis key 或更新为 FAILED。

## 问题 4：上传链路重复调用 Qwen-VL-Max

`validate_image` 和 `registerUploadedImage` 各调了一次 `describe_image()`，同一张图描述两次。

**改进**：第一次描述后缓存到 Redis，第二次直接读缓存。

## 问题 5：临时文件泄露

`skill_image()` 和 `PythonAiIntegrationServiceImpl.edit()` 的临时文件可能没有被清理。

---

# 完整链路文件索引

| 步骤 | 代码文件 | 核心方法 |
|------|---------|---------|
| 上传入口 | `controller/FileController.java` | `uploadImage()` |
| 上传编排 | `service/impl/FileServiceImpl.java` | `uploadImage()`, `validateFile()`, `saveToLocalFromPath()`, `uploadToOssFromLocal()` |
| Java → Python 审核 | `service/impl/PythonAiIntegrationServiceImpl.java` | `validateImage()` |
| Python 审核 | `loomai-agent/server.py` | `validate_image_api()`, `describe_image()`, `validate_image()` |
| Python CLIP 登记 | `loomai-agent/server.py` | `upload_image_api()`, `clip_image_to_512d()` |
| MQ 基础设施 | `config/RabbitMqConfig.java` | `taskExchange()`, `taskQueue()`, `editBinding()`, `mergeBinding()` |
| 消息体 | `dto/TaskMessage.java` | 字段：taskId, userId, type, params |
| 异步提交入口 | `controller/TaskController.java` | `submitEdit()`, `submitMerge()` |
| 异步编排 | `service/impl/FileServiceImpl.java` | `submitEditAsync()`, `submitMergeAsync()`, `saveInitialTaskStatus()` |
| 发 MQ | `service/TaskMessageProducer.java` | `sendEditTask()`, `sendMergeTask()` |
| 消费 MQ | `service/impl/TaskMessageConsumer.java` | `handleTask()`, `handleEditTask()`, `handleMergeTask()` |
| Java → Python 编辑 | `service/impl/PythonAiIntegrationServiceImpl.java` | `edit()`, `merge()`, `downloadToTempFile()`, `uploadEditedFileToOss()` |
| Flask 入口 | `loomai-agent/server.py` | `skill_image_api()`, `skill_image()` |
| Agent 执行 | `loomai-agent/server.py` | `invoke_agent()`, `create_deep_agent()` |
| 编辑 Tool | `loomai-agent/server.py` | `edit_image_tool()` |
| 合并 Tool | `loomai-agent/server.py` | `merge_image_tool()` |
| 查询状态 | `service/impl/FileServiceImpl.java` | `getTaskStatus()` |
| 写操作记录 | `service/impl/RecordServiceImpl.java` | `editSave()`, `mergeSave()` |
| MQ 连接配置 | `application.yml` | `spring.rabbitmq.*`, `prefetch: 1` |
| JWT 工具 | `utill/JwtUtil.java` | `generateToken()`, `parseToken()`, `getUserId()`, `removeToken()` |
