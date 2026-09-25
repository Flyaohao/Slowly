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

embedding 纪律（v3.2 §5.1/§5.2）：
  query 向量化硬超时 0.4s，与关键词通道**并行**（总预算 ≤800ms）；失败/超时
  只置空向量通道并**继续关键词通道**（绝不抛错、不拖首字）。是否降级近因由
  memory_need 路由表决定——近因是 recent_context 的正路与 query 为空的契约，
  **不是** personal_fact/history 的兜底（无命中本身就是有效答案）。

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
import math
import os
import re
import threading
from datetime import datetime
from typing import Any, Dict, List, Optional, Set, Tuple

import httpx
from sqlalchemy import and_, func, or_
from sqlalchemy.orm import Session

from app.core import config
from app.models.ai import AiMemory

logger = logging.getLogger("couple.memory_retrieval")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CHROMA_DIR = config.CHROMA_DIR or os.path.join(BASE_DIR, "data", "chroma")
MEMORY_COLLECTION = "couple_memory"

# ---------------------------------------------------------------- #
# v3.2 §5.1 两段式：门槛层（原始分，绝对值）→ 排序层（名次信号，只管先后）
# ---------------------------------------------------------------- #

#: 门槛层：cosine 原始分下限（判「够不够相关」）。0.35 仅作起点候选值，
#: 最终在标注集上校准（阶段 C）。**不得**拿归一化后的排序分当相关度（红线5）。
SEMANTIC_FLOOR = 0.35
#: 门槛层：关键词覆盖率原始分下限。
LEXICAL_FLOOR = 0.35
#: 兼容旧名（既有测试与调用方引用 SCORE_THRESHOLD/KEYWORD_MIN_SCORE）
SCORE_THRESHOLD = SEMANTIC_FLOOR
KEYWORD_MIN_SCORE = LEXICAL_FLOOR

#: 单次召回条数（prompt 注入预算）
DEFAULT_TOP_K = 5

# ---------------------------------------------------------------- #
# P-C2 §4：混合召回（向量通道 + 零依赖关键词通道 + RRF 融合）
# ---------------------------------------------------------------- #

#: RRF 常数（Reciprocal Rank Fusion，§4.2）
RRF_K = 60

#: 排序层权重（v3.2 §5.1）：0.55·rank + 0.20·time + 0.15·importance + 0.10·scene
RANK_WEIGHT = 0.55
TIME_DECAY_WEIGHT = 0.20
TIME_DECAY_DAYS = 90.0
IMPORTANCE_WEIGHT = 0.15
SCENE_WEIGHT = 0.10
OVERLAP_WEIGHT = 0.30

#: 回声惩罚线性窗（v3.2 echo 连续化，消除 0/1 跳变）：
#:   echo = 0.30 × overlap × max(0, 1 − 天数/3)
#: 6% 重合当天罚 ≈ 0.018，100% 当天罚 ≈ 0.30，3 天线性归零。
#: 老记忆与本轮输入高重合是**期望召回**（专有名词、query=记忆原文），不罚。
ECHO_DECAY_DAYS = 3.0

#: 回声惩罚噪声地板：2-gram 空间里两句无关中文天然共享 2~4% 词项，
#: 重合度低于此值视为**巧合**（共享话题词）不罚；达到即按线性全额罚
#: （exp(-天数/3) 老化门照旧，逐字回声仍吃满 0.30）。
#: 偏离说明（两支既有断言实测定值，窗口 0.045~0.074）：
#:   - 无地板（线性全额罚）→ case_cross_relation_isolation 挂：own 行
#:     ov=0.273 被罚 0.082，低于天然级差 ±0.01，被挤出 top-5；
#:   - 地板取 0.5 → case_backfill_idempotent_and_old_recallable 挂：
#:     新鲜陪跑行（ov 0.03~0.07）失去惩罚后时间分占优，把 11 天前的
#:     精确命中行（sim=1.0 + 关键词双通道）顶到 13/15。
#: 0.06 同时满足两支：own385(0.043) 免罚、backfill 第 7 名(0.074) 仍罚。
MIN_ECHO_OVERLAP = 0.06

#: 场景加权只给冲突场景下的高优事件（importance>=1）
SCENE_BOOST_KEYS = ("cold_war", "mediation")

#: 关键词通道：扫描上限（按事件时间取最新 N 条防全表）。
#: 候选门槛 = LEXICAL_FLOOR（原始分，与向量通道同在**门槛层**）。
KEYWORD_SCAN_LIMIT = 500

#: 抽词停用词（零依赖分词：标点/空白切分 + 2-gram，**不装 jieba**——红线③）
_STOPWORDS = frozenset(
    """
    的 了 是 在 有 和 与 就 都 也 还 又 很 太 不 没 别 把 被 让 给 对 说 吗 呢
    吧 啊 呀 哦 嗯 那 这 但 而 因 为 所 以 什么 怎么 怎样 一个 一下 一直 还是
    可以 自己 现在 好像 如果 虽然 然后 但是 而且 我们 你们 他们 她们 它们 上
    下 里 外 时候 会 想 要 走 来 去 好 吧 他 她 它 我 你
    """.split()
)

#: 切分：空白与中英文标点（短语边界）
_SPLIT_RE = re.compile(
    r"[\s,，。！？!?、；;：:\"'“”‘’（）()【】\[\]<>《》…·\-—_]+"
)

#: `_fallback_recent` 的默认近因条数。P-B 起 `retrieve_memory_items` 的
#: 降级路径与向量路径一样显式传调用方的 limit（修复：降级曾无视 limit 固定
#: 返回 10 条，导致 chat_mode 档位矩阵在降级环境失效、同源断言对不上）。
RECENCY_LIMIT = 10

#: query embedding 硬超时（秒）。v3.1 §3 并行通道 400ms 窗——向量与关键词
#: 并行跑，总预算 ≤800ms；超窗未归的向量线程按通道失败处理（结果不再可信）。
QUERY_EMBED_TIMEOUT = 0.4

_collection = None
_collection_loaded = False
_collection_lock = threading.Lock()


def _get_collection():
    """懒加载 couple_memory collection；失败返回 None，上层降级。"""
    global _collection, _collection_loaded
    # 快路径也必须进锁：锁外读会撞上「_collection_loaded 已置 True、
    # _collection 仍为 None」的加载中间态——向量线程拿到 None 后静默放弃，
    # 该记忆永久缺向量（P-C1 C10 首轮失败与孤儿向量的根因）。
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


def _viewer_state_clause(viewer_user_id: int):
    """v3.2 §6.1 查看者维度的用户级状态过滤——委托统一实现（D5）。

    `memory_user_state_service.viewer_state_filter_clause` 是三处查询点的
    唯一实现（NOT EXISTS、空表=无操作、单方 mute/hide 不影响另一方）。
    """
    from app.services.memory_user_state_service import viewer_state_filter_clause

    return viewer_state_filter_clause(viewer_user_id)


def _relation_is_active(db: Session, relation_id: int) -> bool:
    """附录 §5.1 AI_RECALL 硬边界：仅 relation.status='active' 允许召回。

    一次索引查；管理读（get_memories 等）不走本函数，unbinding/dissolved
    仍可管理自己的 private（附录 §5.2 MEMORY_MANAGEMENT）。
    """
    from app.models.couple_relation import CoupleRelation

    row = (
        db.query(CoupleRelation.status)
        .filter(CoupleRelation.id == relation_id)
        .first()
    )
    return bool(row) and row[0] == "active"


def _route_by_memory_need(
    memory_need: str,
    *,
    query_empty: bool,
    has_candidates: bool,
    vec_ok: bool = True,
    kw_ok: bool = True,
) -> str:
    """v3.2 §5.2 降级×路由统一表 → 'candidates' | 'recency' | 'empty'。

    规则一句话：**近因是 recent_context 的正路与 query 为空的契约，不是
    personal_fact/history 的兜底**——无命中本身就是「没有相关记忆」的有效答案。
    失败与成功的区别只在 recent_context（两者都走近因）；对 personal_fact/
    history 两者都返回空（宁可漏记），vec_ok/kw_ok 仅为表列完整性保留。
    """
    if memory_need == "none":
        return "empty"
    if query_empty:
        return "recency"  # Agent 无参契约（none 已在上拦截）
    if has_candidates:
        return "candidates"
    if memory_need == "recent_context":
        return "recency"  # 新鲜度就是本需求：空/失败都走近因
    # personal_fact / relationship_history：双成功空 = 空；任一失败也 = 空
    _ = (vec_ok, kw_ok)
    return "empty"


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
            # 与 delete_memory 的竞态：异步 embed 期间行可能已被删——
            # 落向量前再查一次 DB，行不在则放弃（否则删完又冒出孤儿向量）
            try:
                from app.core.database import SessionLocal
                from app.models.ai import AiMemory as _M

                _db = SessionLocal()
                try:
                    if _db.get(_M, int(memory["id"])) is None:
                        logger.info("[MEM-RET] 行已删除，跳过向量化 id=%s", mid)
                        return
                finally:
                    _db.close()
            except Exception:
                pass  # 查库失败不阻断写入（以幂等 get 为准）
            from app.services.embedding import embeddings as emb

            vec = emb.embed_documents([text])[0]
            metadata = {
                "memory_id": int(memory["id"]),
                "user_id": int(memory.get("user_id") or 0),
                "relation_id": int(memory.get("relation_id") or 0),
                "visibility": memory.get("visibility") or "private",
                "memory_type": memory.get("memory_type") or "",
                # P-C1 §3.4：事件三键——occurred_at 用 ISO 字符串；
                # Chroma metadata 不接受 None，缺失键一律不写
                "importance": int(memory.get("importance") or 0),
            }
            if memory.get("source"):
                metadata["source"] = memory["source"]
            if memory.get("occurred_at"):
                metadata["occurred_at"] = memory["occurred_at"]
            col.add(
                ids=[mid],
                embeddings=[vec],
                documents=[text],
                metadatas=[metadata],
            )
            logger.info("[MEM-RET] 已向量化 memory_id=%s", mid)
        except Exception as exc:
            logger.warning("[MEM-RET] 写入向量失败 id=%s: %s", memory.get("id"), exc)

    threading.Thread(target=_worker, name="mem-vectorize", daemon=True).start()


def _fallback_recent(
    db: Session, user_id: int, relation_id: int, limit: int = RECENCY_LIMIT
) -> List[Dict[str, Any]]:
    """降级：可见性过滤后的最近 N 条——按**事件时间**排（不变量④）。

    P-C1 §7.3：排序列 = occurred_at DESC，NULL 回退 created_at（coalesce）。
    v3.2 §6.1：只取全局 status='active' 且查看者未 hidden/muted 的行。
    """
    from sqlalchemy import func

    rows = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.relation_id == relation_id,
                AiMemory.status == "active",
                visibility_filter(user_id, relation_id),
                _viewer_state_clause(user_id),
            )
        )
        .order_by(
            func.coalesce(AiMemory.occurred_at, AiMemory.created_at).desc(),
            AiMemory.id.desc(),
        )
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
            # P-C1 §7.1：source 升级为**真实来源列**（letter/diary/…）；
            # 原 memory_type 别名语义挪到 memory_type 键（旧键不删、语义不丢）
            "source": m.source or "",
            "memory_type": m.memory_type or "",
            # 只加不删：三新键，_finalize 与降级出口共用本函数
            "occurred_at": m.occurred_at.isoformat() if m.occurred_at else None,
            "importance": m.importance or 0,
            "created_at": m.created_at.isoformat() if m.created_at else None,
            "from_partner": m.user_id != viewer_user_id,
            "score": scores.get(m.id),
        })
    return items


def _query_scores(col, query_vec: List[float], n: int, relation_id: int) -> Dict[int, float]:
    """memory_id → cosine similarity（1 - distance）。

    必须带 where relation_id（缺陷一）：couple_memory 是全局单 collection，
    不过滤时别情侣的相似记忆会占满 n_results 候选位，把本 relation 的命中
    挤出 → hit_ids 空 → 静默降级，召回质量随机下降。
    """
    res = col.query(
        query_embeddings=[query_vec],
        n_results=max(n, 1),
        where={"relation_id": int(relation_id)},
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


# ---------------------------------------------------------------- #
# Stage 2（P-C2 §3）：画像驱动 query
# ---------------------------------------------------------------- #

def build_profile_query(user_input: str, profile_keywords: Optional[List[str]]) -> str:
    """画像驱动 query = 本轮输入 + 画像关键词（空格分隔）。

    空格让每个关键词在关键词通道里各自成为独立短语（整段命中权重 2），
    同时整体参与向量检索的语义扩展。
    """
    base = (user_input or "").strip()
    kws = [k.strip() for k in (profile_keywords or []) if k and k.strip()]
    if not kws:
        return base
    return (base + " " + " ".join(kws)).strip()


def build_profile_keywords(
    db: Session,
    user_id: int,
    relation_id: int,
    conflict_pattern: Optional[str] = None,
    profile_type: Optional[str] = None,
    partner_profile_type: Optional[str] = None,
    event_limit: int = 3,
) -> List[str]:
    """画像关键词四源（P-C2 §3）：冲突模式 + 依恋类型名 + 近期重要事件标题词
    + 性格辅助（星座 / MBTI / 出生地）。

    - 冲突模式：couple_prof.conflict_pattern（取不到跳过，**不塞占位**）；
    - 依恋类型名：PROFILE_TYPE_LABELS[profile_type]（用户 + 伴侣，重复跳过）；
    - 近期重要事件：relation 内 importance>=1 按事件时间取最新 event_limit 条，
      标题词 = memory_text 前 12 字（成为 query 短语，权重 2）。
    - 性格辅助：user_profile 的星座、MBTI 与出生地（没填就跳过，同「不塞占位」）——
      让「TA 是天蝎座 / INTJ / 杭州人」这类记忆也能被画像驱动 query 召回；
      出生地用城市表命中的规范短名（"杭州"），命中不了就跳过。
    事件查询同样套 visibility_filter + relation_id 双保险（红线④）。
    """
    from app.repositories import user_repo
    from app.services.astrology_service import get_zodiac
    from app.services.birthplace_service import lookup_birth_place
    from app.services.profile_service import PROFILE_TYPE_LABELS

    keywords: List[str] = []
    if conflict_pattern and conflict_pattern.strip() and conflict_pattern.strip() != "未确定":
        keywords.append(conflict_pattern.strip())
    for ptype in (profile_type, partner_profile_type):
        label = PROFILE_TYPE_LABELS.get(ptype or "")
        if label and label not in keywords:
            keywords.append(label)

    basic = user_repo.get_profile_by_user_id(db, user_id)
    if basic is not None:
        zodiac = get_zodiac(basic.birthday)
        if zodiac:
            keywords.append(f"{zodiac}座")
        mbti = (basic.mbti or "").strip().upper()
        if mbti and mbti not in keywords:
            keywords.append(mbti)
        place = lookup_birth_place(basic.birth_place)
        if place is not None and place.name not in keywords:
            keywords.append(place.name)

    rows = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.relation_id == relation_id,
                AiMemory.status == "active",
                visibility_filter(user_id, relation_id),
                _viewer_state_clause(user_id),
                AiMemory.importance >= 1,
            )
        )
        .order_by(func.coalesce(AiMemory.occurred_at, AiMemory.created_at).desc())
        .limit(event_limit)
        .all()
    )
    for m in rows:
        title = (m.memory_text or "").strip()[:12]
        if title and title not in keywords:
            keywords.append(title)
    return keywords


# ---------------------------------------------------------------- #
# Stage 3 通道二（P-C2 §4.1）：零依赖关键词召回
# ---------------------------------------------------------------- #

def _extract_terms(text: str) -> Tuple[Set[str], Set[str]]:
    """抽词：标点/空白切分整段短语（2~12 字，去停用词）+ 全部 2-gram。

    零依赖——不装 jieba（红线③）。专有名词（人名、「第一次看海」、
    纪念日名）靠「整段短语在 memory_text 里整体命中（权重 2）」捕捉，
    2-gram 兜底未分词的长句。
    """
    segments = [s for s in _SPLIT_RE.split(text or "") if s]
    full = {s for s in segments if 2 <= len(s) <= 12 and s not in _STOPWORDS}
    grams: Set[str] = set()
    for seg in segments:
        for i in range(len(seg) - 1):
            g = seg[i : i + 2]
            if g not in _STOPWORDS:
                grams.add(g)
    return full, grams


def _keyword_score(memory_text: str, q_full: Set[str], q_grams: Set[str]) -> float:
    """命中词数 / 加权：整段短语 2 分、2-gram 1 分，分母为 query 侧总量。"""
    denom = 2 * len(q_full) + len(q_grams)
    if denom <= 0 or not memory_text:
        return 0.0
    hit = 2 * sum(1 for t in q_full if t in memory_text)
    hit += sum(1 for g in q_grams if g in memory_text)
    return round(hit / denom, 4)


def _keyword_scores(
    db: Session,
    relation_id: int,
    user_id: int,
    query: str,
    limit: int = KEYWORD_SCAN_LIMIT,
) -> Dict[int, float]:
    """关键词通道主函数：对可见记忆的 memory_text 计分 → {memory_id: score}。

    - **必须套 visibility_filter**（红线①，唯一可见性入口）+ relation_id；
    - v3.2 §6.1：只扫全局 status='active' 且查看者未 hidden/muted 的行；
    - 按 coalesce(occurred_at, created_at) DESC 取最新 limit 行做扫描上限，
      防全表扫描（与降级排序同口径，MySQL 无 NULLS LAST）；
    - 候选门槛 LEXICAL_FLOOR 在调用侧（retrieve）统一过滤。
    """
    q = (query or "").strip()
    if not q:
        return {}
    q_full, q_grams = _extract_terms(q)
    if not q_full and not q_grams:
        return {}
    rows = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.relation_id == relation_id,
                AiMemory.status == "active",
                visibility_filter(user_id, relation_id),
                _viewer_state_clause(user_id),
            )
        )
        .order_by(func.coalesce(AiMemory.occurred_at, AiMemory.created_at).desc())
        .limit(limit)
        .all()
    )
    scores: Dict[int, float] = {}
    for m in rows:
        text = (m.memory_text or "").strip()
        if len(text) < 2:
            continue
        sc = _keyword_score(text, q_full, q_grams)
        if sc > 0:
            scores[m.id] = sc
    return scores


# ---------------------------------------------------------------- #
# Stage 3 融合（P-C2 §4.2/§4.3）
# ---------------------------------------------------------------- #

def _overlap_ratio(text: str, against: str) -> float:
    """text 的词项中出现在 against 里的占比（containment，回声惩罚用）。"""
    t_full, t_grams = _extract_terms(text)
    terms = t_full | t_grams
    if not terms:
        return 0.0
    a_full, a_grams = _extract_terms(against)
    ref = a_full | a_grams
    if not ref:
        return 0.0
    return len(terms & ref) / len(terms)


def _fuse_scores(
    rows: List[AiMemory],
    vec_cand: Dict[int, float],
    kw_cand: Dict[int, float],
    *,
    echo_source: str,
    scene_key: Optional[str] = None,
) -> Dict[int, float]:
    """排序层（v3.2 §5.1）：0.55·rank_score + 0.20·time + 0.15·imp + 0.10·scene − echo。

    **门槛与排序各司其职**：能否进候选由门槛层的原始分 floor 决定
    （SEMANTIC_FLOOR / LEXICAL_FLOOR），本函数只回答「谁先谁后」。
    RRF 折成 [0,1] 的 rank_score（÷ **本轮有候选通道数** × 单通道上限
    1/(60+1)，查询级统一分母），**不做轮内最大值归一**——归一化会让
    「唯一弱候选」恒得 1.0 冒充相关度（v3.2 修 #6 撤回该做法）。
    统一分母保证排序与原始 rrf 完全同序 → **双通道共识行恒居首**
    （按行各自归一会把单通道 rank1 抬到 1.0 反超共识行，不行）。

        rrf(m)  = Σ_channels 1/(60 + rank_channel(m))
        rank(m) = rrf(m) ÷ (有候选通道数 × 1/(60+1))     ∈ (0, 1]
        final   = 0.55·rank + 0.20·exp(−天数/90) + 0.15·(importance/2)
                  + 0.10·场景加权(cold_war/mediation 的冲突事件) − echo
        echo    = 0.30 × overlap × max(0, 1 − 天数/3)   （overlap < 0.06 不罚）

    回声惩罚的参照是**本轮用户原话**（echo_source，不含画像关键词——
    关键词不是用户说过的话）；检索层不持会话历史，「最近 3 条消息」
    以本轮输入近似（偏离留痕）。
    """
    now = datetime.now()
    vec_rank = {mid: i for i, mid in enumerate(sorted(
        vec_cand, key=lambda k: vec_cand[k], reverse=True
    ), start=1)}
    kw_rank = {mid: i for i, mid in enumerate(sorted(
        kw_cand, key=lambda k: kw_cand[k], reverse=True
    ), start=1)}
    #: 单通道理论上限（rank=1 时的 RRF 值）
    rrf_cap = 1.0 / (RRF_K + 1)
    #: 查询级统一分母：本轮**有候选**的通道数（无候选的通道不计入，
    #: 否则单通道场景所有行被无谓腰斩且仍非 1.0）
    n_total_channels = (1 if vec_cand else 0) + (1 if kw_cand else 0)

    finals: Dict[int, float] = {}
    for m in rows:
        rrf = 0.0
        if m.id in vec_rank:
            rrf += 1.0 / (RRF_K + vec_rank[m.id])
        if m.id in kw_rank:
            rrf += 1.0 / (RRF_K + kw_rank[m.id])
        if rrf <= 0 or n_total_channels == 0:
            continue  # 行在候选集但两通道都无排名（防御，不会发生）
        rank_score = rrf / (n_total_channels * rrf_cap)  # ∈ (0, 1]
        ts = m.occurred_at or m.created_at
        age_days = max((now - ts).total_seconds() / 86400.0, 0.0) if ts else 0.0
        time_part = TIME_DECAY_WEIGHT * math.exp(-age_days / TIME_DECAY_DAYS)
        importance_part = IMPORTANCE_WEIGHT * ((m.importance or 0) / 2.0)
        scene_part = (
            SCENE_WEIGHT
            if scene_key in SCENE_BOOST_KEYS and (m.importance or 0) >= 1
            else 0.0
        )
        # 回声惩罚连续化（v3.2 修 #6）：3 天线性归零，消除 0/1 跳变；
        # 重合度 < 噪声地板视为巧合不罚（MIN_ECHO_OVERLAP 处的偏离说明）。
        overlap = _overlap_ratio(m.memory_text or "", echo_source)
        echo_pen = (
            OVERLAP_WEIGHT
            * overlap
            * max(0.0, 1.0 - age_days / ECHO_DECAY_DAYS)
            if overlap >= MIN_ECHO_OVERLAP
            else 0.0
        )
        finals[m.id] = (
            RANK_WEIGHT * rank_score
            + time_part
            + importance_part
            + scene_part
            - echo_pen
        )

    return {mid: round(v, 4) for mid, v in finals.items()}


def _dedup_by_source(rows: List[AiMemory]) -> List[AiMemory]:
    """同 (source, source_id) 只留一条（v3.2 修 #6：去重键带 source 区分维度）。

    输入须已按（分降序，同分蒸馏行在前）排好——首见即该组应留的行。
    source_id 为 NULL 的行不去重。蒸馏行/事件行的偏好在调用方排序里做
    （EVENT_MEMORY_TYPE 懒导入，避免与 memory_service 循环依赖）。
    """
    out: List[AiMemory] = []
    seen: Set[Tuple[str, int]] = set()
    for m in rows:
        sid = getattr(m, "source_id", None)
        if sid is None:
            out.append(m)
            continue
        key = (getattr(m, "source", None) or "", sid)
        if key in seen:
            continue
        seen.add(key)
        out.append(m)
    return out


def retrieve_memory_items(
    db: Session,
    user_id: int,
    relation_id: int,
    query: Optional[str] = None,
    limit: int = DEFAULT_TOP_K,
    *,
    profile_keywords: Optional[List[str]] = None,
    scene_key: Optional[str] = None,
    memory_need: str = "personal_fact",
) -> List[Dict[str, Any]]:
    """相关性召回主入口。prompt 与 evidence **共用本函数**（约束③）。

    v3.2 §5.1 两段式混合召回：

      Stage 2  query = 本轮输入 + 画像关键词（profile_keywords，§3）
      Stage 3  通道一向量（原始 cosine ≥ SEMANTIC_FLOOR，0.4s 并行窗）
               + 通道二关键词（原始覆盖率 ≥ LEXICAL_FLOOR）
               → 排序层 0.55·rank + 0.20·time + 0.15·imp + 0.10·scene − echo
               → (source, source_id) 去重 → limit
               是否降级近因由 **memory_need 路由表**决定（§5.2），不再
               「通道全空一律近因」——近因是 recent_context 的正路与
               query 为空的契约，不是 personal_fact/history 的兜底。

    不变量（§4.4/§5.1，改检索也不能破）：
      1. visibility_filter 唯一入口 + 回表 relation_id/status/user_state
         复滤 + relation.status='active' 硬边界（附录 §5.1/红线④）；
      2. embedding 失败/超时 → **只置空向量通道并继续关键词**（不近因、
         不抛错、不拖首字）；关键词异常同理置空；
      3. 门槛（原始分 floor）与排序分离——排序分永不当相关度过滤；
      4. 近因降级按 coalesce(occurred_at, created_at) DESC（MySQL 无
         NULLS LAST）。

    签名向后兼容：默认 limit=5 不变，新参数仅 keyword-only
    （memory_need 默认 personal_fact = 旧「有则取」语义的保守档）。
    **所有路径（含降级）都按 limit 截断**——prompt 侧与 evidence 侧拿到的
    条数与顺序因此完全一致（约束③的同源断言依赖这一点）。

    返回 dict 旧键一个不删（_finalize 为直排/融合两出口共用）。
    """
    # memory_need=none：本轮不召回（§5.2 路由表首行，服务端字段不可被
    # 模型/用户指定——由调用方从场景配置读）
    if memory_need == "none":
        return []
    # AI_RECALL 硬边界：仅 active 关系允许召回（附录 §5.1）。管理读
    # （get_memories 等）不走本函数，unbinding 仍可管理自己的 private。
    if not _relation_is_active(db, relation_id):
        return []

    raw_query = (query or "").strip()
    q = build_profile_query(raw_query, profile_keywords)
    if profile_keywords:
        # DoD#4 留痕：画像关键词确实进入检索 query
        logger.info(
            "[MEM-RET] 画像驱动 query keywords=%s effective=%s",
            profile_keywords, q,
        )
    if not q:
        # query 空（Agent 无参契约/纯画像关键词未拼出词）→ 按路由表走：
        # recent_context → 近因；personal_fact/history → 空
        if (
            _route_by_memory_need(memory_need, query_empty=True, has_candidates=False)
            == "recency"
        ):
            return _fallback_recent(db, user_id, relation_id, limit=limit)
        return []

    # ---- 通道一向量（独立线程，0.4s embed 窗；§5.1 预算 ≤800ms）----
    # embedding 文本仍只以 memory_text 检索口径为准（查询侧 q 已含画像关键词）。
    # 结果收进 box 再原子换出——join 超时后线程晚到的写入不污染本线程。
    vec_box: Dict[str, Any] = {"scores": {}, "ok": True}

    def _vector_channel() -> None:
        try:
            col = _get_collection()
            if col is None:
                vec_box["ok"] = False  # chroma 不可用 = 通道失败（非「无命中」）
                return
            try:
                have_vectors = col.count() > 0
            except Exception as exc:
                logger.warning("[MEM-RET] count 失败: %s", exc)
                have_vectors = False
            if not have_vectors:
                return  # 库空：通道成功但无候选
            query_vec = _embed_query_fast(q, QUERY_EMBED_TIMEOUT)
            if query_vec is None:
                vec_box["ok"] = False  # 失败/超时 → 置空本通道，关键词照跑
                return
            vec_box["scores"] = _query_scores(
                col, query_vec, max(limit * 3, 15), relation_id
            )
        except Exception as exc:
            logger.warning("[MEM-RET] 向量通道异常，本通道置空: %s", exc)
            vec_box["ok"] = False
            vec_box["scores"] = {}

    vec_thread = threading.Thread(target=_vector_channel, daemon=True)
    vec_thread.start()

    # ---- 通道二：关键词（零依赖 2-gram/短语，当前线程与向量并行）----
    kw_ok = True
    try:
        keyword_scores = _keyword_scores(db, relation_id, user_id, q)
    except Exception as exc:
        logger.warning("[MEM-RET] 关键词通道异常，本通道置空: %s", exc)
        keyword_scores = {}
        kw_ok = False

    vec_thread.join(timeout=QUERY_EMBED_TIMEOUT + 0.5)
    if vec_thread.is_alive():
        vec_ok = False  # 超预算仍未返回 → 按失败计（线程放弃，不等它）
        vector_scores: Dict[int, float] = {}
    else:
        vec_ok = bool(vec_box["ok"])
        vector_scores = vec_box["scores"]

    # ---- 门槛层：通道准入用**原始分** floor（不做轮内归一）----
    vec_cand = {mid: sc for mid, sc in vector_scores.items() if sc >= SEMANTIC_FLOOR}
    kw_cand = {mid: sc for mid, sc in keyword_scores.items() if sc >= LEXICAL_FLOOR}

    # ---- 路由层（§5.2）：近因还是空，由 memory_need 说了算 ----
    route = _route_by_memory_need(
        memory_need,
        query_empty=False,
        has_candidates=bool(vec_cand or kw_cand),
        vec_ok=vec_ok,
        kw_ok=kw_ok,
    )
    if route == "recency":
        return _fallback_recent(db, user_id, relation_id, limit=limit)
    if route == "empty":
        if not (vec_cand or kw_cand):
            logger.info(
                "[MEM-RET] memory_need=%s 无候选 → 返回空（非近因）"
                " vec_ok=%s kw_ok=%s",
                memory_need, vec_ok, kw_ok,
            )
        return []

    # 缺陷二：回表必须同时约束 relation——visibility_filter 的 private 支
    # 只看 user_id，不看 relation；解绑再绑定后旧关系自己的 private 会漏进来。
    # + status='active' + user_state 隐藏/静音复滤（D5：空表=无操作）。
    hit_ids = sorted(set(vec_cand) | set(kw_cand))
    rows = (
        db.query(AiMemory)
        .filter(
            AiMemory.id.in_(hit_ids),
            AiMemory.relation_id == relation_id,
            AiMemory.status == "active",
            visibility_filter(user_id, relation_id),
            _viewer_state_clause(user_id),
        )
        .all()
    )
    if not rows:
        # 通道有候选但全部被可见性/状态滤掉 = 对用户而言「无相关记忆」
        if (
            _route_by_memory_need(
                memory_need, query_empty=False, has_candidates=False,
                vec_ok=vec_ok, kw_ok=kw_ok,
            )
            == "recency"
        ):
            return _fallback_recent(db, user_id, relation_id, limit=limit)
        return []

    fused = _fuse_scores(
        rows, vec_cand, kw_cand, echo_source=raw_query, scene_key=scene_key
    )
    # 排序层只管先后：过了通道 floor 的行不再拿融合分二次过滤（红线5）
    kept = [m for m in rows if m.id in fused]
    if not kept:
        if (
            _route_by_memory_need(
                memory_need, query_empty=False, has_candidates=False,
                vec_ok=vec_ok, kw_ok=kw_ok,
            )
            == "recency"
        ):
            return _fallback_recent(db, user_id, relation_id, limit=limit)
        return []

    # 排序：分降序；同分优先蒸馏行（非事件行）——_dedup_by_source 首见即留
    from app.services.memory_service import EVENT_MEMORY_TYPE

    kept.sort(
        key=lambda m: (
            fused.get(m.id, 0.0),
            1 if (m.memory_type or "") != EVENT_MEMORY_TYPE else 0,
        ),
        reverse=True,
    )
    kept = _dedup_by_source(kept)[:limit]
    return _finalize(kept, user_id, {m.id: fused[m.id] for m in kept})


def format_memory_context(items: List[Dict[str, Any]]) -> str:
    """召回结果 → prompt 片段。伴侣记忆标「TA 曾说过…」（约束①）。

    P-C1 §7.2：每条加「（{source}·{日期}）」前缀——让模型知道这是什么、
    什么时候的事。日期取 occurred_at，无则 created_at，格式 YYYY-MM-DD。
    """
    if not items:
        return ""
    lines = []
    for m in items:
        prefix = "TA 曾说过：" if m.get("from_partner") else ""
        label = m.get("memory_type") or m.get("source") or "记忆"
        tag = ""
        src = m.get("source") or ""
        date = (m.get("occurred_at") or m.get("created_at") or "")[:10]
        if src and date:
            tag = "（%s·%s）" % (src, date)
        elif date:
            tag = "（%s）" % date
        lines.append(
            "- [%s] %s%s%s" % (label, tag, prefix, m.get("content") or "")
        )
    return "## AI 记忆\n" + "\n".join(lines)
