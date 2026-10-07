"""
RAG 知识库模块（新增，独立于 server.py）

功能：
- 用 DashScope text-embedding-v3 生成文本向量
- 用 ChromaDB 持久化存储服装搭配知识
- 提供 ingest()（入库）和 retrieve()（检索）

注意：本模块不 import server.py 的任何内容，可单独运行验证。
"""
import os

from dotenv import load_dotenv

import chromadb
from dashscope import TextEmbedding

# 基于本文件所在目录定位，避免工作目录不同导致路径错误
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CHROMA_DIR = os.path.join(BASE_DIR, "chroma_db")
KNOWLEDGE_DIR = os.path.join(BASE_DIR, "knowledge_base")
COLLECTION_NAME = "fashion_knowledge"

load_dotenv(os.path.join(BASE_DIR, ".env"))
API_KEY = os.getenv("DASHSCOPE_API_KEY")


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
        # ChromaDB 会传入 list[str]
        if isinstance(input, str):
            input = [input]
        embeddings = []
        for text in input:
            resp = TextEmbedding.call(model=self.model, input=text, api_key=API_KEY)
            if resp.status_code == 200:
                embeddings.append(resp.output["embeddings"][0]["embedding"])
            else:
                # 兜底：维度 1024 的零向量，避免单条失败拖垮整个入库
                embeddings.append([0.0] * 1024)
        return embeddings


def get_collection():
    client = chromadb.PersistentClient(path=CHROMA_DIR)
    return client.get_or_create_collection(
        name=COLLECTION_NAME,
        embedding_function=DashScopeEmbeddingFunction(),
        metadata={"hnsw:space": "cosine"},
    )


def _split_chunks(content: str, min_len: int = 10):
    """按空行分段，过滤过短片段。"""
    chunks = []
    for part in content.split("\n\n"):
        part = part.strip()
        if len(part) >= min_len:
            chunks.append(part)
    return chunks


def ingest() -> int:
    """读取 knowledge_base/*.md 并写入 ChromaDB，返回入库的片段数量。"""
    collection = get_collection()
    documents, ids, metadatas = [], [], []
    for fname in sorted(os.listdir(KNOWLEDGE_DIR)):
        if not fname.endswith(".md"):
            continue
        path = os.path.join(KNOWLEDGE_DIR, fname)
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        for i, chunk in enumerate(_split_chunks(content)):
            documents.append(chunk)
            ids.append(f"{fname}-{i}")
            metadatas.append({"source": fname})
    if documents:
        collection.upsert(documents=documents, ids=ids, metadatas=metadatas)
    return len(documents)


def retrieve(query: str, top_k: int = 3):
    """根据问题检索最相关的知识片段，返回 list[str]。"""
    collection = get_collection()
    results = collection.query(query_texts=[query], n_results=top_k)
    return results.get("documents", [[]])[0]


# —— 以下为「完整 RAG」升级新增常量（集中管理，便于调优） ——
VECTOR_TOP_K = 20                 # 向量召回候选数
BM25_TOP_K = 20                   # BM25 召回候选数
SIMILARITY_THRESHOLD = 0.70       # 余弦相似度阈值（初始经验值，需实测调优）
RRF_K = 60                        # RRF 融合常数
RERANK_TOP_K = 5                  # Rerank 后最终返回条数（Top 3~5）
RERANK_MODEL = "BAAI/bge-reranker-base"  # Cross-Encoder 重排模型


# —— 步骤 1：Chunk 结构化切分（Markdown 标题层级）+ 索引重建 ——
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


# —— 步骤 2：Query 改写（LLM Query Rewrite） ——
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


# —— 步骤 3：向量召回增强（带 id 和相似度） ——
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


# —— 步骤 4：BM25 关键词召回 ——
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


# —— 步骤 5：RRF 融合 ——
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


# —— 步骤 6：相似度阈值过滤 ——
def _apply_threshold(vector_hits, threshold: float = SIMILARITY_THRESHOLD):
    """丢弃余弦相似度 < threshold 的向量结果，只保留语义足够相关的候选。"""
    return [(i, t, s) for (i, t, s) in vector_hits if s >= threshold]


# —— 步骤 7：Cross-Encoder Rerank ——
_reranker = None


def _get_reranker():
    """懒加载本地 bge-reranker-base 模型（首次会下载权重，之后走本地缓存）。"""
    global _reranker
    if _reranker is None:
        from FlagEmbedding import FlagReranker
        import torch
        # 有 GPU 用 fp16 加速；无 GPU 走 CPU
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


# —— 步骤 8：完整检索管线编排 ——
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
