# Flash 实时对话助手 — 完整链路分析

> 覆盖 Vue 前端 + Java 后端 + Python Agent，从用户提问到流式回复的完整链路。
> 重点讲解三项扩展能力：SSE 流式输出、RAG 检索增强、长短期记忆。

---

## 总体链路一览

```
┌─────────────────────────────────────────────────────────────────┐
│  第一部分：SSE 流式输出链路（前端 → Java → Python）                 │
│                                                                 │
│  用户在 Flash 页输入问题                                          │
│      │                                                          │
│      ▼                                                          │
│  fetch POST /api/flash/chat （Authorization: Bearer <JWT>）      │
│      │                                                          │
│      ├─ ① 前端用 fetch + ReadableStream 手动解析 SSE             │
│      │      （不能用 EventSource：它带不了 Authorization 头）      │
│      │                                                          │
│      ▼                                                          │
│  Java FlashController.chat()                                    │
│      │                                                          │
│      ├─ ② JWT 鉴权 → 拿 userId                                  │
│      ├─ ③ 新建 SseEmitter(5min) + CompletableFuture 异步处理      │
│      │                                                          │
│      ▼                                                          │
│  Java FlashServiceImpl.streamChat()                             │
│      │                                                          │
│      ├─ ④ 查 user_profile（长期画像）                            │
│      ├─ ⑤ 组装 JSON body（userId + sessionId + message + profile）│
│      ├─ ⑥ HttpClient.BodyHandlers.ofLines() 读 Python SSE 流     │
│      └─ ⑦ 逐条解析 data: 行 → emitter.send() 透传给前端           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  第二部分：Python /api/chat 组装 + 流式生成                        │
│                                                                 │
│  ⑧ 短期记忆：Redis 加载 ConversationSummaryMemory 摘要            │
│  ⑨ RAG：Query改写 → 向量+BM25混合召回 → 阈值 → RRF → Rerank     │
│  ⑩ 长期画像：拼入 system prompt                                  │
│  ⑪ 组装 system_content = 人设 + 画像 + 知识 + 历史摘要            │
│  ⑫ Generation.call(stream=True, incremental_output=True)        │
│      └─ 逐 token yield "data: {\"delta\": \"...\"}"             │
│  ⑬ 生成结束 → 用 LLM 更新摘要 → 写回 Redis（TTL 2h）              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                     前端把每个 delta 追加到
                     气泡里 → 打字机效果
```

---

# 第一部分：SSE 流式输出链路（前端 → Java → Python）

Flash 与传统「请求-响应」接口最大的区别在于：答案不是一次性返回，而是**逐 token 流式推送**。这条链路跨越三个进程，每一层都承担了「透传 + 解析」的职责。

## 为什么 Flash 用 SSE 而不是 WebSocket 或轮询？

- **WebSocket** 是双向全双工，适合聊天室、协作编辑这类需要服务端主动推送的场景。Flash 是单向的「问一句答一句」，WebSocket 过重（握手、心跳、连接管理都更复杂）。
- **轮询** 延迟高、空耗资源——每次轮询大部分返回「还没好」。
- **SSE（Server-Sent Events）** 是 HTTP 之上的单向流，服务端持续推送、浏览器自动重连，实现简单，天然契合「打字机」式流式输出。

**唯一的坑**：浏览器原生 `EventSource` 对象**只能发 GET、不能自定义请求头**，而我们的接口需要 `Authorization: Bearer <JWT>` 鉴权。所以前端放弃了 `EventSource`，改用 `fetch + ReadableStream` 手动解析 SSE 文本。

---

## 步骤 1：前端发起请求并手动解析 SSE

前端封装了两个层次：`api.js` 负责发请求并返回流，`FlashView.vue` 负责读流并渲染。

**代码文件**：`loomai-fronted/src/services/api.js`

```javascript
/**
 * Flash 实时对话（SSE 流式）。
 * 说明：不能用 axios（浏览器端 axios 不支持读流式响应体），改用原生 fetch + ReadableStream。
 * 返回 response.body（ReadableStream），调用方用 getReader() 逐段读取并解析 SSE。
 */
export async function flashChat({ message, sessionId }) {
  const auth = useAuthStore()
  const res = await fetch('/api/flash/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${auth.token}`,
    },
    body: JSON.stringify({ message, sessionId }),
  })
  if (!res.ok || !res.body) {
    throw new Error(`请求失败（${res.status}）`)
  }
  return res.body
}
```

**代码文件**：`loomai-fronted/src/views/FlashView.vue`

```javascript
async function handleSend() {
  const text = input.value.trim()
  if (!text || streaming.value) return

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  messages.value.push({ role: 'assistant', content: '' })
  const assistantMsg = messages.value[messages.value.length - 1]  // 响应式代理，直接改 content 触发渲染
  streaming.value = true

  try {
    const stream = await flashChat({ message: text, sessionId: sessionId.value })
    const reader = stream.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const rawEvent = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        handleSseEvent(rawEvent, assistantMsg)
      }
      scrollToBottom()
    }
    if (buffer.trim()) handleSseEvent(buffer, assistantMsg)  // 兜底：处理没以 \n\n 结尾的残留事件
    if (!assistantMsg.content) assistantMsg.content = '（未收到回复）'
  } catch (err) {
    assistantMsg.content += '\n[出错了：' + (err?.message || '未知错误') + ']'
    ElMessage.error(err?.message || '对话失败')
  } finally {
    streaming.value = false
    scrollToBottom()
  }
}

function handleSseEvent(rawEvent, assistantMsg) {
  for (const line of rawEvent.split('\n')) {
    if (!line.startsWith('data:')) continue
    const payload = line.slice('data:'.length).trim()
    if (!payload) continue
    let obj
    try { obj = JSON.parse(payload) } catch { continue }
    if (obj && typeof obj.delta === 'string') {
      assistantMsg.content += obj.delta          // 逐 token 追加
    } else if (obj && typeof obj.error === 'string') {
      assistantMsg.content += '\n[出错了：' + obj.error + ']'
    }
  }
}
```

关键设计点：

- **SSE 的边界是空行 `\n\n`**：标准 SSE 事件用 `\n\n` 分隔。但 `reader.read()` 每次返回的 chunk 不一定正好对齐事件边界（可能半个事件、可能多个事件），所以用 `buffer` 累积、`indexOf('\n\n')` 切分、残留部分留在 buffer 等下一轮。
- **`TextDecoder({ stream: true })`**：处理多字节字符（中文）被 TCP 分片截断的情况。`stream: true` 让 decoder 记住未完成的字节序列，等下一段数据补全后再解码，避免中文乱码。
- **`assistantMsg` 是响应式代理**：Vue 的 `ref([])` 数组元素是 `reactive` 代理，直接 `assistantMsg.content += delta` 就能触发视图更新，无需重新赋值整个数组。
- **`streaming` 标志防重入**：流式输出期间禁用发送按钮和回车，防止用户连发导致多个流交错。

### 面试官深挖

**Q: 为什么不用 axios 做流式请求？**

axios 基于 XHR，`responseType` 只支持 `arraybuffer`/`blob`/`document`/`json`/`text`，**没有 `stream` 类型**，它会把整个响应体全部读完才返回。流式场景必须用原生 `fetch`，通过 `response.body.getReader()` 边收边读。

**Q: `buffer.indexOf('\n\n')` 的分割方式有什么隐患？**

如果某个 delta 的文本内容里恰好包含 `\n\n`（比如模型输出了一段多行文本），会被误判为事件边界、提前切分。不过本项目 delta 是被 JSON 包裹的（`data: {"delta":"..."}`），JSON 字符串里的换行被转义成 `\n` 字面量，不会出现真实换行符，所以安全。更严谨的做法是给事件加 `id:` 或用 `TextEncoder` 按字节边界严格解析。

**Q: 前端如何防止一个用户同时开多个流？**

`streaming.value` 标志：发送时置 true、`finally` 里置 false。发送按钮和回车都检查 `streaming`，避免重复请求。这是「前端互斥」的简单实现，服务端没有做幂等校验。

---

## 步骤 2：Java Controller — SseEmitter + 异步

**代码文件**：`loomai-main/src/main/java/com/lsq/controller/FlashController.java`

```java
@RestController
@RequestMapping("/api/flash")
public class FlashController {

    @PostMapping("/chat")
    public SseEmitter chat(@RequestHeader("Authorization") String authorization,
                           @RequestBody @Validated FlashChatRequest request) {
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        SseEmitter emitter = new SseEmitter(300_000L);   // 5 分钟超时
        // 异步处理，避免阻塞 Tomcat 线程
        CompletableFuture.runAsync(() ->
                flashService.streamChat(emitter, userId, request.getSessionId(), request.getMessage()));
        return emitter;   // 立即返回，线程不阻塞
    }
}
```

关键设计点：

- **`SseEmitter` 是 Spring MVC 对流式响应的抽象**：Controller 返回一个 `SseEmitter` 对象而不是 `Result`，Spring 会把它的 `send()` 调用包装成 SSE 格式（`data: {...}\n\n`）推给客户端。
- **`CompletableFuture.runAsync` 是关键**：如果 Controller 方法里同步执行 `streamChat`，Tomcat 工作线程会被占用整个流式期间（最长 5 分钟）。`runAsync` 把耗时逻辑丢到 `ForkJoinPool.commonPool()`，Controller 立即返回 emitter，Tomcat 线程得以释放去处理别的请求。
- **`300_000L` = 5 分钟**：`SseEmitter` 超时会抛 `AsyncRequestTimeoutException`，连接被强制断开。如果模型生成超过 5 分钟（长文本 + 慢模型），流会被截断。

`FlashChatRequest` 结构很简单，两个字段：

```java
@Data
public class FlashChatRequest {
    @NotBlank(message = "消息不能为空")
    private String message;    // 用户输入
    private String sessionId;  // 会话 ID（前端生成，用于短期记忆；可空）
}
```

### 面试官深挖

**Q: `CompletableFuture.runAsync` 用的是哪个线程池？有什么风险？**

默认用 `ForkJoinPool.commonPool()`，它的线程数 = CPU 核数 - 1。如果并发流式请求很多，commonPool 会打满，其他依赖 commonPool 的并行流（`parallelStream`）也会被拖慢。生产环境应该注入自定义线程池（`@Bean("flashExecutor")`），限制并发数和队列大小。

**Q: SseEmitter 超时 5 分钟够吗？**

对穿搭建议这类短回答够。但用户可能连续追问，如果每条都接近 5 分钟，连接会在中途断开。更稳健的做法是：前端 `onerror` 时自动重连，或在 Java 侧用 `SseEmitter.event().reconnectTime()` 提示客户端重连策略。

**Q: 为什么不用 `@RequestParam` 而用 `@RequestBody`？**

因为聊天消息是 JSON（含 message + sessionId 两个字段），`@RequestBody` 直接反序列化成 `FlashChatRequest`。这和图片上传接口（`@RequestParam("file")` 收 multipart）形成对比——一个有文件二进制，一个是纯 JSON。

---

## 步骤 3：Java Service — 转发并透传 Python 的 SSE 流

这是 Java 侧的核心：**Java 自己不调大模型，而是做「转发 + 透传」**，把 Python 吐出来的 SSE 文本逐条解析成 `delta`，再用自己的 `SseEmitter` 转推给前端。

**代码文件**：`loomai-main/src/main/java/com/lsq/service/impl/FlashServiceImpl.java`

```java
@Service
@Slf4j
public class FlashServiceImpl implements FlashService {

    private static final String CHAT_PATH = "/api/chat";

    public void streamChat(SseEmitter emitter, Long userId, String sessionId, String message) {
        try {
            // 1. 查长期画像
            UserProfile profile = userProfileService.getByUserId(userId);

            // 2. 组装发给 Python 的请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", userId);
            body.put("sessionId", sessionId);
            body.put("message", message);
            Map<String, Object> profileMap = new LinkedHashMap<>();
            profileMap.put("style", profile == null ? "" : profile.getStyle());
            profileMap.put("color", profile == null ? "" : profile.getColor());
            profileMap.put("bodyType", profile == null ? "" : profile.getBodyType());
            body.put("profile", profileMap);

            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + CHAT_PATH))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            // 3. 读 Python 的 SSE 流并透传给前端
            HttpResponse<Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                emitter.send(Map.of("error", "Python 服务异常（状态码 " + response.statusCode() + "）"));
                emitter.complete();
                return;
            }

            try (Stream<String> lines = response.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line == null || !line.startsWith("data:")) continue;
                    String payload = line.substring("data:".length()).trim();
                    if (payload.isEmpty()) continue;
                    if ("[DONE]".equals(payload)) break;          // 结束标记
                    JsonNode node = objectMapper.readTree(payload);
                    String delta = node.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        // 用 Map 让 Spring 序列化为 JSON，前端解析后拼接
                        emitter.send(Map.of("delta", delta));
                    }
                }
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("Flash 对话转发失败", e);
            try { emitter.send(Map.of("error", e.getMessage() == null ? "对话失败" : e.getMessage())); } catch (Exception ignored) {}
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }
}
```

关键设计点：

- **JDK 17 内置 `HttpClient` 取代 RestTemplate**：`BodyHandlers.ofLines()` 是关键——它把响应体当成**行流**（`Stream<String>`）返回，每收到一行（以换行符分隔）就流式吐出一行，而不是等整个响应读完。这样 Java 才能「边收边转」，而不是一次性等 Python 生成完再转发。
- **三层协议对齐**：Python 吐 `data: {"delta":"你"}` → Java 解析出 `delta` → 重新 `emitter.send(Map.of("delta", delta))` → Spring 再序列化成 SSE 发给前端。每一层都重新打包一次，格式保持一致。
- **`[DONE]` 结束标记**：Python 生成完会发一条 `data: [DONE]`，Java 读到就 break，然后 `emitter.complete()` 关闭连接。前端 `reader.read()` 得到 `done: true`，循环结束。
- **`emitter.send(Map.of(...))` 用 Map 而非字符串**：Spring 会把 Map 自动序列化成 JSON（`{"delta":"..."}`），并处理换行转义。如果直接 send 字符串，换行符会破坏 SSE 格式。

### 面试官深挖

**Q: `HttpResponse.BodyHandlers.ofLines()` 和 `ofString()` 有什么区别？**

`ofString()` 会把整个响应体读成一个 String 才返回，是「一次性」的。`ofLines()` 返回 `Stream<String>`，底层按行切分并惰性消费，配合 `Iterator` 可以「来一行处理一行」。流式转发场景必须用 `ofLines()`，否则 Java 会卡到 Python 全部生成完才继续。

**Q: Java 转发层存在性能问题吗？**

每个 token 都要经历「Python 序列化 JSON → 网络传输 → Java 反序列化 → 重新序列化 → 网络传输 → 前端反序列化」，有多次无意义的序列化开销。优化方向：Java 直接把 Python 的原始 SSE 文本字节流透传（不解析、不重打包），前端解析一次即可。当前「解析再重发」是为了统一格式、过滤错误字段。

**Q: 如果 Python 生成到一半挂了，Java 怎么感知？**

`httpClient.send` 期间抛 `IOException`（连接断开），进入 catch 分支，Java 给前端发一条 error 事件并 complete。前端 `handleSseEvent` 里会把 error 追加到气泡。但已经发出的 token 不会回滚，用户会看到「半句回答 + 出错提示」。

---

## 步骤 4：Python 流式生成 — 绕过 ChatTongyi 的坑

这是整条链路**最有技术含量**的一环，也是本项目踩过的最深的坑。

**代码文件**：`loomai-agent/server.py`

```python
@app.route("/api/chat", methods=["POST"])
def chat_api():
    from flask import Response, stream_with_context
    from rag import retrieve
    from memory import ChatMemoryStore

    data = request.get_json(silent=True) or {}
    message = data.get("message", "")
    session_id = data.get("sessionId", "") or str(uuid.uuid4())
    profile = data.get("profile") or {}

    if not message:
        return jsonify({"success": False, "error": "message 不能为空"}), 400

    # ... 短期记忆 / RAG / 画像 见后续章节，最终拼出 system_content ...

    def generate():
        ai_parts = []
        try:
            dashscope_messages = [
                {"role": "system", "content": system_content},
                {"role": "user", "content": message},
            ]
            responses = Generation.call(
                model="qwen-plus",
                messages=dashscope_messages,
                result_format="message",
                temperature=0.1,
                stream=True,
                incremental_output=True,   # 关键：返回增量 token
                api_key=API_KEY,
            )
            for resp in responses:
                choices = resp.get("output", {}).get("choices") or []
                if not choices:
                    continue
                token = choices[0].get("message", {}).get("content", "") or ""
                if token:
                    ai_parts.append(token)
                    yield f"data: {json.dumps({'delta': token}, ensure_ascii=False)}\n\n"
            ai_text = "".join(ai_parts)
            try:
                memory_store.save_turn(session_id, memory, message, ai_text)
            except Exception as e:
                print(f"保存短期记忆失败: {e}")
            yield "data: [DONE]\n\n"
        except Exception as e:
            print(f"Flash 流式生成异常: {e}")
            yield f"data: {json.dumps({'error': str(e)}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

    return Response(stream_with_context(generate()), mimetype="text/event-stream")
```

### 4.1 为什么必须绕过 `ChatTongyi.stream()`？

最初实现用的是 LangChain 的 `llm.stream()`（`ChatTongyi`），但**前端始终一次性收到整段回答**，没有打字机效果。排查后发现根因不在前端/Java 的缓冲，而在 Python：

`langchain-community` 的 `ChatTongyi._stream()` 在把 DashScope 的增量响应转成 LangChain 的 `ChatMessageChunk` 时，调用了 `convert_dict_to_message(is_chunk=True)`，这个转换过程**把增量 token 弄丢了**——每个 chunk 的 `.content` 都是空字符串，最后只 yield 一个「整段文本」的大 chunk。

解决办法是**绕过 LangChain，直接调 DashScope 底层 SDK**：

```python
Generation.call(
    model="qwen-plus",
    messages=dashscope_messages,
    result_format="message",
    stream=True,
    incremental_output=True,   # 每个响应只含「新增的一个 token」，而非累积全文
)
```

`incremental_output=True` 让 DashScope 每次只返回增量 token，而不是从头累积的完整文本。逐个 token `yield` 出去，就形成了打字机效果。

**对照实验数据**（排查时验证）：`ChatTongyi.stream()` 只 yield 1 个整段 chunk；直接 `Generation.call(incremental_output=True)` yield 15+ 个增量 token。

### 4.2 `stream_with_context` 的作用

Flask 默认在请求上下文销毁后就无法访问 `request` 等对象。`stream_with_context(generate())` 让生成器在「流式返回期间」仍能持有请求上下文，避免生成器里访问 `request` 时报 `RuntimeError: Working outside of request context`。本项目 `generate()` 里虽然没直接用 `request`，但它调用的 `memory_store`、`retrieve` 等闭包变量都依赖请求上下文存活，所以必须包一层。

### 面试官深挖

**Q: SSE 响应为什么要设 `mimetype="text/event-stream"`？**

这是 SSE 协议规范要求的 Content-Type。浏览器（和 Java 的 `ofLines()`）根据它识别流式响应。如果设成 `application/json` 或 `text/plain`，`EventSource` 会拒绝连接（fetch 倒是不挑，但前端按 SSE 解析仍能工作）。规范里还建议配 `Cache-Control: no-cache` 和 `Connection: keep-alive`。

**Q: `ai_parts` 为什么要手动 `"".join()` 拼全文？**

因为 `incremental_output=True` 每次只返回一个增量 token，散落在多个响应里。而短期记忆保存需要「这一轮 AI 的完整回答」作为 `ai_msg`，所以要把所有 token 拼起来。这其实暴露了流式 + 记忆的一个矛盾：**记忆要完整文本，流式却只有增量**，只能边流边攒。

**Q: 流式生成时保存记忆，会不会阻塞后续 token？**

当前实现是在**生成完所有 token 之后**才 `save_turn`，所以不会阻塞流。但代价是：如果生成中途进程 crash 或网络断开，`save_turn` 没执行，这一轮对话就丢了（记忆没更新）。这是「先流后存」的取舍。

**Q: `temperature=0.1` 对流式对话合适吗？**

对话场景 0.1 偏保守，回答会比较「模板化」。穿搭建议需要一定的创造性，0.3~0.7 更合适。但 temperature 越低，输出越稳定、越可复现，对演示和测试友好。这里取 0.1 是偏稳妥的选择。

---

# 第二部分：RAG 检索增强（知识库 → 向量库 → prompt）

Flash 的「专业感」来自 RAG：它不只是让大模型「凭空」回答，而是先从**服装搭配知识库**里检索最相关的片段，注入 prompt，让回答有据可依。

## 为什么需要 RAG？

大模型（qwen-plus）本身有通用穿搭知识，但：

- **知识可能过时或太泛**，缺少本项目特定的搭配规则（色彩、风格、体型、场合）。
- **无法溯源**：纯生成式回答「看起来对」，但不知道依据是什么。
- **易幻觉**：没有外部知识约束时，模型可能编造不存在的搭配理论。

RAG（Retrieval-Augmented Generation，检索增强生成）在生成前先「查资料」，把查到的资料塞进 prompt，既约束了回答方向，又让回答更专业、可解释。

## 步骤 5：知识库与向量化

**代码文件**：`loomai-agent/rag.py`

知识库是 `knowledge_base/` 目录下的 4 篇 Markdown：

```
knowledge_base/
├── 色彩搭配.md
├── 风格指南.md
├── 体型穿搭.md
└── 场合穿搭.md
```

`rag.py` 负责把这几篇文档「切块 → 向量化 → 存入 ChromaDB」：

```python
class DashScopeEmbeddingFunction(chromadb.EmbeddingFunction):
    """把文本转成向量，供 ChromaDB 调用。
    继承 chromadb.EmbeddingFunction：query 路径会调用 embed_query()，
    基类默认将其委托给 __call__()，因此只需实现 __call__ 即可同时支持入库与检索。
    """
    def __init__(self, model: str = "text-embedding-v3"):
        self.model = model

    def name(self) -> str:
        return "dashscope-text-embedding-v3"

    def __call__(self, input):
        if isinstance(input, str):
            input = [input]
        embeddings = []
        for text in input:
            resp = TextEmbedding.call(model=self.model, input=text, api_key=API_KEY)
            if resp.status_code == 200:
                embeddings.append(resp.output["embeddings"][0]["embedding"])
            else:
                embeddings.append([0.0] * 1024)   # 兜底：零向量，避免单条失败拖垮入库
        return embeddings


def get_collection():
    client = chromadb.PersistentClient(path=CHROMA_DIR)
    return client.get_or_create_collection(
        name=COLLECTION_NAME,                       # "fashion_knowledge"
        embedding_function=DashScopeEmbeddingFunction(),
        metadata={"hnsw:space": "cosine"},          # 余弦距离
    )


def _split_long_chunk(text: str, max_chars: int = 512):
    """过长 chunk 按空行二次切分；仍过长则硬切到 max_chars。"""
    if len(text) <= max_chars:
        return [text]
    parts = [p.strip() for p in text.split("\n\n") if p.strip()]
    if len(parts) > 1:
        return parts
    return [text[i:i + max_chars] for i in range(0, len(text), max_chars)]


def _split_markdown(content: str, fname: str):
    """按 Markdown 标题层级切分：解析 # 为 parent_category、## 为 category，
    以 ## 为边界切 chunk（标题 + 正文一起保留），过长再二次切分。
    返回 [(chunk_text, metadata_dict), ...]。
    """
    lines = content.split("\n")
    parent_category = ""
    results = []
    current_category = ""
    buf = []

    def flush():
        if not current_category or not buf:
            return
        text = "\n".join(buf).strip()
        if not text:
            return
        meta = {
            "document": fname,
            "parent_category": parent_category,
            "category": current_category,
        }
        for sub in _split_long_chunk(text):
            results.append((sub, meta))

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("## "):
            flush()
            current_category = stripped[3:].strip()
            buf = [stripped]
        elif stripped.startswith("# "):
            parent_category = stripped[2:].strip()
        elif current_category and stripped:
            buf.append(stripped)

    flush()
    return results


def ingest_structured() -> int:
    """按 Markdown 标题层级切分 + 生成 metadata 后重新入库，返回入库片段数。"""
    collection = get_collection()
    # 清空旧数据，避免新旧 chunk 混合
    existing = collection.get()
    if existing.get("ids"):
        collection.delete(ids=existing["ids"])
    documents, ids, metadatas = [], [], []
    for fname in sorted(os.listdir(KNOWLEDGE_DIR)):
        if not fname.endswith(".md"):
            continue
        path = os.path.join(KNOWLEDGE_DIR, fname)
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        for i, (chunk, meta) in enumerate(_split_markdown(content, fname)):
            documents.append(chunk)
            ids.append(f"{fname}-{i}")
            metadatas.append(meta)
    if documents:
        collection.upsert(documents=documents, ids=ids, metadatas=metadatas)
    return len(documents)
```

关键设计点：

- **`EmbeddingFunction` 继承是接入 ChromaDB 的正确姿势**：ChromaDB 要求 embedding 函数实现 `__call__(input: list[str]) -> list[list[float]]`。`chromadb.EmbeddingFunction` 基类把 `embed_query()` 委托给 `__call__()`，所以只实现 `__call__` 就能同时支持入库（`upsert`）和检索（`query`）两条路径。
- **`text-embedding-v3` 输出 1024 维向量**：DashScope 的通用文本向量模型，中文效果好。维度越高表达能力越强，但存储和计算开销也越大。
- **`metadata={"hnsw:space": "cosine"}` 指定余弦距离**：穿搭知识检索关心「语义方向」而非「绝对距离」，余弦相似度比欧氏距离更适合文本语义。
- **`PersistentClient` 持久化到磁盘**：`chroma_db/` 目录，重启不丢。首次使用需执行 `ingest_structured()` 灌库。
- **按 Markdown 标题层级结构化切分**：`_split_markdown` 解析 `#`（一级标题）为 `parent_category`、`##`（二级标题）为 `category`，以 `##` 为边界切 chunk（标题 + 正文一起保留），并为每条 chunk 打上 metadata `{document, parent_category, category}`。这样每个 chunk 都「自带章节归属」，既保证语义连续，又支持按类别溯源/过滤。超过 512 字符的 chunk 再由 `_split_long_chunk` 二次切分。

### 面试官深挖

**Q: 为什么用 ChromaDB 而不是 FAISS / Milvus / Pinecone？**

- **ChromaDB** 是嵌入式向量库，Python 一行启动、自带持久化、自带 embedding function 抽象，适合本项目这种「本地单机、知识库几百条」的规模。
- **FAISS** 是纯算法库（无持久化、无元数据管理），要自己搭存储和 CRUD。
- **Milvus / Pinecone** 是分布式向量数据库，能力最强，但需要单独部署、运维成本高，对一个小知识库是「大炮打蚊子」。

本项目知识库只有 4 篇文档、几十个片段，ChromaDB 完全够用，且与 LangChain 生态集成最好。

**Q: 为什么按 Markdown 标题层级切分，而不是固定 token 长度？**

固定 token 长度切块（如 512 token + 重叠 64）是通用方案，但会**无视文档结构**——可能在句子中间、甚至在标题里硬切。本项目知识库本身是结构化 Markdown（`#` 一级标题 / `##` 二级标题），按标题切分能天然对齐「章节」边界：一个 `##` 小节就是一个语义完整的 chunk，标题 + 正文一起保留，检索到的片段自带「这是哪一章哪一节」的 context。

代价是粒度不如固定 token 可控（某个章节可能特别长），所以用 `_split_long_chunk` 对超过 512 字符的长 chunk 做二次切分兜底。相比最早期按 `\n\n` 空行切分（粒度完全依赖段落结构、无章节归属），结构化切分对「穿搭知识」这类层次分明的文档更合适。

**Q: 单条 embedding 失败返回零向量，会不会污染检索？**

零向量在余弦空间里和任何向量的相似度都接近 0，不会被检索出来，所以不会「污染」Top-K 结果，只是「少了这一条知识」。这是一种降级策略——宁可少检索一条，也不让整个入库流程因单条 API 失败而中断。

---

## 步骤 6：检索并注入 prompt（完整 RAG 管线）

**代码文件**：`loomai-agent/rag.py`

检索不再是一次 `collection.query`，而是一条完整管线，由 `retrieve_advanced()` 编排。先看集中管理的常量与总编排：

```python
# —— 完整 RAG 升级新增常量（集中管理，便于调优） ——
VECTOR_TOP_K = 20                 # 向量召回候选数
BM25_TOP_K = 20                   # BM25 召回候选数
SIMILARITY_THRESHOLD = 0.70       # 余弦相似度阈值
RRF_K = 60                        # RRF 融合常数
RERANK_TOP_K = 5                  # Rerank 后最终返回条数（Top 3~5）
RERANK_MODEL = "BAAI/bge-reranker-base"  # Cross-Encoder 重排模型


def retrieve_advanced(query: str, history: str = "", top_k: int = RERANK_TOP_K):
    """完整 RAG 检索管线：Query 改写 → 混合召回 → 阈值 → RRF → Rerank。"""
    # ① LLM Query Rewrite
    q = rewrite_query(query, history)

    # ② 混合召回（向量 + BM25）
    vector_hits = _vector_retrieve_with_scores(q, top_k=VECTOR_TOP_K)
    bm25_hits = bm25_retrieve(q, top_k=BM25_TOP_K)

    # ④ 相似度阈值（只作用于 Dense 向量结果，在融合前过滤）
    vector_hits = _apply_threshold(vector_hits)

    # ③ RRF Fusion
    fused = _rrf_fusion([vector_hits, bm25_hits])
    if not fused:
        return []  # 没有相关 Context，不强行注入无关知识

    # ⑤ Cross-Encoder Rerank → Top 3~5
    candidates = [text for _, text, _ in fused]
    return rerank(q, candidates, top_n=top_k)
```

在 `server.py` 的 `/api/chat` 里，检索结果被拼进 system prompt：

```python
# 2. RAG 检索相关知识
knowledge_text = ""
try:
    knowledge = retrieve_advanced(message, history, top_k=5)
    knowledge_text = "\n".join(f"- {k}" for k in knowledge if k)
except Exception as e:
    print(f"RAG 检索失败: {e}")   # 检索失败不阻断对话，降级为纯生成
```

### 管线六步拆解

| 步骤 | 函数 | 模型 / 算法 | 关键参数 | 输出 |
|------|------|------------|---------|------|
| ① Query 改写 | `rewrite_query()` | `qwen-plus`（LLM） | `temperature=0.0` | 标准检索 query |
| ② 向量召回 | `_vector_retrieve_with_scores()` | `text-embedding-v3` | `top_k=20` | 带相似度的 Top20 |
| ③ BM25 召回 | `bm25_retrieve()` | `jieba` + `BM25Okapi` | `top_k=20` | 关键词 Top20 |
| ④ 相似度阈值 | `_apply_threshold()` | — | `threshold=0.70` | 过滤后的向量候选 |
| ⑤ RRF 融合 | `_rrf_fusion()` | Reciprocal Rank Fusion | `k=60` | 融合后的候选 |
| ⑥ 精排 | `rerank()` | `BAAI/bge-reranker-base` | `top_n=5` | 最终 Top 3~5 片段 |

#### ① Query 改写（LLM Query Rewrite）

```python
def rewrite_query(query: str, history: str = "") -> str:
    """把口语化/依赖上下文的 Query 改写成标准检索 Query，失败时返回原 query。"""
    if not history:
        return query
    try:
        from dashscope import Generation
        prompt = (
            "你是检索查询改写助手。请把用户的问题改写成一句适合向量检索的、"
            "独立且完整的中文查询。要求：只改写查询本身，不回答问题；"
            "保留用户真实意图；保留关键场景和限制条件；去掉无意义口语；"
            "不得凭空添加事实。只输出改写后的查询。\n"
            f"对话历史：{history}\n"
            f"当前问题：{query}\n"
            "改写后的查询："
        )
        resp = Generation.call(
            model="qwen-plus",
            messages=[{"role": "user", "content": prompt}],
            result_format="message",
            temperature=0.0,
            api_key=API_KEY,
        )
        if resp.status_code == 200:
            content = resp.output["choices"][0]["message"]["content"].strip()
            if content:
                return content
    except Exception as e:
        print(f"Query 改写失败，使用原查询: {e}")
    return query
```

- **只在有对话历史时改写**：`if not history: return query` —— 首轮问题通常已是完整句子，无需改写；多轮追问（如「那配什么鞋」）依赖上文，才用摘要补全成语义完整、可独立检索的 query。
- **`temperature=0.0`**：改写是「确定性」任务，温度最低保证输出稳定、可复现。
- **失败降级**：改写失败（API 异常、返回空）直接返回原 query，不影响后续检索。

#### ② 向量召回（Dense，带相似度）

```python
def _vector_retrieve_with_scores(query: str, top_k: int = VECTOR_TOP_K):
    """向量召回：返回 [(id, text, 余弦相似度)]，按相似度降序。"""
    collection = get_collection()
    result = collection.query(
        query_texts=[query], n_results=top_k, include=["documents", "distances"]
    )
    ids = result.get("ids", [[]])[0]
    docs = result.get("documents", [[]])[0]
    dists = result.get("distances", [[]])[0]
    # ChromaDB cosine 空间返回 distance = 1 - similarity，这里转回相似度
    return [(i, t, 1.0 - d) for i, t, d in zip(ids, docs, dists)]
```

- 与 naive RAG 只取 top 3 不同，这里**放宽到 top 20**（`VECTOR_TOP_K=20`），把「可能相关」的候选都捞回来，交给后面的 RRF + Rerank 精挑。
- 关键：`include=["documents", "distances"]` 让 ChromaDB 同时返回**余弦距离**，转成相似度（`1.0 - distance`）供阈值过滤用。

#### ③ BM25 关键词召回（Sparse）

```python
def bm25_retrieve(query: str, top_k: int = BM25_TOP_K):
    """BM25 关键词召回：返回 [(id, text, bm25_score)]，按分数降序。"""
    try:
        import jieba
        from rank_bm25 import BM25Okapi
    except ImportError:
        return []  # 未装依赖则跳过 BM25，退化为纯向量检索
    corpus = _all_chunks()
    if not corpus:
        return []
    tokenized_corpus = [jieba.lcut(text) for _, text in corpus]
    bm25 = BM25Okapi(tokenized_corpus)
    scores = bm25.get_scores(jieba.lcut(query))
    ranked = sorted(range(len(corpus)), key=lambda i: scores[i], reverse=True)[:top_k]
    return [(corpus[i][0], corpus[i][1], float(scores[i])) for i in ranked if scores[i] > 0]
```

- **BM25 是「词法匹配」**，向量是「语义匹配」——两者互补。向量能召回「近义但用词不同」的片段（「西装」↔「正装」），BM25 能精准命中「专有名词/颜色/数字」这类关键词（「莫兰迪色系」「梨形身材」）。
- **`jieba` 分词**后喂给 `BM25Okapi`：BM25 按空格分词，中文必须先用 `jieba.lcut` 分词。
- **未装依赖则返回空**：`ImportError` 兜底，退化为纯向量检索，不 crash。

#### ④ 相似度阈值过滤（只作用于向量）

```python
def _apply_threshold(vector_hits, threshold: float = SIMILARITY_THRESHOLD):
    """丢弃余弦相似度 < threshold 的向量结果，只保留语义足够相关的候选。"""
    return [(i, t, s) for (i, t, s) in vector_hits if s >= threshold]
```

- **阈值 `0.70`**：余弦相似度低于 0.70 的向量候选被丢弃，避免把「语义上其实不太相关」的知识硬塞进 prompt。0.70 是经验初值，需实测调优。
- **只作用于 Dense 向量**：BM25 分数是词法相关度、和余弦相似度不在一个量纲，不能共用同一阈值，所以阈值过滤只在向量结果上做、在 RRF 融合前完成。

#### ⑤ RRF 融合（Reciprocal Rank Fusion）

```python
def _rrf_fusion(ranked_lists, k: int = RRF_K):
    """RRF 融合：对多个召回排序结果按排名加权合并，返回 [(id, text, rrf_score)] 降序。"""
    score = {}
    text_by_id = {}
    for ranked in ranked_lists:
        for rank, item in enumerate(ranked):
            doc_id, text = item[0], item[1]
            score[doc_id] = score.get(doc_id, 0.0) + 1.0 / (k + rank + 1)
            text_by_id[doc_id] = text
    return sorted(
        [(doc_id, text_by_id[doc_id], s) for doc_id, s in score.items()],
        key=lambda x: x[2],
        reverse=True,
    )
```

- **为什么不直接把两路分数相加？** 向量相似度（0~1）和 BM25 分数（无上界的词频加权）量纲完全不同，直接相加会让 BM25 的高分「碾压」向量分。RRF 只关心**排名**（rank）而非原始分数：每个文档按其在各路的排名累加 `1/(k+rank+1)`，`k=60` 是平滑常数，防止排名靠前的文档权重过大。
- 既在向量路又在 BM25 路都排名靠前的文档，RRF 分数最高，会被推到融合结果的顶部。

#### ⑥ Cross-Encoder Rerank（精排）

```python
_reranker = None

def _get_reranker():
    """懒加载本地 bge-reranker-base 模型（首次会下载权重，之后走本地缓存）。"""
    global _reranker
    if _reranker is None:
        from FlagEmbedding import FlagReranker
        import torch
        _reranker = FlagReranker(RERANK_MODEL, use_fp16=torch.cuda.is_available())
    return _reranker


def rerank(query: str, documents, top_n: int = RERANK_TOP_K):
    """用 bge-reranker-base 对候选精排，返回 top_n 文本；失败回退原顺序。"""
    if not documents:
        return []
    if len(documents) <= 1:
        return documents
    try:
        reranker = _get_reranker()
        pairs = [[query, doc] for doc in documents]
        scores = reranker.compute_score(pairs, normalize=True)
        ranked = sorted(zip(documents, scores), key=lambda x: x[1], reverse=True)
        return [doc for doc, _ in ranked[:top_n]]
    except Exception as e:
        print(f"Rerank 失败，回退原顺序: {e}")
        return list(documents)[:top_n]
```

- **Bi-Encoder vs Cross-Encoder**：前面的向量检索（`text-embedding-v3`）是 **Bi-Encoder**，把 query 和文档**各自独立**编码成向量再算余弦，速度快但「query 和文档没有交互」。`bge-reranker-base` 是 **Cross-Encoder**，把 `[query, doc]` 拼成一个 pair 一起过 Transformer，精确建模两者交互，相关性判断更准，但速度慢——所以只对融合后的少量候选做精排，不作用于全库。
- **`compute_score(pairs, normalize=True)`**：输出归一化到 0~1 的相关性分数。
- **`use_fp16=torch.cuda.is_available()`**：有 GPU 用 fp16 加速，无 GPU（CPU-only）用 fp32。
- **懒加载 + 失败回退**：模型首次调用才加载（下载权重），rerank 失败则回退为 RRF 顺序取 top_n，保证对话不断。

关键设计点：

- **「召回 → 粗排 → 精排」三段式**：向量/BM25 召回（宽、快、全）→ RRF 融合（合并两路）→ Cross-Encoder 精排（窄、慢、准）。每一步缩小候选集、提升精度，避免对全库做昂贵的 rerank。
- **Query 改写解决「语义鸿沟」**：多轮追问用摘要补全上下文再改写，缩小口语提问与书面知识之间的 gap。
- **RAG 失败降级为纯生成**：`try/except` 包住 `retrieve_advanced`，检索挂了（embedding API 超时、rerank 模型加载失败）也不影响对话，只是这次回答没有知识增强。这是**可用性优先**的设计。
- **知识以「列表」形式注入**：`可参考的搭配知识：\n- 片段1\n- 片段2\n- 片段3`，用自然语言告诉模型「这些是参考资料」，模型会自动从中提取有用信息。
- **阈值过滤保证「宁缺毋滥」**：相似度低于 0.70 的候选被丢弃，`fused` 为空时直接返回 `[]`，不强行注入无关知识（避免「硬凑」误导模型）。

### 面试官深挖

**Q: 为什么要「混合召回」（向量 + BM25），只用向量不行吗？**

向量（Dense）擅长语义匹配，但有两个盲区：一是**专有名词/数字/颜色**这类「长尾词」，向量可能泛化过头；二是**低频但关键的词**（如「梨形身材」「莫兰迪色系」）。BM25（Sparse）正好补上这块——精确的词法匹配。两者召回结果用 RRF 融合，互补提升召回率。这也是一线 RAG 系统的标配（Hybrid Search）。

**Q: RRF 融合为什么不直接用分数加权？**

向量相似度（0~1）和 BM25 分数（无上界）量纲不同，直接相加会让 BM25 高分项「碾压」向量分。RRF 只看排名、忽略原始分数，天然解决了「不同检索器分数不可比」的问题。`k=60` 是经验平滑常数。

**Q: Cross-Encoder 和 Bi-Encoder 的本质区别是什么？**

Bi-Encoder（`text-embedding-v3`）把 query 和 doc **各自独立**编码成向量，用余弦相似度衡量——两句话「分别看」再比，快但粗糙。Cross-Encoder（`bge-reranker-base`）把 `[query, doc]` **拼成一个输入**一起过模型，Transformer 的 attention 让 query 和 doc 的每个 token 都交互——「一起看」，准但慢。所以工程上「先 Bi-Encoder 召回、后 Cross-Encoder 精排」是标准范式。

**Q: 阈值 0.70 是怎么定的？**

经验初值，需要实测调优。调高（如 0.8）会过滤掉更多「勉强相关」的知识，回答更保守、更依赖模型自身知识；调低（如 0.6）会放进更多候选，可能引入噪声。理想做法是拿一批「问题-正确知识」标注数据，在验证集上扫阈值，找召回率和噪声的平衡点。

**Q: 每一步都可能失败（embedding、改写、rerank），怎么保证对话不中断？**

全链路都做了**降级**：`rewrite_query` 失败返回原 query；`bm25_retrieve` 未装依赖返回空；`rerank` 失败回退 RRF 顺序；最外层 `retrieve_advanced` 用 `try/except` 包住，任何一步挂了都只是「这次没有知识增强」，不影响流式生成。

**Q: RAG 的「幻觉」问题解决了吗？**

部分解决。RAG 降低了幻觉概率（有知识约束），但不能根除——模型仍可能「无视」检索结果、或曲解知识片段。彻底解决需要：检索结果带引用标注、要求模型「只依据给定资料回答，资料没有就明说不知道」、加人工审核。

---

# 第三部分：长短期记忆

记忆是 Flash 区别于「一次性问答机器人」的核心。它有两层记忆，各司其职：

| 维度 | 长期记忆 | 短期记忆 |
|------|---------|---------|
| 存什么 | 用户的风格/颜色/体型偏好 | 当前会话的对话摘要 |
| 存哪里 | MySQL `user_profile` 表 | Redis + LangChain `ConversationSummaryMemory` |
| 生命周期 | 永久（用户手动维护） | 2 小时 TTL |
| 谁写入 | 用户点「偏好设置」 | 每轮对话后自动更新 |
| 作用 | 回答始终贴合个人偏好 | 多轮对话不「失忆」 |

## 步骤 7：长期记忆 — user_profile 画像

长期记忆存的是「这个用户是谁」——ta 的穿搭偏好。由用户在 Flash 页的「偏好设置」弹窗手动填写，落库 MySQL。

**表结构**：`loomai-main/user_profile.sql`

```sql
CREATE TABLE IF NOT EXISTS `user_profile` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `style` VARCHAR(255) DEFAULT NULL COMMENT '风格偏好，如：简约/复古/街头',
    `color` VARCHAR(255) DEFAULT NULL COMMENT '颜色偏好，如：黑白灰/暖色系',
    `body_type` VARCHAR(255) DEFAULT NULL COMMENT '体型，如：偏瘦/微胖/梨形',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_id` (`user_id`)       -- 一个用户一条画像
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

**Java 侧读写**：`UserProfileServiceImpl.java`

```java
@Override
public UserProfile getByUserId(Long userId) {
    LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(UserProfile::getUserId, userId);
    return userProfileMapper.selectOne(wrapper);
}

@Override
@Transactional(rollbackFor = Exception.class)
public void saveOrUpdate(Long userId, String style, String color, String bodyType) {
    UserProfile existing = getByUserId(userId);
    if (existing == null) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setStyle(style);
        profile.setColor(color);
        profile.setBodyType(bodyType);
        userProfileMapper.insert(profile);
    } else {
        existing.setStyle(style);
        existing.setColor(color);
        existing.setBodyType(bodyType);
        userProfileMapper.updateById(existing);
    }
}
```

关键设计点：

- **`UNIQUE KEY uk_user_id`**：一个用户最多一条画像，`saveOrUpdate` 用「先查后写」实现 upsert。并发下存在竞态（两个请求同时查到 null 都 insert），但偏好设置是低频操作，风险可忽略。
- **画像由用户手动维护**，不是自动从对话中抽取。这是「显式长期记忆」——简单可靠，但需要用户主动填写。进阶做法是对话中自动提取（「我喜欢穿黑色」→ 自动更新 color 偏好），但那需要额外的实体抽取 + 用户确认流程。

### 面试官深挖

**Q: 画像为什么不用 JSON 字段直接塞进 users 表，而要单独建表？**

单独建表解耦：画像字段（style/color/body_type）未来可能扩展（身高、预算、忌口颜色），独立表改表结构不影响 users 主表。且画像访问频率和用户认证不同，可以独立缓存、独立索引。缺点是多了 JOIN 或二次查询。

**Q: 画像数据量极小（每用户一条），有必要用 MySQL 吗？存 Redis 不行吗？**

存 Redis 更快，但画像需要**永久保存**，Redis 重启（无持久化/RDB 丢失）或 TTL 过期会丢。MySQL 是可靠持久化。折中方案：MySQL 持久化 + Redis 缓存（读画像先查 Redis，miss 再查 MySQL 回填），兼顾性能和可靠性。

**Q: `saveOrUpdate` 的「先查后写」在并发下会怎样？**

两个请求同时 `getByUserId` 都返回 null → 都执行 insert → 第二个 insert 因 `uk_user_id` 唯一键冲突报错。虽然概率低，但严谨做法是依赖数据库唯一键 + `INSERT ... ON DUPLICATE KEY UPDATE`（MyBatis-Plus 的 `saveOrUpdate` 有类似能力），或在应用层加分布式锁。

---

## 步骤 8：短期记忆 — ConversationSummaryMemory + Redis

短期记忆解决「多轮对话失忆」问题：用户先说「我下周参加婚礼」，再说「那配什么鞋」，模型需要记得上文提到的「婚礼」这个场景。

**代码文件**：`loomai-agent/memory.py`

```python
from langchain_classic.memory import ConversationSummaryMemory

SUMMARY_TTL_SECONDS = 2 * 60 * 60   # 会话摘要保留 2 小时

class ChatMemoryStore:
    def __init__(self, redis_client, llm):
        self.redis = redis_client
        self.llm = llm

    @staticmethod
    def _summary_key(session_id: str) -> str:
        return f"chat:session:{session_id}:summary"

    def load_memory(self, session_id: str) -> ConversationSummaryMemory:
        """加载（或新建）某个会话的摘要记忆。"""
        memory = ConversationSummaryMemory(llm=self.llm, memory_key="chat_history")
        saved = self.redis.get(self._summary_key(session_id))
        if saved:
            try:
                memory.buffer = saved      # 把上次的摘要装回 buffer
            except Exception:
                pass
        return memory

    def get_history_text(self, memory) -> str:
        """取出当前摘要，作为 prompt 里的历史上下文。"""
        variables = memory.load_memory_variables({})
        return variables.get("chat_history", "")

    def save_turn(self, session_id: str, memory, user_msg: str, ai_msg: str):
        """保存本轮问答，更新摘要并落 Redis。"""
        memory.save_context({"input": user_msg}, {"output": ai_msg})
        summary = getattr(memory, "buffer", "")
        if summary:
            self.redis.set(self._summary_key(session_id), summary, ex=SUMMARY_TTL_SECONDS)
```

在 `server.py` 里，记忆的加载和注入：

```python
# 1. 短期记忆
memory_store = ChatMemoryStore(_redis_client, llm)
memory = memory_store.load_memory(session_id)
history = memory_store.get_history_text(memory)

# ... 组装 prompt 时 ...
if history:
    context_parts.append(f"之前的对话摘要：{history}")
```

关键设计点：

- **「摘要式」记忆而非「窗口式」记忆**：`ConversationSummaryMemory` 每轮对话后用 LLM 把历史**压缩成一段摘要**（`memory.buffer`），而不是保留原始对话全文。好处是上下文不随轮数线性膨胀——聊 100 轮，注入 prompt 的始终是一段固定长度的摘要。
- **Redis 持久化摘要**：`memory.buffer` 存到 Redis（key = `chat:session:{sessionId}:summary`），TTL 2 小时。这样即使 Python 进程重启，摘要还在；跨请求也能按 sessionId 找回记忆。
- **sessionId 是记忆的「钥匙」**：同一个 sessionId 的所有轮次共享一份摘要。前端「新对话」按钮会重新生成 sessionId，等于「换了个新脑子」，清空短期记忆。

### 面试官深挖

**Q: 为什么用「摘要式」记忆而不是「窗口式」（保留最近 N 轮原文）？**

窗口式记忆保留最近 N 轮**原始对话**，信息无损但 token 消耗大（每轮都带全部历史），且超过窗口就彻底丢失早期信息。摘要式记忆把历史压成固定长度摘要，token 消耗恒定，但**摘要过程会丢失细节**（模型「忘记」了用户说的具体数字、具体颜色）。本项目选摘要式，是「上下文长度可控」和「信息保真」之间的折中。

**Q: 摘要记忆的代价是什么？**

**每次对话都要额外调一次 LLM 做摘要**（`memory.save_context` 内部会触发 summarize）。也就是说一轮对话实际调了两次模型：一次生成回答、一次更新摘要。这增加了延迟和 API 成本。对于短对话可能「不划算」，但换来了长对话的可扩展性。

**Q: `save_turn` 在流式生成之后才执行，会有什么问题？**

见「步骤 4」的深挖——生成完成前如果崩溃，本轮对话不进摘要。此外，`save_turn` 里的 LLM 摘要是**同步阻塞**的，会拖慢 `[DONE]` 之后的收尾。不过此时所有 token 已经发完，前端已经拿到完整回答，摘要延迟用户无感知。

**Q: TTL 2 小时意味着什么？用户 2 小时后回来就「失忆」了？**

是的。短期记忆是「会话级」的，2 小时没互动摘要过期。这是有意设计——短期记忆本就该短。跨会话、跨天的「你是谁」由长期记忆（user_profile）负责。两者的职责边界清晰：**短期记「聊了什么」，长期记「你是谁」。**

**Q: Redis 里摘要的 key 用 sessionId，如果用户同时开两个浏览器 tab 会怎样？**

前端同一用户（同一 userId）共享同一个 localStorage，所以 sessionId 相同，两个 tab 会**串记忆**（一个 tab 的对话影响另一个 tab 的摘要）。不过「新对话」会生成新 sessionId，两个 tab 可以各自独立。

---

# 第四部分：完整 prompt 组装与生成

把前三部分串起来，`/api/chat` 最终拼出的 system prompt 长这样：

```
你是「Flash」，一位专业的服装搭配助手。请用自然亲切的语气，给出具体、可执行的穿搭建议。只做文字交流，不涉及图片。回答简洁，避免过度展开。

用户偏好：风格=简约，颜色=黑白灰，体型=偏瘦。请尽量结合这些偏好给出建议。

可参考的搭配知识：
- 黑白灰是经典的中性色搭配，适合大多数正式与休闲场合……
- 偏瘦体型可选择叠穿、宽松版型增加视觉分量……

之前的对话摘要：
用户下周要参加朋友婚礼，正在找一套适合婚礼的穿搭，预算中等。
```

三块内容（画像 + 知识 + 记忆）按固定顺序拼接，然后和用户当前消息一起发给 `qwen-plus`。模型同时「看到」了「用户是谁」「该给什么建议」「之前聊了什么」，回答自然专业且连贯。

### 面试官深挖

**Q: 画像、知识、记忆三者都在 system prompt 里，会不会互相冲突？**

可能。比如用户偏好「黑白灰」，但知识库里说「婚礼应穿喜庆颜色」，摘要里用户又说「想穿得亮一点」——三者信号矛盾时，模型可能不知所措。这是**多源上下文冲突**问题。进阶做法：给三块内容标注优先级（如「用户当前明确要求 > 长期偏好 > 通用知识」），或在 prompt 里明确冲突时的裁决规则。

**Q: system prompt 会越来越长吗？会不会超模型上下文窗口？**

短期记忆是摘要式（固定长度），长期画像固定 3 字段，RAG 最终固定注入 Top 3~5（`RERANK_TOP_K=5`）片段，所以 system prompt 长度是**有上界的**，不会随对话轮数无限增长。这正是「摘要式记忆 + 固定 Rerank Top-K」设计的好处。但如果知识片段很长、摘要很长，叠加起来仍可能接近 qwen-plus 的上下文上限（8K~32K token），需要监控并截断。

---

# 第五部分：可靠性问题汇总

## 问题 1：SSE 三层透传，链路长、故障点多

前端 → Java → Python 任意一层断了都会中断流。Java 的 `HttpClient` 连接超时 10s、请求超时 5min，`SseEmitter` 也是 5min，但**没有自动重连和断点续传**。

**改进**：前端 `onerror` 时携带 `Last-Event-ID` 重连；Java 侧对 Python 的瞬时故障做重试。

## 问题 2：短期记忆「先流后存」，生成中途崩溃丢记忆

`save_turn` 在所有 token 生成完之后才执行。如果进程在生成中途被杀，本轮对话既没输出完整回答、也没更新摘要。

**改进**：流式生成期间同步攒 `ai_parts`，每攒满一定长度就落盘（或写入 Redis 的临时 buffer），崩溃后下次能部分恢复。

## 问题 3：摘要记忆丢失细节

LLM 摘要会「压缩掉」用户的具体信息（数字、颜色、日期），导致后续回答失真。用户说「我 175cm 80kg」，摘要可能只写「用户偏壮」。

**改进**：摘要式 + 窗口式混合——关键实体（身高体重、具体颜色）用结构化字段单独存，对话流水用摘要压缩。

## 问题 4：RAG 检索无相似度阈值，可能「硬凑」【已解决】

~~`retrieve()` 无论相似度多低都返回 top_k 条，可能把无关知识塞进 prompt，误导模型。~~

**已解决**：升级后的管线加入 `_apply_threshold()`，`SIMILARITY_THRESHOLD=0.70` 丢弃余弦相似度低于阈值的向量候选；`retrieve_advanced` 里 RRF 融合结果为空时直接返回 `[]`，不强行注入无关知识（宁缺毋滥）。

## 问题 5：知识库切块过于简单【已解决】

~~按 `\n\n` 切块，粒度不可控、无重叠，语义在段落边界割裂。~~

**已解决**：改用 `_split_markdown` 按 Markdown 标题层级结构化切分（`#` → `parent_category`、`##` → `category`），每条 chunk 打上 metadata `{document, parent_category, category}`；超 512 字符的长 chunk 由 `_split_long_chunk` 二次切分兜底。

## 问题 6：长期画像「先查后写」有并发竞态

`saveOrUpdate` 的 check-then-act 在并发下可能触发唯一键冲突。

**改进**：数据库 `INSERT ... ON DUPLICATE KEY UPDATE`，或应用层加锁。

## 问题 7：前端 localStorage 记忆不跨设备、不落库

聊天记录和 sessionId 存在浏览器 localStorage（`flash_chat_{userId}`），换设备、清缓存就丢。这不是「服务端记忆」，只是「客户端显示缓存」。

**改进**：真正的会话历史应落库（MySQL 或 Redis），前端只做展示。本项目是「学生演示项目」的轻量取舍。

---

# 完整链路文件索引

| 步骤 | 代码文件 | 核心方法 |
|------|---------|---------|
| 前端发起 SSE 请求 | `loomai-fronted/src/services/api.js` | `flashChat()` |
| 前端解析流 + 渲染 | `loomai-fronted/src/views/FlashView.vue` | `handleSend()`, `handleSseEvent()` |
| 前端本地缓存 | `loomai-fronted/src/views/FlashView.vue` | `loadFlashChat()`, `persistFlashChat()`, `flashStorageKey()` |
| 前端偏好设置 | `loomai-fronted/src/views/FlashView.vue` | `openProfileDialog()`, `handleSaveProfile()` |
| Java SSE 入口 | `controller/FlashController.java` | `chat()`, `getProfile()`, `saveProfile()` |
| Java 转发 + 透传 | `service/impl/FlashServiceImpl.java` | `streamChat()` |
| Java 画像接口 | `service/FlashService.java`, `service/UserProfileService.java` | `streamChat()`, `getByUserId()`, `saveOrUpdate()` |
| Java 画像实现 | `service/impl/UserProfileServiceImpl.java` | `getByUserId()`, `saveOrUpdate()` |
| 画像实体 | `entity/UserProfile.java` | 字段：userId, style, color, bodyType |
| 画像 Mapper | `mapper/UserProfileMapper.java` | `extends BaseMapper<UserProfile>` |
| 请求 DTO | `dto/request/FlashChatRequest.java`, `FlashProfileRequest.java` | message/sessionId；style/color/bodyType |
| 画像表结构 | `loomai-main/user_profile.sql` | `user_profile` 表 |
| Python 对话入口 | `loomai-agent/server.py` | `chat_api()`, `generate()` |
| RAG 检索 | `loomai-agent/rag.py` | `ingest_structured()`, `retrieve_advanced()`, `rewrite_query()`, `_vector_retrieve_with_scores()`, `bm25_retrieve()`, `_rrf_fusion()`, `_apply_threshold()`, `rerank()`, `get_collection()`, `DashScopeEmbeddingFunction` |
| 短期记忆 | `loomai-agent/memory.py` | `ChatMemoryStore.load_memory()`, `get_history_text()`, `save_turn()` |
| Python 服务地址 | `config/PythonAiServiceProperties.java` | `baseUrl`（默认 `http://127.0.0.1:5000`） |
| 知识库 | `loomai-agent/knowledge_base/*.md` | 色彩/风格/体型/场合 4 篇 |
