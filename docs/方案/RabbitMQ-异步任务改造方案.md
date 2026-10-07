# RabbitMQ 异步任务改造方案

## 一、背景与目标

当前项目的图片编辑和图片合并接口是**同步阻塞**的：

```
前端 → Controller → Service → HTTP 调 Python → 等 30s+ → 返回结果
```

用户在浏览器里干等几十秒，体验差；且接口超时风险高（前端已设 300s timeout）。

改造目标：**引入 RabbitMQ 消息队列，将编辑/合并改为异步任务模式**，同时 **100% 保留原有同步接口**，不影响现有功能。

---

## 二、改造后架构

```
┌──────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│  前端     │────→│  Controller  │────→│  RabbitMQ    │────→│  Consumer    │
│ EditView │     │ /api/task/   │     │  task.queue  │     │ @RabbitListener│
│ MergeView│     │ submit-edit  │     │              │     │              │
└────┬─────┘     └──────┬───────┘     └─────────────┘     └──────┬───────┘
     │                  │                                        │
     │   ① 提交任务      │   ② 发消息到队列                        │  ③ 消费消息
     │   立即返回 taskId │                                        │  调用 Python AI
     │                  │                                        │  结果存 Redis
     │   ┌──────────────▼──────────────────────────────────────┐ │
     │   │                 Redis                                │ │
     │   │  key: task:{taskId}                                 │←┘
     │   │  value: {status, resultUrl, saveUrl, ...}           │
     │   │  TTL: 3600s                                         │
     │   └──────────────┬──────────────────────────────────────┘
     │                  │
     │   ④ 轮询 GET /api/task/status/{taskId}
     │   每 2 秒查一次，直到 SUCCESS / FAILED
     │
     └──────────────────┘
```

### 流程对比

| 步骤 | 改造前（同步） | 改造后（异步） |
|---|---|---|
| 提交 | 前端等 30~60s | 前端立即拿到 taskId |
| 处理 | 浏览器 pending | 后台 Consumer 消费 |
| 结果 | HTTP Response 直接返回 | Redis 存结果，前端轮询获取 |
| 失败 | HTTP 报错，请求结束 | Redis 记录错误原因，可重试 |
| 进度 | 无感知 | PENDING → PROCESSING → SUCCESS/FAILED |

---

## 三、文件变更清单

### 3.1 新增文件（6 个）

| 文件 | 包/路径 | 职责 |
|---|---|---|
| `TaskMessage.java` | `com.lsq.dto` | 消息体 DTO：taskId / userId / type / params |
| `TaskSubmitResponse.java` | `com.lsq.dto.response` | 提交任务后返回：taskId / status |
| `TaskStatusResponse.java` | `com.lsq.dto.response` | 查询任务状态返回：taskId / status / resultUrl / saveUrl / error |
| `TaskMessageProducer.java` | `com.lsq.service` | 封装 `RabbitTemplate.convertAndSend()` |
| `TaskMessageConsumer.java` | `com.lsq.service.impl` | `@RabbitListener` 消费消息，调 Python，结果写 Redis |
| `RabbitMqConfig.java` | `com.lsq.config` | Queue / Exchange / Binding 声明 + Jackson 消息序列化 |

### 3.2 修改文件（8 个）

| 文件 | 改动点 |
|---|---|
| `pom.xml` | 新增 `spring-boot-starter-amqp` 依赖 |
| `application.yml` | 新增 RabbitMQ 连接配置 |
| `FileController.java` | 新增 3 个异步接口（见第四节） |
| `FileService.java` | 接口新增 `submitEditAsync` / `submitMergeAsync` 方法签名 |
| `FileServiceImpl.java` | 实现异步方法：鉴权 → 发消息到队列 → 返回 taskId |
| `api.js`（前端） | 新增 `submitEditTask` / `submitMergeTask` / `getTaskStatus` |
| `EditView.vue`（前端） | 提交后改为轮询 taskId，展示进度状态 |
| `MergeView.vue`（前端） | 同上 |

### 3.3 不改动的文件

| 文件 | 原因 |
|---|---|
| `PythonAiIntegrationService.java` 及其 impl | Consumer 直接复用现有方法，无需改动 |
| `RecordService.java` | Consumer 注入后直接调用 `editSave` / `mergeSave`，接口不变 |
| 数据库表 | 任务状态存 Redis，不建新表 |
| Python sidecar | HTTP 接口不变，Consumer 调的方式和现在一样 |
| 原有同步接口 `/api/file/edit`、`/api/file/merge` | **保留不动**，原有前端路径继续可用 |

---

## 四、新增 API 设计

### 4.1 提交编辑任务

```
POST /api/task/submit-edit
Headers: Authorization: Bearer <token>
Content-Type: application/json

Body:
{
  "image": "https://lsq67.oss-cn-beijing.aliyuncs.com/images/xxx.png",
  "instruction": "把人物的上衣换成粉色"
}

Response 200:
{
  "code": 200,
  "message": "任务已提交",
  "data": {
    "taskId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "status": "PENDING"
  }
}
```

### 4.2 提交合并任务

```
POST /api/task/submit-merge
Headers: Authorization: Bearer <token>
Content-Type: application/json

Body:
{
  "image1": "https://lsq67.oss-cn-beijing.aliyuncs.com/images/xxx.png",
  "image2": "https://lsq67.oss-cn-beijing.aliyuncs.com/images/yyy.png",
  "instruction": "给图2的人物换上图1的衣服"
}

Response 200:
{
  "code": 200,
  "message": "任务已提交",
  "data": {
    "taskId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "status": "PENDING"
  }
}
```

### 4.3 查询任务状态

```
GET /api/task/status/{taskId}
Headers: Authorization: Bearer <token>

Response 200 (处理中):
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "taskId": "a1b2c3d4-...",
    "status": "PROCESSING"
  }
}

Response 200 (成功):
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "taskId": "a1b2c3d4-...",
    "status": "SUCCESS",
    "resultUrl": "https://dashscope.aliyuncs.com/...",
    "saveUrl": "https://lsq67.oss-cn-beijing.aliyuncs.com/image/editedxxx.png"
  }
}

Response 200 (失败):
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "taskId": "a1b2c3d4-...",
    "status": "FAILED",
    "errorMessage": "Python 编辑图片接口调用失败"
  }
}
```

---

## 五、消息与数据设计

### 5.1 RabbitMQ 结构

```
Exchange:  loomai.task.exchange
类型:      direct
Queue:     loomai.task.queue
RoutingKey: task.edit  |  task.merge
```

- 持久化：队列和消息均持久化（`durable = true`）
- 确认机制：生产者 `PublisherConfirm`，消费者手动 ACK（处理成功后才确认）

### 5.2 消息体 `TaskMessage`

```json
{
  "taskId": "uuid-字符串",
  "userId": 1001,
  "type": "EDIT",
  "params": {
    "image": "https://oss/xxx.png",
    "instruction": "把上衣换成粉色"
  }
}
```

合并类型时 type 为 `MERGE`，params 中为 `image1`、`image2`、`instruction`。

### 5.3 Redis 任务状态

```
Key:    task:{taskId}
Value:  JSON
TTL:    3600 秒（1 小时后自动清理）
```

```json
{
  "taskId": "uuid",
  "userId": 1001,
  "type": "EDIT",
  "status": "PENDING",
  "resultUrl": null,
  "saveUrl": null,
  "errorMessage": null,
  "createTime": "2026-07-28T15:30:00",
  "completeTime": null
}
```

状态流转：

```
PENDING → PROCESSING → SUCCESS
                    → FAILED
```

---

## 六、Consumer 消费逻辑

```java
@RabbitListener(queues = "loomai.task.queue")
public void handleTask(TaskMessage message) {
    // 1. 更新 Redis 状态为 PROCESSING
    // 2. 根据 message.type 分发：
    //    - EDIT: 构造 EditImageRequest → pythonAiIntegrationService.edit(...)
    //    - MERGE: 构造 MergeImageRequest → pythonAiIntegrationService.merge(...)
    // 3. 成功：Redis 更新为 SUCCESS，写入 resultUrl / saveUrl
    //    调用 recordService.editSave() / mergeSave() 保存操作记录
    // 4. 失败：Redis 更新为 FAILED，写入 errorMessage
    // 5. 手动 ACK 消息
}
```

关键点：
- Consumer 复用现有的 `PythonAiIntegrationService`（它已经封装了"下载 OSS → 调 Python → 回传 OSS"的完整逻辑）
- Consumer 注入 `RecordService`，处理成功后直接保存操作记录
- 异常处理：捕获所有异常，状态标记 FAILED，**不抛出**（防止消息无限重试）

---

## 七、前端改造思路

### 7.1 EditView.vue 改动

```
现在的 submitEdit():
  isEditLoading = true
  await editImage(params)        // 同步等 30s+
  editResult = response.data
  isEditLoading = false

改造后:
  isEditLoading = true
  const { taskId } = await submitEditTask(params)  // 秒返
  startPolling(taskId)           // 每 2s 查一次状态
```

轮询逻辑：
```js
const pollTimer = setInterval(async () => {
  const { status, resultUrl, saveUrl, errorMessage } = await getTaskStatus(taskId)
  if (status === 'PROCESSING') return   // 继续等
  clearInterval(pollTimer)
  isEditLoading = false
  if (status === 'SUCCESS') {
    editResult = { url: resultUrl, saved_oss_url: saveUrl }
  } else {
    editMessage = errorMessage || '处理失败'
  }
}, 2000)
```

### 7.2 改动对比

| 位置 | 改动前 | 改动后 |
|---|---|---|
| 加载状态 | "提交中…" → "AI计算中…" | "任务已提交" → "AI处理中…"（带进度文案） |
| 网络请求 | 1 次 POST，等 30s+ | 1 次 POST 秒返 + N 次 GET 轮询 |
| 超时处理 | 前端 timeout 300s | 轮询最多 120 次（4 分钟），超时提示 |
| 结果展示 | 和现在一样 | 和现在一样（不改变 UI 布局） |

---

## 八、现有功能保护措施

| 措施 | 说明 |
|---|---|
| **旧接口不动** | `/api/file/edit`、`/api/file/merge` 代码一行不改，继续可用 |
| **新路径隔离** | 异步接口使用新路径 `/api/task/*`，与旧接口互不干扰 |
| **数据库不改** | 不新增表，不修改现有表结构 |
| **Python 侧不动** | HTTP 接口无变化，Consumer 和旧代码调同一套接口 |
| **可降级** | 如果 RabbitMQ 挂了，旧同步接口仍然能正常使用 |
| **配置开关** | 可在 `application.yml` 中控制是否启用异步模式 |

---

## 九、实施步骤

| 步骤 | 内容 | 预计时间 |
|---|---|---|
| 1 | `pom.xml` 加 `spring-boot-starter-amqp`，`application.yml` 加 RabbitMQ 配置 | 5 min |
| 2 | 新增 `RabbitMqConfig.java` | 10 min |
| 3 | 新增 DTO：`TaskMessage`、`TaskSubmitResponse`、`TaskStatusResponse` | 10 min |
| 4 | 新增 `TaskMessageProducer.java` | 10 min |
| 5 | 新增 `TaskMessageConsumer.java` | 20 min |
| 6 | 修改 `FileService` / `FileServiceImpl` | 15 min |
| 7 | 修改 `FileController`，新增 3 个接口 | 15 min |
| 8 | 前端 `api.js` 新增 API 函数 | 5 min |
| 9 | 前端 `EditView.vue` 轮询改造 | 20 min |
| 10 | 前端 `MergeView.vue` 轮询改造 | 20 min |
| 11 | 启动 RabbitMQ，联调测试 | 30 min |
| **合计** | | **~2.5 小时** |

---

## 十、前置条件

- 本地安装 RabbitMQ（或 Docker 启动）：`docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management`
- 管理后台：`http://localhost:15672`（guest/guest）

---

## 十一、面试可讲的亮点

1. **异步化改造**：将同步阻塞的 AI 图片处理改为消息队列异步处理，提升用户体验
2. **技术选型**：Spring AMQP + RabbitMQ，消息持久化 + 手动 ACK 保证不丢消息
3. **状态机设计**：PENDING → PROCESSING → SUCCESS/FAILED，状态流转清晰
4. **前后端协作**：前端轮询 + Redis 暂存结果，轻量级实现异步通知
5. **向后兼容**：新增异步接口，零侵入保留原有同步接口，平滑过渡
6. **可扩展**：未来可直接把 Consumer 拆到独立微服务，队列天然支持
