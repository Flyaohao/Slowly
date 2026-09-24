"""记忆相关性召回（P0-4，按补充约束实现）。

分层：
  主路径 = Chroma `couple_memory` 按 query 向量召回 + 相似度阈值
  降级   = 最近 N 条（created_at desc），可见性规则不变

可见性红线（约束①，覆盖手册错误枚举）：
    条件 = (user_id==自己 AND visibility=="private")
           OR (relation_id==本关系 AND visibility=="couple")
  实测枚举只有 "private" / "couple"（无 "private_self"）。
  **绝不含对方 user_id 的 private**（越权）；伴侣 couple 记忆注入时标
  "TA 曾说过…" 来源。

embedding 纪律（约束④）：
  query 向量化硬超时 3s、失败/超时一律走降级，绝不抛错、不拖首字延迟。

历史回填（约束②）：
  写入挂钩只覆盖新记忆；旧行用 scripts/build_memory_index.py --backfill
  幂等回填。

实现说明：
  用 `chromadb.PersistentClient` 直连，不用 langchain_chroma 包装——
  同进程里 theory 与 memory 各开一个 langchain Chroma 会撞
  "Could not connect to tenant default_tenant"。
"""
from __future__ import annotations

import logging
import os
import threading
from typing import Any, Dict, List, Optional

import httpx
from sqlalchemy import and_, or_
from sqlalchemy.orm import Session

from app.core import config
from app.models.ai import AiMemory

logger = logging.getLogger("couple.memory_retrieval")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CHROMA_DIR = config.CHROMA_DIR or os.path.join(BASE_DIR, "data", "chroma")
MEMORY_COLLECTION = "couple_memory"

#: 相似度阈值（cosine similarity = 1 - distance）。低于不算命中，不硬塞 top_k。
SCORE_THRESHOLD = 0.35

#: 单次召回条数（prompt 注入预算）
DEFAULT_TOP_K = 5

#: 降级用的近因条数（保持改造前行为）
RECENCY_LIMIT = 10

#: query embedding 硬超时（秒）。约束④。
QUERY_EMBED_TIMEOUT = 3.0

_collection = None
_collection_loaded = False
_collection_lock = threading.Lock()


def _get_collection():
    """懒加载 couple_memory collection；失败返回 None，上层降级。"""
    global _collection, _collection_loaded
    if _collection_loaded:
        return _collection
    with _collection_lock:
        if _collection_loaded:
            return _collection
        _collection_loaded = True
        if not os.path.isdir(CHROMA_DIR):
            logger.warning("[MEM-RET] 向量库目录不存在，召回走降级: %s", CHROMA_DIR)
            _collection = None
            return _collection
        try:
            import chromadb

            client = chromadb.PersistentClient(path=CHROMA_DIR)
            _collection = client.get_or_create_collection(
                MEMORY_COLLECTION, metadata={"hnsw:space": "cosine"}
            )
            logger.info(
                "[MEM-RET] couple_memory 就绪 count=%s", _collection.count()
            )
        except Exception as exc:
            logger.warning("[MEM-RET] couple_memory 加载失败，召回走降级: %s", exc)
            _collection = None
    return _collection


def reset_store_for_tests() -> None:
    """测试/回填专用：清掉懒加载缓存。"""
    global _collection, _collection_loaded
    with _collection_lock:
        _collection = None
        _collection_loaded = False


def visibility_filter(user_id: int, relation_id: int):
    """约束①的 SQL 可见性条件（fallback 与向量回表共用）。"""
    return or_(
        and_(AiMemory.user_id == user_id, AiMemory.visibility == "private"),
        and_(AiMemory.relation_id == relation_id, AiMemory.visibility == "couple"),
    )


def _embed_query_fast(text: str, timeout: float = QUERY_EMBED_TIMEOUT) -> Optional[List[float]]:
    """query 向量化，硬超时 timeout 秒；失败返回 None（约束④）。"""
    from app.services.embedding import EMBEDDING_MODEL, embeddings

    if not embeddings.api_key:
        return None
    payload = {
        "model": EMBEDDING_MODEL,
        "input": [text],
        "dimensions": embeddings.dim,
    }
    headers = {
        "Authorization": "Bearer %s" % embeddings.api_key,
        "Content-Type": "application/json",
    }
    url = "%s/embeddings" % embeddings.base_url
    try:
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(url, headers=headers, json=payload)
        if resp.status_code != 200:
            logger.warning("[MEM-RET] query embed HTTP %s", resp.status_code)
            return None
        data = resp.json()["data"]
        data.sort(key=lambda x: x.get("index", 0))
        return data[0]["embedding"]
    except Exception as exc:
        logger.warning("[MEM-RET] query embed 失败/超时(%.1fs): %s", timeout, exc)
        return None


def vectorize_memory_async(memory: Dict[str, Any]) -> None:
    """create_memory 挂钩：后台把新记忆写入 couple_memory（约束②写入侧）。"""
    if not memory or memory.get("id") is None:
        return
    text = (memory.get("memory_text") or "").strip()
    if not text:
        return

    def _worker():
        try:
            col = _get_collection()
            if col is None:
                return
            mid = str(memory["id"])
            existing = col.get(ids=[mid])
            if existing and existing.get("ids"):
                return  # 幂等
            from app.services.embedding import embeddings as emb

            vec = emb.embed_documents([text])[0]
            col.add(
                ids=[mid],
                embeddings=[vec],
                documents=[text],
                metadatas=[{
                    "memory_id": int(memory["id"]),
                    "user_id": int(memory.get("user_id") or 0),
                    "relation_id": int(memory.get("relation_id") or 0),
                    "visibility": memory.get("visibility") or "private",
                    "memory_type": memory.get("memory_type") or "",
                }],
            )
            logger.info("[MEM-RET] 已向量化 memory_id=%s", mid)
        except Exception as exc:
            logger.warning("[MEM-RET] 写入向量失败 id=%s: %s", memory.get("id"), exc)

    threading.Thread(target=_worker, name="mem-vectorize", daemon=True).start()


def _fallback_recent(
    db: Session, user_id: int, relation_id: int, limit: int = RECENCY_LIMIT
) -> List[Dict[str, Any]]:
    """降级：可见性过滤后的最近 N 条。"""
    rows = (
        db.query(AiMemory)
        .filter(
            and_(AiMemory.relation_id == relation_id, visibility_filter(user_id, relation_id))
        )
        .order_by(AiMemory.created_at.desc())
        .limit(limit)
        .all()
    )
    return _finalize(rows, user_id, {})


def _finalize(
    rows: List[AiMemory], viewer_user_id: int, scores: Dict[int, float]
) -> List[Dict[str, Any]]:
    items = []
    for m in rows:
        items.append({
            "id": m.id,
            "content": m.memory_text,
            "source": m.memory_type or "",
            "created_at": m.created_at.isoformat() if m.created_at else None,
            "from_partner": m.user_id != viewer_user_id,
            "score": scores.get(m.id),
        })
    return items


def _query_scores(col, query_vec: List[float], n: int) -> Dict[int, float]:
    """memory_id → cosine similarity（1 - distance）。"""
    res = col.query(
        query_embeddings=[query_vec],
        n_results=max(n, 1),
        include=["distances", "metadatas"],
    )
    ids = (res.get("ids") or [[]])[0]
    dists = (res.get("distances") or [[]])[0]
    metas = (res.get("metadatas") or [[]])[0]
    scores: Dict[int, float] = {}
    for i, _mid in enumerate(ids):
        if i >= len(dists):
            break
        meta = metas[i] if i < len(metas) else {}
        mem_id = (meta or {}).get("memory_id")
        if mem_id is None:
            continue
        try:
            scores[int(mem_id)] = round(1.0 - float(dists[i]), 4)
        except (TypeError, ValueError):
            continue
    return scores


def retrieve_memory_items(
    db: Session,
    user_id: int,
    relation_id: int,
    query: Optional[str] = None,
    limit: int = DEFAULT_TOP_K,
) -> List[Dict[str, Any]]:
    """相关性召回主入口。prompt 与 evidence **共用本函数**（约束③）。

    路径：
      1. query 为空 → 近因降级
      2. embedding 3s 超时/失败 → 近因降级（约束④，不抛错）
      3. 向量命中且 similarity ≥ SCORE_THRESHOLD → 按分返回
         （**不与近因合并**——约束②：降级≠合并）
      4. 向量无达标命中 / collection 不可用 → 近因降级

    可见性在 SQL 层统一套 visibility_filter：向量候选回表再过滤，
    chroma metadata 与库不一致时也不会越权（约束①红线）。
    """
    q = (query or "").strip()
    if not q:
        return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)

    col = _get_collection()
    if col is None:
        return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)

    try:
        if col.count() == 0:
            return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)
    except Exception as exc:
        logger.warning("[MEM-RET] count 失败，降级: %s", exc)
        return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)

    query_vec = _embed_query_fast(q, QUERY_EMBED_TIMEOUT)
    if query_vec is None:
        return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)

    try:
        scores = _query_scores(col, query_vec, max(limit * 3, 15))
    except Exception as exc:
        logger.warning("[MEM-RET] 向量检索失败，降级: %s", exc)
        return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)

    hit_ids = [mid for mid, sc in scores.items() if sc >= SCORE_THRESHOLD]
    if not hit_ids:
        return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)

    rows = (
        db.query(AiMemory)
        .filter(
            AiMemory.id.in_(hit_ids),
            visibility_filter(user_id, relation_id),
        )
        .all()
    )
    if not rows:
        return _fallback_recent(db, user_id, relation_id, limit=RECENCY_LIMIT)

    rows.sort(key=lambda m: scores.get(m.id, 0.0), reverse=True)
    return _finalize(rows[:limit], user_id, scores)


def format_memory_context(items: List[Dict[str, Any]]) -> str:
    """召回结果 → prompt 片段。伴侣记忆标「TA 曾说过…」（约束①）。"""
    if not items:
        return ""
    lines = []
    for m in items:
        prefix = "TA 曾说过：" if m.get("from_partner") else ""
        lines.append(
            "- [%s] %s%s" % (m.get("source") or "记忆", prefix, m.get("content") or "")
        )
    return "## AI 记忆\n" + "\n".join(lines)
