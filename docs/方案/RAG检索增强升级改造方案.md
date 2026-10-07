# RAG 检索增强升级改造方案

> 目标：在**不破坏现有 Flash 对话功能**的前提下，把当前最朴素的 RAG（向量检索 top-3 → 拼 prompt）升级为一条**完整、可落地、能指导后续开发的检索增强链路**：
>
> ```
> 用户 Query
>    ↓
> ① LLM Query Rewrite
>    ↓
> ② Hybrid Retrieval（ChromaDB Vector + BM25 Keyword）
>    ↓
> ③ RRF Fusion
>    ↓
> ④ Similarity Threshold Filter
>    ↓
> ⑤ Cross-Encoder Rerank
>    ↓
> ⑥ Top 3~5
>    ↓
> ⑦ RAG Prompt
>    ↓
> LLM
> ```
>
> 升级后，`/api/chat` 的检索部分从「一句话调 `retrieve()`」升级为「多级检索管线」，其余（SSE 流式、短期记忆、长期画像、Persona）**保持原样**。

---

## 0. 核心原则（每次改动前先读这一节）

1. **现有功能零改动**：`retrieve()` 现有函数一字不动；`server.py` 里的 SSE 流式、记忆、画像、prompt 组装逻辑全部保持原样。
2. **现有文件只「追加」不「重写」**：`rag.py` 只在**末尾追加**新函数；`server.py` 只改 `chat_api` 内的 2 行（import 追加 + 调用点替换），不重写任何现有代码。
3. **每步都有降级**：新增能力（BM25、rerank、query 改写、chunk 重建）任一失败，自动退化为原有行为，**聊天功能永不断**。
4. **回滚容易**：所有新增逻辑集中在 `rag.py` 末尾新增函数 + `server.py` 两行，出问题直接还原这两处即可。
5. **技术方案以本方案为准**：本文档是 RAG 升级的最终约定，若与历史讨论冲突，以本文档为准。

---

## 1. 现状分析（改造前）

当前基础 RAG 链路（`loomai-agent/rag.py` + `server.py` 的 `chat_api`）：

```text
用户 Query
    ↓
ChromaDB 向量检索（collection.query，n_results=3）
    ↓
Top-K（3 条文本片段）
    ↓
检索结果注入 Prompt
    ↓
LLM
```

当前实现的主要特点（**保留作为基线，不要删除**）：

* Knowledge 使用 **Markdown 文件**（`knowledge_base/*.md`，目前 4 篇：色彩搭配 / 风格指南 / 体型穿搭 / 场合穿搭）。
* 当前 Chunk 按**空行**（`split("\n\n")`）进行简单切分，过滤长度 < 10 的片段。
* 向量库用 **ChromaDB**（`PersistentClient`，collection `fashion_knowledge`，`hnsw:space: cosine`）。
* Embedding 用 DashScope **`text-embedding-v3`**（1024 维，`DashScopeEmbeddingFunction` 封装）。
* 相似度度量用 **cosine similarity**。
* 当前主要依赖**单一向量检索**。

**当前缺失（改造点）：**

| 缺失环节 | 当前表现 | 导致的问题 |
|---------|---------|-----------|
| Query Rewrite | 直接用用户原话检索 | 多轮/口语化问题（"那这件呢"）检索不到正确知识 |
| BM25 | 只有向量检索 | 关键词精确命中（"梨形身材""莫兰迪色"）可能被语义检索漏掉 |
| Hybrid Retrieval | 单一 dense 召回 | 召回不充分，Recall 偏低 |
| RRF | 无 | 未来双路召回无法统一排序 |
| 相似度阈值过滤 | 无阈值，硬取 top-3 | 知识库无相关内容时硬塞无关知识，误导模型 |
| Rerank | 只有向量分数排序 | 召回粗排后无精排，最相关的不一定排最前 |

---

## 2. 技术选型（含选型原因）

| 环节 | 选型 | 说明 |
|------|------|------|
| Query 改写 | DashScope `qwen-plus`（`Generation.call`） | 与聊天主模型同款，`temperature=0` 保证稳定 |
| 向量召回 | DashScope `text-embedding-v3`（**沿用现有**） | 1024 维，ChromaDB `cosine` 空间，已有，不动 |
| BM25 关键词召回 | `jieba` 分词 + `rank_bm25.BM25Okapi` | 两个纯 Python 轻量库，生产标准做法 |
| 融合算法 | RRF（Reciprocal Rank Fusion），`k=60` | 业界标准，避免向量分数与 BM25 分数不同量纲无法直接加权 |
| 相似度阈值 | 余弦相似度 `≥ 0.70`（初始经验值） | 只作用于 Dense 向量相似度，非行业标准，需实测调优 |
| Rerank 重排 | **`BAAI/bge-reranker-base`**（本地 Cross-Encoder） | 开源中文重排模型，精排效果好，本地可控 |
| 向量召回候选数 | `vector_top_k = 20` | 先广撒网，再精排 |
| BM25 召回候选数 | `bm25_top_k = 20` | 同上 |
| 最终返回数 | `rerank top_k = 5`（Top 3~5） | 与原 `top_k=3` 对齐量级，稍放宽 |

### 2.1 选型原因（为什么这么选）

* **为什么选择 LLM Query Rewrite**：用户输入天然口语化、冗余、依赖上下文（多轮指代）。用 LLM 改写能把这些"人话"转成适合检索的"标准查询"，这是单一向量检索做不到的。只做改写不做其他（不引入 Multi-Query/HyDE 等，避免过度复杂）。
* **为什么选择 BM25**：向量检索擅长语义相似（"专业感" ≈ "正式"），但会漏掉**精确关键词**（"西装""深色""梨形"）。BM25 基于词频-逆文档频率做精确词项匹配，两者互补，Recall 更高。
* **为什么采用 Hybrid Retrieval**：Dense（向量）+ Sparse（BM25）是业界检索增强的经典组合，单靠任一路都有明显盲区。
* **为什么使用 RRF**：见 7.3，核心是"比排名不比分数"。
* **为什么不直接相加 BM25 与 Vector score**：向量余弦相似度在 `[0,1]`，BM25 分数无上限（几到几十），量纲完全不同，直接相加会让 BM25 大分数主导，结果失真。
* **为什么需要 Similarity Threshold**：防止"知识库没有相关内容时，为了凑 Top-K 硬塞无关片段"。低于阈值的片段直接丢弃，宁可少答也不误导。
* **为什么初始设置 0.70**：0.70 是当前项目基于 `text-embedding-v3` 的**初始经验值**，不是所有 embedding 模型通用的行业标准，后续要用测试集和实际检索效果调优。
* **为什么选择 Cross-Encoder**：见 9.1。双塔/向量模型在召回阶段追求"快"；Cross-Encoder 把 Query 和 Chunk 拼一起精算相关性，精度更高，适合精排。
* **为什么选择 `bge-reranker-base`**：BAAI 出品的中文能力强的开源重排模型，中文检索榜单表现好，契合本项目中文服装知识库；`base` 版（约 1.1GB）比 `large` 版轻，个人项目 CPU 可跑；本地可控、无 API 调用费用。
* **为什么不让 Reranker 直接检索整个知识库**：Cross-Encoder 计算成本高（每个 Query-Chunk 对都要过一遍模型），全库精排不现实；先 Vector + BM25 召回 20~50 个候选，再精排，成本可控。
* **为什么使用 Markdown Structure Chunking**：见 4.1。当前知识本身就是 Markdown，按标题层级切分能保留语义边界，比按空行切更完整。
* **为什么最终只取 Top 3~5**：给 LLM 的 Context 有限（token 成本 + 上下文噪声），3~5 条精排后的高相关片段足够回答，且不会稀释 Persona 和用户画像的作用。

---

## 3. 改造后整体架构（完整 Pipeline）

```text
用户 Query
    ↓
LLM Query Rewrite（qwen-plus）
    ↓
Rewritten Query
    ↓
┌──────────────────────┐
│                      │
↓                      ↓
ChromaDB Vector        BM25
Top 20                 Top 20
│                      │
└──────────┬───────────┘
           ↓
        RRF Fusion（k=60）
           ↓
     Candidate Set（20~50）
           ↓
Similarity Threshold（similarity >= 0.70）
           ↓
Cross-Encoder Rerank（bge-reranker-base）
           ↓
        Top 3~5
           ↓
     Context Assembly
           ↓
       RAG Prompt
           ↓
          LLM
           ↓
       Final Answer
```

> 说明：相似度阈值针对的是 **Dense 向量相似度**，不直接拿 BM25 score 与 0.70 比较。BM25 通过 Top-K + RRF 参与候选集合。实现上阈值在向量结果进入 RRF 前先过滤（详见 8）。

---

## 4. Chunk 策略改造（Markdown Structure Chunking）

### 4.1 从「空行切分」升级为「Markdown 标题层级结构化切分」

当前实现是"按空行切 Chunk"（保留在 1 现状分析中作为旧方案说明）。改造后改为：

> **基于 Markdown 标题层级的结构化语义 Chunking。**

因为 Knowledge 本身就是 Markdown，直接利用 Markdown 的标题结构切分，语义边界更清晰。层级：

```text
# 一级标题（父类别 / 文档主题）
## 二级标题（子类别 / 具体场景）  ← 主要 Chunk 边界
### 三级标题（细节，通常不切分）
```

**当前项目建议：以 `##` 二级标题作为主要 Chunk 边界。**

例如：

```markdown
# 场合穿搭指南

## 通勤上班
...

## 面试
...

## 约会
...
```

切分后形成：

```text
Chunk 1：场合穿搭指南 > 通勤上班
Chunk 2：场合穿搭指南 > 面试
Chunk 3：场合穿搭指南 > 约会
```

**二次切分规则**：如果某个 `##` 下内容过长，再按「段落 → 必要时固定 Token 长度」二次切分。**不引入复杂的语义模型 Chunking**（如 SemanticChunker），保持简单。

### 4.2 Chunk Metadata

每个 Chunk 写入时带上 Metadata：

```json
{
  "document": "场合穿搭.md",
  "parent_category": "场合穿搭指南",
  "category": "面试"
}
```

最终 Chunk 至少包含四个字段：

```text
content            ← Chunk 正文文本
document           ← 来源文件名
parent_category    ← 一级标题（# 文档主题）
category           ← 二级标题（## 场景）
```

**Metadata 的用途：**

* **检索结果溯源**：回答时能告诉用户"这段建议来自《场合穿搭.md》的面试章节"。
* **Prompt Context 信息**：把 `[面试]` 这类 category 标签拼进上下文，让 LLM 知道知识的场景归属。
* **后续 Metadata Filter**：将来可按 `category` 过滤（如用户问面试，只检索 `category=面试` 的片段）。
* **调试检索结果**：打印 id 时能直接看出是哪个文档哪个章节。
* **区分不同知识场景**：通勤 / 面试 / 约会 等场景在向量空间可能相近，靠 metadata 明确区分。

### 4.3 Knowledge Markdown 结构调整（保留原知识，只调层级）

不重新设计知识，保留原有"场合穿搭"等主题和知识内容，只优化 Markdown 层级，适配新 Chunk 策略。推荐结构：

```markdown
# 场合穿搭指南

## 通勤上班

### 穿搭原则
...

### 推荐服装
...

### 推荐颜色
...

### 鞋子
...

## 面试

### 穿搭原则
...

### 推荐服装
...

### 推荐颜色
...

### 配饰
...

## 约会
...
```

要求：保留原有知识内容、不无依据地新增大量知识、主要调整层级和组织方式、保证每个 `##` 场景下形成相对完整的知识单元。

---

## 5. 依赖准备

- [x] **5.1 安装 BM25 依赖**：在**当前 Anaconda 环境**（项目实际运行环境，已确认 `chromadb 1.5.9` + `dashscope` 在此）执行：
  ```bash
  pip install jieba rank_bm25
  ```
  - 验收：`python -c "import jieba, rank_bm25"` 不报错。

- [x] **5.2 安装 Rerank 依赖**：
  ```bash
  pip install FlagEmbedding
  ```
  - `FlagEmbedding` 是 BAAI 官方库，`FlagReranker` 专为 bge-reranker 设计；会自带 `sentence-transformers`/`transformers`（若环境已有 torch/transformers 则复用）。
  - 首次运行会自动下载 `BAAI/bge-reranker-base` 权重（约 1.1GB），需联网。
  - 等价替代：也可用 `sentence_transformers.CrossEncoder("BAAI/bge-reranker-base")`。
  - 验收：`python -c "from FlagEmbedding import FlagReranker"` 不报错。

- [x] **5.3 Java / 前端零改动确认**：本方案只改 Python 侧，**Java（`loomai-main`）、前端（`loomai-fronted`）一行不动**。
  - 验收：确认无需改动 `pom.xml`、`api.js`、任何 `.vue`、任何 `.java`。

---

## 6. Query 改写（LLM Query Rewrite）

### 6.1 目标

将用户口语化、冗余或上下文依赖的 Query，改写成更适合知识库检索的标准化 Query。

例如：

```text
用户："我明天去面试，应该穿什么？"
  ↓ 改写
"面试场合适合穿什么服装？"
```

### 6.2 改写要求（写进 Prompt）

* 只改写 Query，**不直接回答问题**。
* **保留用户真实意图**。
* **保留关键场景和限制条件**。
* **去掉无意义口语**。
* **不得凭空添加事实**。
* 输出**一个** rewritten query 即可。

### 6.3 失败降级

```text
Rewrite 失败
    ↓
直接使用原始 Query
    ↓
继续执行后续 Retrieval
```

不要因为 Query Rewrite 失败导致整个 RAG 不可用。

### 6.4 代码（`rag.py` 末尾追加）

```python
def rewrite_query(query: str, history: str = "") -> str:
    """① LLM Query Rewrite：把口语化/依赖上下文的 Query 改写成标准检索 Query。"""
    if not history:
        return query  # 无历史则无需改写，省一次调用
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

---

## 7. 混合召回（Vector + BM25 + RRF）

```text
                Rewritten Query
                      ↓
             ┌────────┴────────┐
             ↓                 ↓
       ChromaDB Vector       BM25
          Search             Search
             ↓                 ↓
          Top 20             Top 20
             └────────┬────────┘
                      ↓
                    RRF
```

### 7.1 Vector Retrieval（沿用现有，不更换）

* 继续使用 `DashScope text-embedding-v3` + `ChromaDB` + `Cosine Similarity`。
* 不更换现有 Embedding 和 Vector DB。
* 与现有 `retrieve()` 的区别：召回数从 3 提到 20，并额外返回每个片段的 id 和相似度。

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

### 7.2 BM25 Keyword Retrieval

BM25 是经典的关键词检索算法（基于词频 TF × 逆文档频率 IDF）。**Vector Retrieval 擅长语义相似，BM25 擅长关键词精确匹配，两者结合提高 Recall。**

**结合当前知识库举例：**

* 语义查询「什么穿搭能让面试官觉得我比较专业」——"专业"和"正式"是语义近义，向量检索能命中，BM25 反而可能因为字面不匹配而漏掉。
* 精确关键词查询「面试 西装 深色」——"西装""深色"是具体词，BM25 能精确命中包含这些词的 Chunk，而向量 embedding 可能被"面试"这个高频词稀释，把"西装"相关片段排到后面。

```python
def _all_chunks():
    """返回知识库全部 (id, text) 片段，作为 BM25 语料（与向量库同源）。"""
    collection = get_collection()
    result = collection.get()
    ids = result.get("ids", [])
    docs = result.get("documents", [])
    return list(zip(ids, docs))


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

### 7.3 RRF Fusion

**为什么需要 RRF**：Vector 的余弦相似度在 `[0,1]`，BM25 分数无上限（几到几十），两者量纲不同，**不能直接 `BM25 score + Vector score` 相加**。RRF 只看「排名」不看「分数」，天然规避量纲问题。

**公式**：

```text
RRF Score = Σ 1 / (k + rank)
```

**`k = 60` 的意义**：k 越大，排名靠后的贡献衰减越慢、融合越"宽容"；`k=60` 是业界（Facebook 的 RRF 论文）常用默认值，当前项目初始配置即 60。

**简单计算示例**：

```text
Vector 排序：A B C D E
BM25 排序：  C A F B G

RRF(k=60) 各文档得分：
  A: 1/(60+1) + 1/(60+2) = 1/61 + 1/62 ≈ 0.0325
  C: 1/(60+3) + 1/(60+1) = 1/63 + 1/61 ≈ 0.0323
  B: 1/(60+2) + 1/(60+4) = 1/62 + 1/64 ≈ 0.0317
  F: 无向量名次 + 1/(60+3) = 1/63 ≈ 0.0159（BM25 独有命中，仍参与）
  ...

融合后统一排名：A > C > B > ... > F ...
```

**代码实现思路**：

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

---

## 8. 相似度阈值过滤

当前项目初始值：

```yaml
similarity_threshold: 0.70
```

> **0.70 是当前项目的初始经验值，不是所有 Embedding 模型都通用的固定行业标准，后续应该通过测试集和实际检索效果进行调优。**

流程：

```text
Vector Search Top 20
        ↓
Similarity >= 0.70 ?
        ↓ 是
    Candidate
        ↓ 否
      丢弃
```

要点：

* 主要针对 **Dense Vector similarity**。
* **不直接拿 BM25 score 与 0.70 比较**（量纲不同）。
* BM25 通过 Top-K + RRF 参与候选集合，本身已通过 `score > 0` 过滤无词项命中的片段。

**如果过滤后没有相关结果**：

```text
没有相关 Context
    ↓
不强行注入无关知识
```

避免「为了凑 Top-K 而把无关文档塞进 Prompt」导致模型答非所问。

```python
def _apply_threshold(vector_hits, threshold: float = SIMILARITY_THRESHOLD):
    """③ 相似度阈值：丢弃余弦相似度 < threshold 的向量结果。"""
    return [(i, t, s) for (i, t, s) in vector_hits if s >= threshold]
```

---

## 9. Cross-Encoder Rerank

完整流程：

```text
Vector Top 20
+
BM25 Top 20
        ↓
RRF
        ↓
Candidate Top 20~50
        ↓
Cross-Encoder（bge-reranker-base）
        ↓
重新计算 Query-Chunk Relevance
        ↓
重新排序
        ↓
Top 3~5
```

### 9.1 为什么需要 Rerank？

* Vector Retrieval / BM25 属于**第一阶段粗召回**（追求快、召回全）。
* Reranker 属于**第二阶段精排**（追求准）。
* Cross-Encoder 会**同时输入 `Query + Chunk`**，通过注意力机制判断两者的**真实相关性**，精度远高于"各自算向量再比余弦"的双塔方式。

### 9.2 为什么不让 Reranker 搜索整个知识库？

Cross-Encoder 计算成本更高（每个 Query-Chunk 对都要过一遍模型），全库精排不现实。所以先用 Vector + BM25 召回 20~50 个候选，再对候选做精排，成本可控。

### 9.3 代码

```python
_reranker = None

def _get_reranker():
    global _reranker
    if _reranker is None:
        from FlagEmbedding import FlagReranker
        # use_fp16=True 有 GPU 更快；无 GPU 改为 False 走 CPU
        _reranker = FlagReranker("BAAI/bge-reranker-base", use_fp16=True)
    return _reranker


def rerank(query: str, documents, top_n: int = RERANK_TOP_K):
    """④ Cross-Encoder Rerank：用 bge-reranker-base 对候选精排，返回 top_n 文本。"""
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
        print(f"Rerank 失败，回退 RRF 顺序: {e}")
        return list(documents)[:top_n]
```

---

## 10. 完整检索管线编排（`retrieve_advanced`）

把 ①②③④ 串起来，是 `server.py` 里唯一要替换的调用点。

```python
# —— 以下为「完整 RAG」升级新增常量（集中管理，便于调优） ——
VECTOR_TOP_K = 20                 # 向量召回候选数
BM25_TOP_K = 20                   # BM25 召回候选数
SIMILARITY_THRESHOLD = 0.70       # 余弦相似度阈值（初始经验值）
RRF_K = 60                        # RRF 融合常数
RERANK_TOP_K = 5                  # Rerank 后最终返回条数（Top 3~5）
RERANK_MODEL = "BAAI/bge-reranker-base"


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

> 说明：阈值在向量结果进入 RRF 前过滤（对应第 8 节「Vector Search Top 20 → Similarity >= 0.70 → Candidate」）；BM25 结果本身是"词项命中"，不套向量阈值，直接参与 RRF。

---

## 11. `server.py` 改动（只改 2 行）

`chat_api()` 函数内，目前是（`server.py` 第 677、696 行附近）：

```python
    from rag import retrieve
    ...
    knowledge = retrieve(message, top_k=3)
```

改为：

```python
    from rag import retrieve, retrieve_advanced
    ...
    knowledge = retrieve_advanced(message, history, top_k=5)
```

* **第 1 行改动**：import 语句追加 `retrieve_advanced`（保留 `retrieve`，便于回滚/对比）。
* **第 2 行改动**：检索调用换成 `retrieve_advanced(message, history, top_k=5)`，传入 `history`（短期记忆摘要，第 691 行已算出，作用域内可用）。
* **其余零改动**：`memory_store`、`profile_text`、`system_prompt` 组装、`generate()` 流式、`save_turn`、`Response(...)` 全部不动。

---

## 12. Prompt Context 增强（只增强 RAG Context）

保留原来的聊天 Prompt、Persona、Memory、SSE、Conversation Context 等机制，**只增强 RAG Context 部分**。最终 RAG Context 结构：

```text
系统指令
+
Persona / User Profile / Memory
+
Retrieved Knowledge
+
User Query
```

Knowledge Context 建议格式（利用 chunk 的 metadata `category` 打上场景标签）：

```text
【知识库参考资料】

[面试]
面试场合建议选择简洁正式的服装……

[通勤上班]
通勤上班适合……

【用户问题】
我明天去面试应该穿什么？
```

要求：

* 如果知识库没有相关信息，**不要强行使用无关 Context**，也不要让 RAG 伪造知识（即 `retrieve_advanced` 返回空时，`knowledge_text=""`，prompt 里不出现知识段）。

---

## 13. 索引重建

由于 Chunk 方式发生变化（空行 → Markdown 标题层级 + Metadata），必须重建索引，避免新旧 Chunk 混合。

```text
修改 Knowledge Markdown
        ↓
Markdown Chunking（按 ## 标题 + Metadata）
        ↓
生成 Metadata（document / parent_category / category）
        ↓
Embedding（text-embedding-v3）
        ↓
重新写入 ChromaDB
        ↓
重新构建 BM25 Index
```

要点：

* 保留原方案已有的 ChromaDB 初始化和数据持久化设计（`PersistentClient` + `get_or_create_collection`）。
* Chunk 策略变化后，需删除旧 collection 或新建 collection，重新 `ingest`。
* 建议新增 `ingest_structured()` 函数负责「Markdown Chunking + Metadata + 入库」，旧 `ingest()` 保留不动作为基线。

```python
def ingest_structured() -> int:
    """按 Markdown 标题层级切分 + 生成 metadata 后重新入库，返回入库片段数。"""
    collection = get_collection()
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

> `_split_markdown(content, fname)`：解析 `#` 取 `parent_category`、`##` 取 `category`，以 `##` 为边界切 chunk，过长再按段落/Token 二次切分，返回 `(chunk_text, {"document": fname, "parent_category": ..., "category": ...})`。

---

## 14. 降级机制（保持简单，任一子模块失败不拖垮聊天）

| 子模块失败 | 降级行为 |
|-----------|---------|
| Query Rewrite 失败 | 使用原始 Query，继续后续 Retrieval |
| BM25 失败（依赖未装/异常） | 继续使用 Vector Retrieval，退化为纯向量 |
| Vector Retrieval 失败 | 若 BM25 可用则继续 BM25 |
| Reranker 失败 | 使用 RRF 后的候选结果（取前 top_n） |
| 最终没有相关 Context | 不注入无关知识，聊天照常（不含知识段） |

**核心结论**：整条新链路是「渐进增强」，任何一环失败都只会让检索效果回落到改造前水平，**绝不会让聊天接口 500**。`chat_api` 现有 try/except 兜底，SSE 照常返回。

---

## 15. 联调与测试

- [x] **15.1 依赖验收**：`python -c "import jieba, rank_bm25; from FlagEmbedding import FlagReranker; from dashscope import Generation"` 通过。

- [x] **15.2 Chunk 重建验收**：跑 `ingest_structured()`，确认 ChromaDB 重新入库，`collection.get()` 能看到 `document`/`parent_category`/`category` 三个 metadata。

- [x] **15.3 rag.py 单测**：临时脚本调用 `retrieve_advanced`，确认四环节各自跑通，且老 `retrieve()` 仍正常。

### 15.4 检索测试用例

| # | Query | 验证点 |
|---|-------|--------|
| 1 | 面试应该穿什么？ | 普通 Query，基础召回 |
| 2 | 我要去参加面试，穿啥比较合适？ | Query Rewrite（口语化 → 标准化） |
| 3 | 面试 西装 深色 | BM25 关键词精确匹配 |
| 4 | 什么穿搭能够让面试官觉得我比较专业？ | Vector Retrieval 语义相似 |
| 5 | 怎么做红烧肉？ | Similarity Threshold + 无相关 Context（应返回空/不注入知识） |
| 6 | （综合 Query） | Hybrid Retrieval：Vector + BM25 + RRF 是否协同 |
| 7 | （同上） | Rerank：对比「RRF 排序」与「Rerank 后排序」，确认 Rerank 把真正相关 Chunk 排到前面 |

- [x] **15.5 Flash 端到端回归**：前端 Flash 页发「帮我推荐一套通勤穿搭」→ 确认逐字流式输出、回答体现知识库内容。

- [x] **15.6 多轮指代验证**：先问「通勤穿什么」，再问「那约会呢」→ 确认能接上文（Query Rewrite 生效）。

- [x] **15.7 无关问题验证**：问知识库完全无关的问题 → 确认不被硬塞无关穿搭知识（阈值生效）。

- [x] **15.8 现有功能回归（红线）**：逐项确认 **图片编辑、图片合并、我的图片、历史记录、登录、图片搜索** 全部正常；Flash 聊天本身正常。

---

## 16. 改造前后对比

### 改造前

```text
Query
 ↓
ChromaDB（向量检索）
 ↓
Top 3
 ↓
Prompt
 ↓
LLM
```

### 改造后

```text
Query
 ↓
Query Rewrite
 ↓
Vector + BM25
 ↓
RRF
 ↓
Similarity Threshold
 ↓
Cross-Encoder Rerank
 ↓
Top 3~5
 ↓
Prompt
 ↓
LLM
```

### 每个新增组件解决的问题

| 组件 | 解决的问题 |
|------|-----------|
| Query Rewrite | 口语化、冗余、多轮指代导致的检索不准 |
| BM25 | 关键词精确命中被语义检索漏掉 |
| Hybrid Retrieval | 单一 Dense 召回 Recall 不足 |
| RRF | 双路召回分数量纲不同，无法直接相加 |
| Similarity Threshold | 无相关内容时硬塞无关片段误导模型 |
| Cross-Encoder Rerank | 粗排后无精排，最相关的排不到前面 |

---

## 17. 面试话术

> "我之前项目中的 RAG 是比较基础的 Dense Retrieval：Knowledge 文档按空行切 Chunk 后写入 ChromaDB，用户 Query 直接做向量检索，再把 Top-K 结果注入 Prompt。后来我对检索链路做了增强，把它升级成一条完整的 Retrieval Pipeline。"

然后按流程完整说明：

```text
Query Rewrite（LLM 把口语化/多轮问题改写成标准检索查询）
        ↓
BM25 + Vector Hybrid Retrieval（语义 + 关键词双路召回）
        ↓
RRF（把双路结果按排名融合成统一排序）
        ↓
Similarity Threshold（过滤低相似度的无关片段）
        ↓
Cross-Encoder Rerank（bge-reranker-base 对候选精排）
        ↓
Top-K（取最相关的 3~5 条）
        ↓
Prompt（注入上下文，交给 LLM 生成）
```

重点让面试官听出来：

> **"我不是简单调用一个向量数据库，而是理解并实现了完整的 RAG Retrieval Pipeline——从粗召回到精排、从单一检索到混合检索、还有相似度阈值和降级兜底。"**

---

## 18. 统一参数配置

```yaml
rag:
  retrieval:
    vector_top_k: 20
    bm25_top_k: 20
    similarity_threshold: 0.70
    rrf_k: 60

  rerank:
    model: BAAI/bge-reranker-base
    top_k: 5
```

```text
Vector Top-K        = 20
BM25 Top-K          = 20
RRF Candidate       = 20~50
Final Rerank Top-K  = 3~5
```

最终给 LLM 的 Context 建议：

```text
Top 3~5
```

> 以上参数都是当前项目的**初始配置**，不要写成绝对行业标准；`similarity_threshold` 尤其需要按 `text-embedding-v3` 的实际检索效果和测试集调优。

---

## 附录 A：文件清单

### 修改文件

| 文件 | 改动类型 | 改动内容 |
|------|---------|---------|
| `loomai-agent/rag.py` | **末尾追加** | 新增 `rewrite_query` / `_vector_retrieve_with_scores` / `_all_chunks` / `bm25_retrieve` / `_rrf_fusion` / `_apply_threshold` / `rerank` / `retrieve_advanced` / `_split_markdown` / `ingest_structured` + 6 个常量 |
| `loomai-agent/server.py` | **2 行** | import 追加 `retrieve_advanced`；检索调用换成 `retrieve_advanced(message, history, top_k=5)` |

### 明确不改动的文件（红线）

`rag.py` 的 `retrieve`/`get_collection`/`ingest`/`DashScopeEmbeddingFunction`；`server.py` 的 `/api/chat` 流式生成、记忆、画像、prompt 组装；`memory.py`；**Java 全部**；**前端全部**；`docker-compose.yml`。

### 新增依赖

| 依赖 | 用途 | 安装 |
|------|------|------|
| `jieba` | 中文分词（BM25 用） | `pip install jieba` |
| `rank_bm25` | BM25 评分（BM25Okapi） | `pip install rank_bm25` |
| `FlagEmbedding` | bge-reranker-base 重排（Cross-Encoder） | `pip install FlagEmbedding` |

---

## 进度追踪说明（给 AI 的约定）

- 每个 `- [ ]` 是一个**可独立验收的小步骤**，完成后把 `[ ]` 改成 `[x]`。
- 完成标准 = 该步骤后面的「验收」条件达成。
- 顺序：**先装依赖 → 重建 Chunk → 追加 `rag.py` → 改 `server.py` 两行 → 单测 → 端到端回归**，不要跳步。
- 全程守住第 0 节红线：现有功能零改动、现有文件只追加。

---

## 改造步骤（完整落地清单）

> 本部分严格遵循前文方案，是「从零开始改代码」的执行顺序。所有新增代码都写在 `loomai-agent/rag.py` 末尾，`server.py` 只改 2 行。
> **每个小节最后一步是「验证」**，验证通过后才把该小节的框框打勾，再进入下一小节，不要跳步。

### 步骤 0：环境准备（依赖 + 常量）

- [x] 安装 BM25 依赖：`pip install jieba rank_bm25`
- [x] 安装 Rerank 依赖：`pip install FlagEmbedding`（首次运行会自动下载 `BAAI/bge-reranker-base` 权重，约 1.1GB）
- [x] 在 `rag.py` 末尾追加 6 个常量（对应方案 §10）：`VECTOR_TOP_K=20`、`BM25_TOP_K=20`、`SIMILARITY_THRESHOLD=0.70`、`RRF_K=60`、`RERANK_TOP_K=5`、`RERANK_MODEL="BAAI/bge-reranker-base"`
- [x] 验证：`python -c "import jieba, rank_bm25; from FlagEmbedding import FlagReranker; from dashscope import Generation; import rag; print(rag.VECTOR_TOP_K, rag.RERANK_MODEL)"` 不报错，且输出 `20 BAAI/bge-reranker-base`

### 步骤 1：Chunk 结构化切分 + 索引重建

- [x] 新增 `_split_markdown(content, fname)`：解析 `#` 取 `parent_category`、`##` 取 `category`，以 `##` 为边界切 chunk，过长再按段落/Token 二次切分，返回 `(chunk_text, metadata_dict)`（对应方案 §13）
- [x] 新增 `ingest_structured()`：遍历 `knowledge_base/*.md`，调用 `_split_markdown`，`upsert` 时带上 `document`/`parent_category`/`category` 三个 metadata（对应方案 §13 代码）
- [x] 按方案 §4.3 调整 `knowledge_base/*.md` 的 Markdown 层级（`#` 主题 + `##` 场景 + `###` 细节），保留原有知识内容，不无依据新增知识（注：4 个 md 本身已是 `#`+`##` 结构，无需改动，也未无依据加 `###`）
- [x] 运行 `python -c "import rag; print(rag.ingest_structured())"` 重新写入 ChromaDB
- [x] 验证：`python -c "import rag; print(rag.get_collection().get()['metadatas'][:2])"` 能看到每个 chunk 带 `document`/`parent_category`/`category`；`rag.retrieve('面试')` 老函数仍正常

### 步骤 2：Query 改写（`rewrite_query`）

- [x] 追加 `rewrite_query(query, history="")`（对应方案 §6.4）：无 `history` 时直接返回原 query；有 `history` 时用 `qwen-plus` + `temperature=0` 改写，prompt 要求「只改写不回答、保留意图/场景、去掉口语、不加事实、输出一个 query」
- [x] 失败降级：`Generation.call` 异常时 `except` 后返回原 query（已写进方案 §6.4 代码）
- [x] 验证：`python -c "import rag; print(rag.rewrite_query('我要去参加面试，穿啥比较合适？', '之前聊过通勤穿搭'))"` 输出一句标准化查询（如「面试场合适合穿什么服装」）

### 步骤 3：向量召回增强（`_vector_retrieve_with_scores`）

- [x] 追加 `_vector_retrieve_with_scores(query, top_k=VECTOR_TOP_K)`（对应方案 §7.1）：`collection.query(include=["documents","distances"])`，把 `distance` 转回相似度 `1 - d`，返回 `(id, text, 相似度)` 列表
- [x] 验证：`python -c "import rag; print(rag._vector_retrieve_with_scores('面试穿什么', 5))"` 返回带 id/文本/相似度的列表，相似度值在 [0,1]

### 步骤 4：BM25 检索（`_all_chunks` + `bm25_retrieve`）

- [x] 追加 `_all_chunks()`：用 `collection.get()` 拿全库 `(id, text)` 作 BM25 语料（与向量库同源）
- [x] 追加 `bm25_retrieve(query, top_k=BM25_TOP_K)`（对应方案 §7.2）：`jieba.lcut` 分词 + `BM25Okapi.get_scores`，只保留分数 > 0 的 top_k；`ImportError` 时返回 `[]`
- [x] 验证：`python -c "import rag; print(rag.bm25_retrieve('面试 西装 深色', 5))"` 能精确命中含这些关键词的 chunk

### 步骤 5：RRF 融合（`_rrf_fusion`）

- [x] 追加 `_rrf_fusion(ranked_lists, k=RRF_K)`（对应方案 §7.3）：按排名累加 `1/(k+rank+1)`，返回 `(id, text, rrf_score)` 降序
- [x] 验证：`python -c "import rag; v=rag._vector_retrieve_with_scores('面试',5); b=rag.bm25_retrieve('面试',5); print(rag._rrf_fusion([v,b])[:3])"` 输出融合后的统一排序，且两路都命中的 id 排在更前

### 步骤 6：相似度阈值（`_apply_threshold`）

- [x] 追加 `_apply_threshold(vector_hits, threshold=SIMILARITY_THRESHOLD)`（对应方案 §8）：只保留相似度 >= 0.70 的向量结果
- [x] 验证：`python -c "import rag; print(rag._apply_threshold([('a','正装',0.9),('b','无关',0.5)]))"` 只返回相似度 0.9 那条，0.5 被过滤

### 步骤 7：Rerank 重排（`_get_reranker` + `rerank`）

- [x] 追加 `_get_reranker()`：懒加载 `FlagReranker("BAAI/bge-reranker-base", use_fp16=True)`（无 GPU 时改 `use_fp16=False` 走 CPU）
- [x] 追加 `rerank(query, documents, top_n=RERANK_TOP_K)`（对应方案 §9.3）：`compute_score([[query, doc], ...], normalize=True)` 后按分降序取 top_n；失败回退原顺序
- [x] 验证：`python -c "import rag; print(rag.rerank('面试穿什么', ['面试建议穿正装','通勤可选休闲']))"` 把「面试」相关的排到最前

### 步骤 8：编排函数（`retrieve_advanced`）

- [x] 追加 `retrieve_advanced(query, history="", top_k=RERANK_TOP_K)`（对应方案 §10）：串起 改写 → 向量 → BM25 → 阈值 → RRF → Rerank
- [x] 验证（相关问题）：`python -c "import rag; print(rag.retrieve_advanced('面试穿什么', ''))"` 返回 3~5 条精排后的知识片段
- [x] 验证（无关问题）：`python -c "import rag; print(rag.retrieve_advanced('怎么做红烧肉？', ''))"` 返回空列表 `[]`（阈值拦截，不注入无关知识）

### 步骤 9：`server.py` 接线（只改 2 行）

- [x] 改 import（`chat_api` 内，`server.py` 第 677 行附近）：`from rag import retrieve` → `from rag import retrieve, retrieve_advanced`
- [x] 改调用（第 696 行附近）：`knowledge = retrieve(message, top_k=3)` → `knowledge = retrieve_advanced(message, history, top_k=5)`
- [x] 验证：重启 Python 服务，前端 Flash 页发「帮我推荐一套通勤穿搭」→ 逐字流式输出、回答体现知识库内容
- [x] 验证（红线回归）：逐项确认 **图片编辑、图片合并、我的图片、历史记录、登录、图片搜索** 全部正常，Flash 聊天本身正常

### 全部步骤完成标志

- [x] 上面 0~9 所有框框已打勾，且 §15 联调测试 7 个用例全部通过，本改造方案交付完成
