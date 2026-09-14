"""RAG 检索服务

检索策略
--------
**优先走向量检索**（ChromaDB + DashScope Embedding），
向量库不可用（未构建 / 依赖缺失 / 调用失败）时**自动降级到关键词匹配**，
保证 AI 主链路不会因为 RAG 故障而中断。

向量库位置：`backend/data/chroma`，由 `scripts/build_vectorstore.py` 构建。

与上下游的契约
--------------
`retrieve_chunks()` 的函数签名与返回结构**保持不变**，
因此 `ai_service.chat()` 中的调用点、以及 `build_rag_context()` 均无需改动。
"""

import logging
import os
from typing import List, Optional

from sqlalchemy import or_
from sqlalchemy.orm import Session

from app.models.ai import AiKnowledgeChunk, AiKnowledgeDoc

logger = logging.getLogger("couple.rag")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CHROMA_DIR = os.path.join(BASE_DIR, "data", "chroma")
COLLECTION_NAME = "couple_theory"

#: 向量库懒加载缓存。"loaded" 标记保证只尝试一次，避免每次请求都重试失败路径
_vectorstore = None
_vectorstore_loaded = False


def _get_vectorstore():
    """懒加载 Chroma 向量库；任何失败都返回 None，由上层降级。"""
    global _vectorstore, _vectorstore_loaded

    if _vectorstore_loaded:
        return _vectorstore
    _vectorstore_loaded = True

    if not os.path.isdir(CHROMA_DIR):
        logger.warning("[RAG] 向量库目录不存在，降级到关键词检索: %s", CHROMA_DIR)
        return None

    try:
        from langchain_chroma import Chroma

        from app.services.embedding import embeddings

        _vectorstore = Chroma(
            collection_name=COLLECTION_NAME,
            embedding_function=embeddings,
            persist_directory=CHROMA_DIR,
            collection_metadata={"hnsw:space": "cosine"},
        )
        logger.info("[RAG] 向量库加载成功: %s", CHROMA_DIR)
    except Exception as exc:  # 依赖缺失或库损坏
        logger.warning("[RAG] 向量库加载失败，降级到关键词检索: %s", exc)
        _vectorstore = None

    return _vectorstore


def retrieve_chunks(db: Session, query_text: str, top_k: int = 3) -> List[dict]:
    """检索与用户输入相关的理论片段。

    返回结构（与旧版保持一致）::

        [{"chunk_id", "doc_title", "chunk_text", "metadata", "score"}]
    """
    results = _vector_search(query_text, top_k)
    if results:
        return results
    return _keyword_search(db, query_text, top_k)


def _vector_search(query_text: str, top_k: int = 3) -> List[dict]:
    """向量相似度检索。"""
    store = _get_vectorstore()
    if store is None or not query_text or not query_text.strip():
        return []

    try:
        hits = store.similarity_search_with_score(query_text, k=top_k)
    except Exception as exc:
        logger.warning("[RAG] 向量检索失败: %s", exc)
        return []

    results = []
    for doc, distance in hits:
        meta = doc.metadata or {}
        results.append({
            "chunk_id": getattr(doc, "id", "") or "",
            "doc_title": meta.get("doc_title", ""),
            "chunk_text": doc.page_content,
            "metadata": meta,
            # cosine 距离 → 相似度（越大越相关），与原 score 语义对齐
            "score": round(1.0 - float(distance), 4),
            "retriever": "vector",
        })

    if results:
        logger.info(
            "[RAG] 向量检索命中 %d 条 | top1=%s(%.4f)",
            len(results), results[0]["doc_title"], results[0]["score"],
        )
    return results


def _keyword_search(db: Session, query_text: str, top_k: int = 3) -> List[dict]:
    """关键词兜底检索（原实现，保留以保证可用性）。"""
    keywords = _extract_keywords(query_text)
    if not keywords:
        return []

    conditions = []
    for kw in keywords:
        conditions.append(AiKnowledgeChunk.chunk_text.ilike(f"%{kw}%"))

    chunks = (
        db.query(AiKnowledgeChunk)
        .filter(or_(*conditions))
        .limit(top_k * 2)
        .all()
    )

    scored = []
    for chunk in chunks:
        score = sum(1 for kw in keywords if kw in chunk.chunk_text)
        scored.append((score, chunk))

    scored.sort(key=lambda x: x[0], reverse=True)

    results = []
    for score, chunk in scored[:top_k]:
        doc = db.query(AiKnowledgeDoc).filter(AiKnowledgeDoc.id == chunk.doc_id).first()
        results.append({
            "chunk_id": chunk.id,
            "doc_title": doc.title if doc else "",
            "chunk_text": chunk.chunk_text,
            "metadata": chunk.chunk_metadata,
            "score": score,
            "retriever": "keyword",
        })
    return results


def build_rag_context(chunks: List[dict]) -> str:
    """把召回片段拼装为注入 Prompt 的上下文。"""
    if not chunks:
        return ""
    parts = ["## 沟通原则参考"]
    for i, chunk in enumerate(chunks, 1):
        title = chunk.get("doc_title", "")
        text = chunk.get("chunk_text", "")
        parts.append(f"### 参考 {i}: {title}\n{text}")
    parts.append("\n请将以上知识作为参考，不伪造研究结论。结合用户具体情况给出建议。")
    return "\n\n".join(parts)


def seed_document(db: Session, title: str, source: str, content: str, chunk_size: int = 500) -> int:
    """把一篇文档按段落切分并写入 MySQL（供关键词兜底检索使用）。"""
    existing = db.query(AiKnowledgeDoc).filter(AiKnowledgeDoc.title == title).first()
    if existing:
        return existing.id

    doc = AiKnowledgeDoc(
        title=title,
        source=source,
        content=content,
        status="active",
    )
    db.add(doc)
    db.flush()

    paragraphs = [p.strip() for p in content.split("\n\n") if p.strip()]
    current_chunk = ""
    for para in paragraphs:
        if len(current_chunk) + len(para) > chunk_size and current_chunk:
            chunk = AiKnowledgeChunk(
                doc_id=doc.id,
                chunk_text=current_chunk.strip(),
                metadata={"doc_title": title, "source": source},
            )
            db.add(chunk)
            current_chunk = para
        else:
            current_chunk += "\n\n" + para if current_chunk else para

    if current_chunk.strip():
        chunk = AiKnowledgeChunk(
            doc_id=doc.id,
            chunk_text=current_chunk.strip(),
            metadata={"doc_title": title, "source": source},
        )
        db.add(chunk)

    db.flush()
    return doc.id


def _extract_keywords(text: str) -> List[str]:
    import re
    text = re.sub(r'[^\w\s]', ' ', text)
    words = text.split()
    stopwords = {
        "的", "了", "是", "在", "我", "你", "他", "她", "它",
        "和", "与", "或", "但", "不", "有", "这", "那", "都",
        "就", "也", "还", "把", "被", "让", "给", "从", "到",
        "the", "a", "an", "is", "are", "was", "were", "be",
        "have", "has", "had", "do", "does", "did", "will",
        "would", "could", "should", "may", "might", "can",
        "i", "you", "he", "she", "it", "we", "they",
        "and", "or", "but", "not", "this", "that", "to", "of",
        "in", "on", "at", "for", "with", "as", "by",
    }
    keywords = [w for w in words if len(w) >= 2 and w.lower() not in stopwords]
    return keywords[:10]
