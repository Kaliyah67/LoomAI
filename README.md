# LoomAI —— 智能服装搭配与多模态检索平台

LoomAI（衣览无余）是一款基于 **Java (Spring Boot)** + **Python (Flask AI Agent)** + **Vue 3** 异构架构的智能穿搭服务平台。它把传统的后端业务逻辑与前沿的 AI 能力结合，实现了从图片合规审核、多模态搜索（文搜图 / 图搜图）、AI 图片编辑与合并，到 **实时穿搭对话助手（Flash，含 RAG 与对话记忆）** 的完整闭环。

---

## 目录

- [核心功能](#核心功能)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [环境要求](#环境要求)
- [快速启动](#快速启动)
- [端口与配置说明](#端口与配置说明)
- [数据库说明](#数据库说明)
- [Flash 智能助手（扩展）专项说明](#flash-智能助手扩展专项说明)
- [注意事项](#注意事项)

---

## 核心功能

| 功能 | 说明 |
|------|------|
| 登录 / 注册 | 邮箱验证码登录 + 密码登录，JWT 鉴权，token 存 Redis |
| 图片上传 | 上传图片到 Aliyun OSS，并做内容合规审核（Qwen-VL + 文本模型二次判定） |
| 图片编辑 | 自然语言指令驱动 AI 编辑单张图片（DashScope 图像编辑模型） |
| 图片合并 | 双图合成，AI 按指令融合两张图片 |
| 文搜图 / 图搜图 | 本地 CLIP 提取 512 维特征 + Redis 向量检索，毫秒级多模态搜索 |
| 历史记录 | 查看个人编辑 / 合并的历史记录 |
| **Flash 实时对话** | SSE 流式输出的穿搭顾问，结合 RAG 知识库与用户偏好（长期画像）+ 短期记忆 |

---

## 系统架构

```
┌───────────────┐      ┌────────────────────┐      ┌──────────────────────┐
│   Vue 3 前端   │─────▶│  Java Spring Boot   │─────▶│   Python Flask AI    │
│   (端口 5173)  │      │     (端口 8081)      │      │      (端口 5000)      │
└───────────────┘      └─────────┬──────────┘      └──────────┬───────────┘
                                 │                             │
                    ┌────────────▼────────────┐   ┌────────────▼────────────┐
                    │ MySQL / Redis / RabbitMQ│   │ ChromaDB / Redis / LLM  │
                    │ Aliyun OSS / 邮件       │   │ CLIP / LangChain Agent  │
                    └─────────────────────────┘   └─────────────────────────┘
```

**职责划分：**

- **Java 主控端**：用户认证、JWT 鉴权、Aliyun OSS 存储调度、RabbitMQ 异步任务、核心业务流编排。对 AI 能力只做「转发 + 编排」，不直接调用大模型。
- **Python AI 侧车**：封装 CLIP 向量化、LangChain Agent（`@tool` 图像编辑/合并）、DashScope 大模型调用、ChromaDB 检索、对话记忆。

**Flash 实时对话链路（SSE）：**

```
前端 FlashView ──fetch 流式──▶ Java FlashController ──SseEmitter 透传──▶ Python /api/chat
                                    │ 查 user_profile（长期画像）          │ 短期记忆(Redis) + RAG(ChromaDB)
                                    └───────────────────────────────────▶ 拼画像 + 知识 + 记忆 ─▶ ChatTongyi 流式生成
```

> 前端不用原生 `EventSource`（无法携带 `Authorization` 头），改用 `fetch + ReadableStream` 手动解析 SSE。

---

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue 3、Vite 7、Element Plus、Pinia、Vue Router 4、Axios |
| 后端 | Java 17、Spring Boot 3.2、MyBatis-Plus、Spring Security Crypto、JWT (jjwt)、RabbitMQ、Java Mail |
| AI 侧 | Python 3.13、Flask、LangChain、langchain-community、langchain-classic、DashScope（通义千问）、CLIP（Transformers + Torch）、ChromaDB、BM25（rank_bm25）、bge-reranker（FlagEmbedding）、DeepAgents |
| 存储 | MySQL 8.0、Redis 7、Aliyun OSS |
| 部署 | Docker Compose |

---

## 项目结构

```
LoomAI-Final-new/
├── loomai-main/                     # Java Spring Boot 后端（端口 8081）
│   ├── src/main/java/com/lsq/
│   │   ├── controller/              # FileController / TaskController / RecordController
│   │   │                            # UserController / FlashController（Flash 新增）
│   │   ├── service/                 # 业务接口 + impl 实现
│   │   ├── mapper/                  # MyBatis-Plus Mapper
│   │   ├── entity/                  # 实体（FileInfo / Record / User / UserProfile）
│   │   ├── dto/                     # request / response / python 交互 DTO
│   │   ├── config/                  # 配置类（含 PythonAiServiceProperties）
│   │   ├── common/                  # 通用（Result 响应协议等）
│   │   ├── log/                     # AOP 跨语言 API 日志切面
│   │   └── utill/                   # 工具（JwtUtil 等）
│   ├── src/main/resources/application.yml   # 数据源 / Redis / RabbitMQ / OSS / JWT 配置
│   ├── init.sql                     # 初始建表（files、records）
│   ├── user_profile.sql             # Flash 用户画像表（手动执行）
│   └── pom.xml
│
├── loomai-agent/                    # Python Flask AI 服务（端口 5000）
│   ├── server.py                    # Flask 入口，含全部接口 + /api/chat（SSE）
│   ├── rag.py                       # RAG：Query 改写 + 向量/BM25 混合召回 + RRF + 阈值 + Rerank
│   ├── memory.py                    # 短期记忆：Redis + ConversationSummaryMemory
│   ├── knowledge_base/              # 服装搭配知识库（色彩/风格/体型/场合 4 篇 md）
│   ├── chroma_db/                   # 向量库持久化目录
│   ├── .env                         # DASHSCOPE_API_KEY
│   └── requirements.txt
│
├── loomai-fronted/                  # Vue 3 前端（端口 5173）
│   ├── src/
│   │   ├── views/                   # 页面：Login / Edit / Merge / Images / Records / Flash
│   │   ├── components/              # AppSidebar / AppHeader 等
│   │   ├── router/                  # 路由（含登录守卫）
│   │   ├── services/                # api.js + http.js（axios 封装）
│   │   ├── store/                   # Pinia（auth 登录态）
│   │   └── assets/                  # 图片 / 样式
│   ├── .env.development             # PROXY_TARGET 后端地址
│   ├── vite.config.js               # dev 代理 /api → 后端
│   └── package.json
│
├── docs/                            # Flash 智能助手扩展改造方案（含进度追踪）
├── docker-compose.yml               # MySQL / Redis / RabbitMQ
├── uploads/                         # 本地上传目录
├── imagesDownload/                  # 图片下载目录
└── README.md
```

---

## 环境要求

| 依赖 | 版本 | 备注 |
|------|------|------|
| JDK | 17 | 本地路径示例 `D:\lsq\Java\JDK\jdk-17` |
| Python | 3.13+ | 本项目使用 Anaconda base 环境 |
| Node.js | 18+ | 建议 20+ |
| Maven | 3.8+ | 构建 Java 后端 |
| Docker Desktop | — | 运行 MySQL / Redis / RabbitMQ |

---

## 快速启动

### 1. 启动基础设施（MySQL / Redis / RabbitMQ）

```bash
docker compose up -d
```

首次启动会自动执行 `loomai-main/init.sql`，创建 `files`、`records` 表。

### 2. 配置密钥

编辑 `loomai-agent/.env`，填入 DashScope API Key 与 Redis 端口：

```
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx
REDIS_PORT=6380
```

### 3. 安装依赖

```bash
# Python AI 依赖（本项目使用 Anaconda base 环境）
pip install -r loomai-agent/requirements.txt

# 前端依赖
cd loomai-fronted && npm install
```

> 注意：`requirements.txt` 之外，RAG 与短期记忆还依赖 `chromadb` 与 `langchain-classic`，安装命令见下方「Flash 扩展」一节。

### 4. 启动三个应用服务

开三个终端，分别执行：

**终端 1 —— Java 后端（:8081）**

```bash
export JAVA_HOME=/d/lsq/Java/JDK/jdk-17 && export PATH="$JAVA_HOME/bin:$PATH"
cd loomai-main && mvn spring-boot:run
```

**终端 2 —— Python AI（:5000）**

```bash
cd loomai-agent && python server.py
```

**终端 3 —— 前端（:5173）**

```bash
cd loomai-fronted && npm run dev
```

### 5. 访问

浏览器打开 **http://localhost:5173**，使用邮箱验证码或账号密码登录。

---

## 端口与配置说明

| 服务 | 宿主机端口 | 容器内端口 | 账号 / 说明 |
|------|-----------|-----------|-------------|
| 前端 (Vite) | 5173 | — | dev 代理 `/api` → 8081 |
| Java 后端 | 8081 | — | — |
| Python AI | 5000 | — | — |
| MySQL | 3307 | 3306 | root / `bite@123`，库 `bite_wear` |
| Redis | 6380 | 6379 | 无密码 |
| RabbitMQ | 5672 | 5672 | admin / `admin123`，管理台 15672 |

**配置位置：**

- 后端：`loomai-main/src/main/resources/application.yml`（MySQL / Redis / RabbitMQ / OSS / JWT / 邮件）
- AI 侧：`loomai-agent/.env`（`DASHSCOPE_API_KEY`、`REDIS_PORT=6380`）
- 前端：`loomai-fronted/.env.development`（`PROXY_TARGET=http://127.0.0.1:8081`）

---

## 数据库说明

| 表 | 用途 | 创建方式 |
|----|------|---------|
| `users` | 登录用户 | 已存在（登录功能依赖） |
| `files` | 上传图片记录 | `init.sql`（Docker 首次启动自动建） |
| `records` | 编辑 / 合并请求记录 | `init.sql` |
| `user_profile` | Flash 用户长期画像（风格/颜色/体型） | `user_profile.sql`（**需手动执行**） |

手动创建 `user_profile` 表：

```bash
docker exec -i loomai-mysql mysql -uroot -p'bite@123' bite_wear < loomai-main/user_profile.sql
```

---

## Flash 智能助手（扩展）专项说明

Flash 是本项目在原有图片功能之外新增的**实时穿搭对话助手**，具备三项能力：

1. **实时对话**：SSE 流式输出（打字机效果），纯文字，不涉及图片。
2. **RAG（检索增强生成）**：Query 改写 → 混合召回（ChromaDB 向量 + BM25 关键词）→ RRF 融合 + 相似度阈值 → `bge-reranker-base` 精排，取 Top 3~5 片段注入 prompt；知识来自 `knowledge_base/`（色彩/风格/体型/场合），向量用 DashScope `text-embedding-v3` 生成。
3. **对话记忆**：
   - 短期：Redis + LangChain `ConversationSummaryMemory`，按 sessionId 保存，TTL 2 小时。
   - 长期：MySQL `user_profile` 表，保存用户手动设置的风格/颜色/体型偏好。

**额外依赖（RAG + 记忆）：**

```bash
pip install chromadb langchain-classic jieba rank_bm25 FlagEmbedding
```

**首次使用 Flash 前：**

1. 确认已创建 `user_profile` 表（见上一节）。
2. 启动 Python 服务时，`rag.py` 会检查并初始化 ChromaDB 向量库；首次可执行 `python -c "import rag; rag.ingest_structured()"` 灌入知识库（按 Markdown 标题层级切分并带 metadata）。

**前端入口：** 登录后侧栏「Flash 助手」菜单。

---

## 注意事项

- **启动顺序**：Docker（MySQL/Redis/RabbitMQ）→ Java（8081）→ Python（5000）→ 前端（5173）。
- Python 服务启动时会加载 CLIP / Transformers 模型，首次启动较慢属正常。
- Python 侧流式对话已绕过 `langchain-community` 的 `ChatTongyi.stream()`（该版本存在增量 token 丢失的 bug），改为直接调用 `dashscope.Generation.call(stream=True, incremental_output=True)`。
- 聊天记录按登录用户 `userId` 保存在浏览器 localStorage 中（切页面 / 刷新不丢），非服务端落库。
- 各服务端口与账号密码均为本地开发默认值，生产环境请按需修改。
