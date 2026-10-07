# Flash 智能助手扩展改造方案

> 目标：在**不破坏现有功能**的前提下，新增一个独立的「Flash 实时对话助手」，具备三个能力：
> 1. **实时对话**（SSE 流式输出，纯文字）
> 2. **RAG**（服装搭配知识库检索增强）
> 3. **记忆**（短期会话记忆 + 长期用户画像）

---

## 0. 核心原则（每次改动前先读这一节）

1. **现有功能零改动**：图片编辑、图片合并、我的图片、历史记录、登录、图片搜索、异步任务等**全部保持原样**，不删除、不重写任何现有逻辑。
2. **现有文件只「追加」不「重写」**：需要改动的现有文件（如 `router/index.js`、`AppSidebar.vue`、`api.js`、`server.py`）一律**只在末尾追加**新内容，不动原有代码。
3. **新功能全部独立**：新路由、新页面、新接口、新表、新文件全部独立命名，与现有功能物理隔离。
4. **回滚容易**：新增代码如果出问题，直接删掉新增文件、撤销追加内容即可，不影响老功能。

---

## 1. 架构总览

```
前端 Vue（新增独立页面 FlashView，不碰现有 4 个功能页）
   │  fetch 流式读取（带 JWT header）—— 不用原生 EventSource（它带不了 header）
   ▼
Java Spring Boot（新增 FlashController，唯一业务入口 + JWT 鉴权）
   │  1. 查用户长期画像（MySQL user_profile）
   │  2. SseEmitter 转发到 Python /api/chat，并透传流回前端
   ▼
Python Flask（新增 /api/chat 流式接口）
   ├─ 短期记忆：Redis（sessionId → 对话历史，ConversationSummaryMemory 摘要）
   ├─ RAG：ChromaDB 检索服装搭配知识 → 拼进 prompt
   ├─ 长期记忆：由 Java 传入的用户画像（偏好）拼进 prompt
   └─ 大模型：ChatTongyi 流式生成
```

**技术选型（已确认）：**

| 项 | 选型 |
|----|------|
| 向量数据库 | ChromaDB（本地持久化） |
| 文本 embedding | DashScope `text-embedding-v3` |
| 传输协议 | SSE（Server-Sent Events） |
| 聊天路由 | 前端 → Java（JWT 鉴权 + SseEmitter 转发）→ Python |
| 短期记忆 | Redis + LangChain `ConversationSummaryMemory` |
| 长期记忆 | MySQL `user_profile` 表（Java 侧读写） |
| 前端形态 | 独立页面 `FlashView`，侧栏加一个入口，不动现有页面 |

---

## 2. 模块一：依赖与准备

- [x] **2.1 Python 依赖安装**：在 `loomai-agent` 的虚拟环境里安装 `chromadb`、`langchain`（`ConversationSummaryMemory` 所在包）、确认 `dashscope` 已装。
  - 验收：`python -c "import chromadb; import langchain; import dashscope"` 不报错。

- [x] **2.2 知识库文档准备**：在 `loomai-agent/knowledge_base/` 下新建 3~4 篇占位 markdown（内容先编，后续可替换）。
  - 建议文件名：`色彩搭配.md`、`风格指南.md`、`体型穿搭.md`、`场合穿搭.md`
  - 每篇 10~20 行干货即可，用于跑通 RAG。

- [x] **2.3 确认 Java 侧零新依赖**：SSE 用 Spring MVC 自带的 `SseEmitter`，调 Python 流式接口用 JDK 自带 `java.net.http.HttpClient`（Java 17 已内置），**不需要新增 pom 依赖**。
  - 验收：确认 `pom.xml` 无需改动（或仅记录结论）。

---

## 3. 模块二：Python 侧（`loomai-agent`）

### 3.1 RAG 知识库（新增独立文件，不碰 `server.py` 现有逻辑）

- [x] **3.1.1 新建 `loomai-agent/rag.py`**：封装 ChromaDB 初始化和检索。
  - 用 `chromadb.PersistentClient(path=...)`，collection 名如 `fashion_knowledge`。
  - embedding 函数：调 DashScope `text-embedding-v3`（`TextEmbedding.call(model="text-embedding-v3", input=text)`），取返回的向量。
  - 提供 `ingest()`（读 `knowledge_base/*.md` 分段入库）和 `retrieve(query, top_k=3)`（返回最相关文本片段）。
  - 验收：手动跑一次 `ingest()`，ChromaDB 目录生成、能 `retrieve()` 出结果。

- [x] **3.1.2 新建 `loomai-agent/knowledge_base/*.md`**：写占位知识文档（与 2.2 对应）。

### 3.2 短期记忆（Redis，复用现有 `_redis_client`）

- [x] **3.2.1 新建 `loomai-agent/memory.py`**：封装会话记忆的读写。
  - `get_history(session_id)` / `save_history(session_id, messages)`，Redis key 如 `chat:session:{session_id}`，设 TTL（如 2 小时）。
  - 集成 LangChain `ConversationSummaryMemory`：对话变长时用 `llm` 做摘要，减少 token。
  - 验收：能对同一 session 连续对话，且能读到历史上下文。

### 3.3 聊天流式接口（在 `server.py` **末尾追加**新路由）

- [x] **3.3.1 追加 `/api/chat` 路由（SSE 流式）**：
  - 入参：JSON body `{userId, sessionId, message, profile:{style,color,bodyType}}`。
  - 流程：读短期记忆 → RAG 检索相关知识 → 拼系统人设 + 知识 + 记忆 + 画像 → `ChatTongyi` 流式生成。
  - 返回：`Response(stream_with_context(...), mimetype="text/event-stream")`，逐 token `yield "data: {token}\n\n"`，结束 `yield "data: [DONE]\n\n"`。
  - 验收：`curl -N` 能逐字看到输出。

- [x] **3.3.2 组装系统 Prompt**：定义「Flash」服装搭配助手人设，约束只做穿搭建议，语气自然，**纯文字不涉及图片**。
  - 结构：`人设 + 用户画像 + RAG 检索到的知识 + 短期记忆历史 + 当前问题`。

- [x] **3.3.3 对话结束后回写短期记忆**：把本轮问答追加进 Redis session。

> 说明：长期记忆（用户画像）**不在 Python 侧落库**，由 Java 查好通过 `profile` 字段传进来，Python 只负责「用」。这样 Python 完全不碰 MySQL，数据边界清晰。

---

## 4. 模块三：Java 侧（`loomai-main`）

### 4.1 长期记忆（MySQL）

- [x] **4.1.1 新建表 `user_profile`**：`id`、`user_id`（唯一）、`style`、`color`、`body_type`、`updated_at`。
  - 验收：表在 `bite_wear` 库创建成功。

- [x] **4.1.2 新建 `entity/UserProfile.java`**：`@TableName("user_profile")`，字段与表对应。

- [x] **4.1.3 新建 `mapper/UserProfileMapper.java`**：`extends BaseMapper<UserProfile>`。

- [x] **4.1.4 新建 `service/UserProfileService.java` + `impl`**：提供 `getByUserId`（查画像，无则返回空）和 `saveOrUpdate`（存画像）。

### 4.2 Flash 对话接口（SSE 转发）

- [x] **4.2.1 新建 `dto/request/FlashChatRequest.java`**：`message`（用户输入）、可选 `sessionId`。

- [x] **4.2.2 新建 `controller/FlashController.java`**：
  - `@RequestMapping("/api/flash")`。
  - `POST /api/flash/chat`：返回 `SseEmitter`，从 `@RequestHeader("Authorization")` 做 JWT 鉴权拿 userId（复用 `JwtUtil`）。
  - 流程：查 `user_profile` → 组装请求转发 Python `/api/chat` → 用 `HttpClient` 读 Python 的 SSE 流 → `emitter.send()` 逐条透传给前端。
  - 用独立线程/`CompletableFuture.runAsync` 处理，避免阻塞 Tomcat 线程。
  - 验收：Postman/curl 能拿到流式输出。

- [x] **4.2.3 新建 `service/FlashService.java` + `impl`**：封装「转发 Python 流式接口」逻辑，方法如 `streamChat(...)` 返回流。

### 4.3 画像读写接口

- [x] **4.3.1 `FlashController` 追加 `GET /api/flash/profile`**：返回当前用户画像。

- [x] **4.3.2 `FlashController` 追加 `POST /api/flash/profile`**：保存画像 `{style, color, bodyType}`（前端 Flash 页可提供一个简单「我的偏好」设置）。

> 说明：第一版长期记忆用「用户显式设置的画像」，简单可靠；「自动从对话提取偏好」作为后续增强，不在本期范围。

---

## 5. 模块四：前端（`loomai-fronted`）

### 5.1 路由与菜单（追加，不动现有项）

- [x] **5.1.1 `router/index.js` 末尾追加** `/flash` 路由：`component: () => import('../views/FlashView.vue')`，`meta: { auth: true, title: 'Flash 助手' }`。

- [x] **5.1.2 `components/AppSidebar.vue` 末尾追加**一个 `RouterLink to="/flash"`，文案「Flash 助手」，图标可复用现有 svg 或新建一个。

### 5.2 Flash 聊天页（新增独立文件）

- [x] **5.2.1 新建 `views/FlashView.vue`**：聊天界面（消息列表 + 输入框 + 发送按钮）。
  - 纯文字交流，**不做文件/图片上传**。

- [x] **5.2.2 流式读取（关键）**：**不用原生 `EventSource`**（它无法带 `Authorization` header），改用 `fetch` + `response.body.getReader()` 逐段读取，配合 `TextDecoder` 解码，实现打字机效果。

- [x] **5.2.3 会话管理**：页面挂载时生成一个 `sessionId`（uuid），随每次请求传递，用于短期记忆。

### 5.3 API 封装（追加）

- [x] **5.3.1 `services/api.js` 末尾追加**：`flashChat(message, sessionId)`（返回流式 fetch）、`getFlashProfile()`、`saveFlashProfile(payload)`。

---

## 6. 模块五：联调与验证

- [x] **6.1 启动顺序**：Docker（MySQL/Redis/RabbitMQ）→ Java（8081）→ Python（5000）→ 前端。

- [x] **6.2 RAG 单测**：先跑通 Python 侧 `ingest()` + `retrieve()`，确认知识库能检索出相关片段。

- [x] **6.3 Flash 端到端测试**：前端进 Flash 页 → 发一条「帮我推荐一套通勤穿搭」→ 确认逐字流式输出、且回答里体现了知识库内容。

- [x] **6.4 短期记忆验证**：在同一会话连续问「那我换成约会穿呢」，确认 Flash 能接上文（知道前一句在聊通勤）。

- [x] **6.5 长期记忆验证**：设置偏好「简约/黑白灰」→ 重新对话，确认回答风格受影响。

- [x] **6.6 现有功能回归验证（重要）**：逐项确认 **图片编辑、图片合并、我的图片、历史记录、登录、图片搜索** 全部仍正常，无任何报错。

---

## 7. 附录 A：文件清单

### 新增文件

**Python（`loomai-agent/`）**
- `rag.py` — RAG（ChromaDB + embedding + 检索）
- `memory.py` — 短期记忆（Redis + ConversationSummaryMemory）
- `knowledge_base/*.md` — 知识库文档

**Java（`loomai-main/src/main/java/com/lsq/`）**
- `controller/FlashController.java`
- `service/FlashService.java` + `service/impl/FlashServiceImpl.java`
- `service/UserProfileService.java` + `service/impl/UserProfileServiceImpl.java`
- `entity/UserProfile.java`
- `mapper/UserProfileMapper.java`
- `dto/request/FlashChatRequest.java`
- `dto/request/FlashProfileRequest.java`

**前端（`loomai-fronted/src/`）**
- `views/FlashView.vue`

### 修改文件（仅追加）

- `loomai-agent/server.py` — 末尾追加 `/api/chat` 路由
- `loomai-main/.../controller/FlashController.java`（新文件，含 chat + profile 接口）
- `loomai-fronted/src/router/index.js` — 追加 `/flash` 路由
- `loomai-fronted/src/components/AppSidebar.vue` — 追加菜单项
- `loomai-fronted/src/services/api.js` — 追加 Flash 相关接口

### 明确不改动的文件（红线）

`FileController`、`FileServiceImpl`、`PythonAiIntegrationServiceImpl`（现有方法）、`TaskController`、`TaskMessageConsumer`、`EditView.vue`、`MergeView.vue`、`ImagesView.vue`、`RecordsView.vue`、`LoginView.vue`、`docker-compose.yml`（除非需要加服务）。

---

## 8. 附录 B：接口契约（约定）

### Python `/api/chat`（SSE）
```
POST /api/chat
body: { "userId": "1", "sessionId": "uuid", "message": "帮我推荐通勤穿搭",
        "profile": { "style": "简约", "color": "黑白灰", "bodyType": "偏瘦" } }
response: text/event-stream
  每帧: data: {token}\n\n
  结束: data: [DONE]\n\n
```

### Java `/api/flash/chat`（SSE）
```
POST /api/flash/chat
header: Authorization: Bearer <jwt>
body: { "message": "帮我推荐通勤穿搭", "sessionId": "uuid" }
response: text/event-stream（透传 Python）
```

### Java 画像接口
```
GET  /api/flash/profile   → { code, message, data: { style, color, bodyType } }
POST /api/flash/profile   → body { style, color, bodyType }
```

---

## 9. 进度追踪说明（给 AI 的约定）

- 每个 `- [ ]` 是一个**可独立验收的小步骤**，完成后把 `[ ]` 改成 `[x]`。
- 完成标准 = 该步骤后面的「验收」条件达成。
- 每完成一个模块，先跑该模块的验收，再进入下一个模块，**不要跨模块跳着改**。
- 全程守住第 0 节的红线：现有功能零改动。
