from typing import List, Optional
from sqlalchemy.orm import Session
from sqlalchemy import or_

from app.models.ai import AiKnowledgeDoc, AiKnowledgeChunk


def retrieve_chunks(db: Session, query_text: str, top_k: int = 3) -> List[dict]:
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
    top_chunks = scored[:top_k]

    results = []
    for score, chunk in top_chunks:
        doc = db.query(AiKnowledgeDoc).filter(AiKnowledgeDoc.id == chunk.doc_id).first()
        results.append({
            "chunk_id": chunk.id,
            "doc_title": doc.title if doc else "",
            "chunk_text": chunk.chunk_text,
            "metadata": chunk.chunk_metadata,
            "score": score,
        })

    return results


def build_rag_context(chunks: List[dict]) -> str:
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
