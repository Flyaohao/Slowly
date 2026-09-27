import logging
import os
import threading
from typing import Optional, List, Dict
from datetime import datetime

from sqlalchemy.orm import Session
from sqlalchemy import and_, func

from app.models.ai import AiMemory
from app.core.config import AI_MEMORY_MODEL
from app.services.llm_client import llm, LlmClient, LlmError
from app.services.prompt_builder import MEMORY_DISTILL_PROMPT, SESSION_SUMMARY_PROMPT

logger = logging.getLogger("couple.memory")

#: 记忆抽取是每轮对话都要跑一次的后台任务，本质是「二分类 + 一句话抽取」。
#: 用主模型（推理模型）实测单次约 10s / 2000+ token；换轻量模型后判断结果
#: 与主模型一致，但快约 20 倍。故这一条链路单独指定模型，不跟随主模型。
#: 注意：模型只影响速度与成本，输出结构仍由 scene=memory_distill 的
#: Pydantic 模型约束，因此换模型不会改变落库字段契约。
distill_llm = LlmClient(model=AI_MEMORY_MODEL, fallbacks=[])

#: LLM 可能自由发挥，落库前收口到这几类
MEMORY_TYPES = ("偏好", "关系事实", "沟通雷区", "核心诉求")
_DEFAULT_MEMORY_TYPE = "关系事实"

#: P-C1 §2：直写路径专用 memory_type 登记。
#: ⚠️ 这两个**不进 MEMORY_TYPES**——那是 AI 萃取输出的白名单
#: （memory_service 落库校验处越界会回退「关系事实」，加进去模型会乱标）。
EVENT_MEMORY_TYPE = "事件"              # 事件行（§4 无条件先写）
SESSION_SUMMARY_MEMORY_TYPE = "session_summary"  # 会话归档摘要（已在写，补登记）

#: 太短的输入没有可沉淀的信息，直接跳过，省一次模型调用
_MIN_INPUT_LEN = 6

#: memory_type → v3.2 封闭谓词（计划 Step 6 粗映射；表外类型落 other）
_MEMORY_TYPE_PREDICATE = {
    "偏好": "preference",
    "关系事实": "behavior",
    "沟通雷区": "constraint",
    "核心诉求": "goal",
    "事件": "event",
}


def _apply_v3_fields(
    memory: AiMemory,
    *,
    user_id: int,
    memory_type: str,
    source: Optional[str],
    pipeline_task_id: Optional[int] = None,
    item_fingerprint: Optional[str] = None,
    v3_identity=None,
) -> None:
    """双写：在同一条 AiMemory 上补齐 v3.2 §1~4 契约列（flag 开才调用）。

    身份优先用调用方给的 `v3_identity`（pipeline 路径，已 enforce 过仍再
    enforce 一次——幂等），否则按 source 默认推导；**推导失败由调用方捕获**
    标 `legacy_pending`，绝不让记忆落库失败（§6.3 补偿队列指标桩）。
    """
    from app.core import config as app_config
    from app.services.memory_fingerprint import PIPELINE_VERSION
    from app.services.memory_identity import Identity, enforce_identity
    from app.services.memory_ownership import (
        default_ownership,
        epistemic_default,
    )
    from app.services.memory_registry import (
        build_fact_key,
        cardinality_for,
        normalize_object_key,
    )

    # 身份四元组
    if v3_identity is None:
        epi, origin = epistemic_default(source)
        if origin == "business_event" or epi == "system_event":
            identity = Identity(None, None, "system_event", "business_event")
        else:
            identity = Identity(user_id, user_id, epi, origin)
    elif isinstance(v3_identity, Identity):
        identity = v3_identity
    else:
        identity = Identity(**v3_identity)
    enforced = enforce_identity(identity)
    memory.reported_by_user_id = enforced.reported_by_user_id
    memory.attributed_to_user_id = enforced.attributed_to_user_id
    memory.epistemic_type = enforced.epistemic_type
    memory.assertion_origin = enforced.assertion_origin

    # 主体与谓词注册表
    subject_role_user = user_id
    memory.subject_type = "user"
    memory.subject_user_id = subject_role_user
    predicate = _MEMORY_TYPE_PREDICATE.get(memory_type, "other")
    object_key, registry_miss = normalize_object_key(None)  # 非 chat 路径无词典输入
    memory.predicate_code = predicate
    memory.object_key = object_key
    memory.registry_miss = registry_miss
    memory.cardinality = cardinality_for(predicate)
    memory.fact_key = build_fact_key("user", subject_role_user, predicate, object_key)

    # 所有权（§6.2）
    owner, creator, ownership_type = default_ownership(source, user_id=user_id)
    memory.owner_user_id = owner
    memory.created_by_user_id = creator
    memory.ownership_type = ownership_type

    # 管线与索引（§4；index_status 显式写，绝不依赖 DB 默认）
    memory.schema_version = "v3"
    memory.pipeline_task_id = pipeline_task_id
    memory.item_fingerprint = item_fingerprint
    memory.pipeline_version = PIPELINE_VERSION
    memory.index_status = "pending_upsert" if app_config.MEMORY_ASSERTION_INDEX_WORKER else "skipped"


def create_memory(
    db: Session,
    user_id: int,
    relation_id: int,
    memory_type: str,
    memory_text: str,
    visibility: str = "private",
    *,
    occurred_at: Optional[datetime] = None,
    source: Optional[str] = None,
    source_id: Optional[int] = None,
    importance: int = 0,
    pipeline_task_id: Optional[int] = None,
    item_fingerprint: Optional[str] = None,
    v3_identity=None,
) -> dict:
    """落库一条记忆。P-C1 §3：事件四要素透传（全部可选，旧调用逐字节不变）。

    ``occurred_at`` 为空时存 ``created_at``（只在为空时回填，不覆盖已有值）。

    v3.2 双写（`MEMORY_ASSERTION_DUAL_WRITE=1`）：同事务补齐 v3 契约列；
    推导失败仍提交但标 `schema_version='legacy_pending'` + `[MEM-V3-COMP]`
    告警（补偿队列指标桩）。`MEMORY_ASSERTION_INDEX_WORKER=1` 时新行
    `index_status='pending_upsert'` 并**跳过**旧向量化钩子（单一索引属主）。
    flag 全关时本函数行为与 v1 逐字节一致。
    """
    from app.core import config as app_config

    memory = AiMemory(
        user_id=user_id,
        relation_id=relation_id,
        memory_type=memory_type,
        memory_text=memory_text,
        visibility=visibility,
        occurred_at=occurred_at,
        source=source,
        source_id=source_id,
        importance=importance,
    )
    if app_config.MEMORY_ASSERTION_DUAL_WRITE:
        try:
            _apply_v3_fields(
                memory,
                user_id=user_id,
                memory_type=memory_type,
                source=source,
                pipeline_task_id=pipeline_task_id,
                item_fingerprint=item_fingerprint,
                v3_identity=v3_identity,
            )
        except Exception:
            # §6.3：推导失败不阻塞落库，标 legacy_pending 进补偿
            logger.warning(
                "[MEM-V3-COMP] v3 列推导失败，标 legacy_pending "
                "user=%s type=%s source=%s",
                user_id, memory_type, source, exc_info=True,
            )
            memory.schema_version = "legacy_pending"
    db.add(memory)
    db.flush()
    if memory.occurred_at is None:
        # 回填 = created_at（server default，flush 后按需回读）
        if memory.created_at is None:
            db.refresh(memory, ["created_at"])
        memory.occurred_at = memory.created_at
    db.commit()
    saved = _to_dict(memory)
    # P0-4：新记忆异步向量化（约束②写入侧）。失败只记日志，不影响落库。
    # v3.2：INDEX_WORKER=1 时旧钩子让位（新行 pending_upsert 由 worker 领取）。
    if not app_config.MEMORY_ASSERTION_INDEX_WORKER:
        try:
            from app.services.memory_retrieval import vectorize_memory_async

            vectorize_memory_async(saved)
        except Exception:
            logger.warning("[MEMORY] 向量化挂钩异常（不影响落库）", exc_info=True)
    elif app_config.MEMORY_ASSERTION_DUAL_WRITE:
        # v3.2 D3：非 chat 触发点写入 pending_upsert 行，拉起索引 worker
        # （进程内懒启动；flags 关时下面的门直接放行=旧行为）
        try:
            from app.services.memory_pipeline_worker import ensure_started

            ensure_started()
        except Exception:
            logger.warning("[MEMORY] 启动索引 worker 异常（不影响落库）", exc_info=True)
    return saved


def get_memories(
    db: Session,
    user_id: int,
    relation_id: Optional[int] = None,
    memory_type: Optional[str] = None,
    source: Optional[str] = None,
    importance: Optional[int] = None,
    since: Optional[datetime] = None,
    until: Optional[datetime] = None,
) -> List[dict]:
    """记忆列表（P-C3 §4.1：加 source/importance/since/until 筛选，向后兼容）。

    时间过滤按 ``occurred_at``，NULL 回退 ``created_at``（事件**发生**时间
    才是用户感知的「什么时候的事」）；排序同理 coalesce 后倒序 + ``id``
    稳定 tiebreak——MySQL 不支持 ``NULLS LAST``，不能写 ``nullslast()``。
    """
    query = db.query(AiMemory).filter(AiMemory.user_id == user_id)
    if relation_id:
        query = query.filter(AiMemory.relation_id == relation_id)
    if memory_type:
        query = query.filter(AiMemory.memory_type == memory_type)
    if source:
        query = query.filter(AiMemory.source == source)
    if importance is not None:
        query = query.filter(AiMemory.importance == importance)
    effective_time = func.coalesce(AiMemory.occurred_at, AiMemory.created_at)
    if since is not None:
        query = query.filter(effective_time >= since)
    if until is not None:
        query = query.filter(effective_time <= until)
    memories = (
        query.order_by(effective_time.desc(), AiMemory.id.desc()).all()
    )
    return [_to_dict(m) for m in memories]


def update_importance(db: Session, memory_id: int, user_id: int, importance: int) -> dict:
    """标星/取消标星（P-C3 §4.2）。只接受 2（标星）/ 0（取消）。"""
    if importance not in (0, 2):
        raise ValueError("importance 只能是 0 或 2")
    memory = (
        db.query(AiMemory)
        .filter(AiMemory.id == memory_id, AiMemory.user_id == user_id)
        .first()
    )
    if memory is None:
        raise ValueError("记忆不存在")
    memory.importance = importance
    db.commit()
    return _to_dict(memory)


def get_couple_memories(db: Session, user_id: int, relation_id: int) -> List[dict]:
    """读取本关系 couple 可见记忆。

    IDOR 修复（v3.2 附录 §5.2）：强制 current_user ∈ 关系成员，否则
    `ValueError`（router 映射 403）——仅靠 relation_id 不足以授权，
    任何拿到关系外 id 的调用方都必须被挡在这里。
    """
    from app.models.couple_relation import CoupleRelation

    relation = (
        db.query(CoupleRelation)
        .filter(CoupleRelation.id == relation_id)
        .first()
    )
    if relation is None or user_id not in (
        relation.user_a_id,
        relation.user_b_id,
    ):
        raise ValueError("50002")  # router 层翻译为 403，不泄露关系是否存在
    memories = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.relation_id == relation_id,
                AiMemory.visibility == "couple",
                AiMemory.status == "active",
            )
        )
        .order_by(AiMemory.created_at.desc())
        .all()
    )
    return [_to_dict(m) for m in memories]


def get_memory_context(
    db: Session,
    user_id: int,
    relation_id: int,
    limit: int = 10,
    query: str = "",
    *,
    memory_need: str = "personal_fact",
) -> str:
    """prompt 注入用的记忆片段。

    P0-4 起改走 `memory_retrieval.retrieve_memory_items`：
      - 传了 query → 两段式混合召回（通道 floor 原始分 + 排序层）
      - 未传 query → 按 memory_need 路由（recent_context/空 query 契约
        → 近因；personal_fact/history → 空，v3.2 §5.2）
    可见性：自己 private + 本关系 couple（约束①），不含对方 private。
    memory_need 由**服务端**调用方传（场景配置/工具契约），不接受模型指定。
    """
    from app.services.memory_retrieval import (
        format_memory_context,
        retrieve_memory_items,
    )

    items = retrieve_memory_items(
        db,
        user_id,
        relation_id,
        query=query or "",
        limit=max(limit, 10),
        memory_need=memory_need,
    )
    return format_memory_context(items)


def update_visibility(db: Session, memory_id: int, user_id: int, visibility: str) -> dict:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    memory.visibility = visibility
    db.commit()
    # 一致性维护（非修 bug）：当前召回不读 metadata.visibility（走 SQL 双保险），
    # 但同步 chroma 避免日后有人按 metadata 过滤时拿到陈旧值。
    try:
        from app.services.memory_retrieval import _get_collection

        col = _get_collection()
        if col is not None:
            got = col.get(ids=[str(memory_id)])
            if got and got.get("ids"):
                metas = (got.get("metadatas") or [None])[0] or {}
                metas = dict(metas)
                metas["visibility"] = visibility
                col.update(ids=[str(memory_id)], metadatas=[metas])
    except Exception as exc:
        logger.warning("[MEMORY] 同步向量 metadata 失败 id=%s: %s", memory_id, exc)
    return _to_dict(memory)


def delete_memory(db: Session, memory_id: int, user_id: int) -> None:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    # v3 双写后，断言可能被 evidence / edge（双向端点）/ user_state 外键引用，
    # 直接 db.delete 会 IntegrityError 1451 → 接口 500。派生行无独立存在意义，
    # 在**同一事务**内先级联清掉再删断言（失败则整体回滚，不留半删状态）。
    from app.models.ai import (
        MemoryAssertionEdge,
        MemoryAssertionEvidence,
        MemoryAssertionUserState,
    )

    db.query(MemoryAssertionEvidence).filter(
        MemoryAssertionEvidence.assertion_id == memory_id
    ).delete(synchronize_session=False)
    db.query(MemoryAssertionEdge).filter(
        (MemoryAssertionEdge.parent_assertion_id == memory_id)
        | (MemoryAssertionEdge.child_assertion_id == memory_id)
    ).delete(synchronize_session=False)
    db.query(MemoryAssertionUserState).filter(
        MemoryAssertionUserState.assertion_id == memory_id
    ).delete(synchronize_session=False)
    db.delete(memory)
    db.commit()
    # 缺陷三：DB 是权威、向量是派生——commit 成功后同步删向量。
    # 向量库异常不能让删除接口失败（只记日志）。
    try:
        from app.services.memory_retrieval import _get_collection

        col = _get_collection()
        if col is not None:
            col.delete(ids=[str(memory_id)])
    except Exception as exc:
        logger.warning("[MEMORY] 同步删向量失败 id=%s: %s", memory_id, exc)


def get_memories_by_source(
    db: Session, user_id: int, source: str, source_id: int
) -> List[dict]:
    """某个业务实体（如一条观点）产生的记忆。

    用途单一但关键：详情页「计入军师记忆」开关的**初始状态**必须由服务端现状
    决定，而不是客户端凭上次操作记在内存里——否则重进页面就会显示成未计入，
    用户一点又写一条重复记忆。
    """
    rows = (
        db.query(AiMemory)
        .filter(
            AiMemory.user_id == user_id,
            AiMemory.source == source,
            AiMemory.source_id == source_id,
        )
        .order_by(AiMemory.id.asc())
        .all()
    )
    return [_to_dict(m) for m in rows]


def delete_memories_by_source(
    db: Session, user_id: int, source: str, source_id: int
) -> int:
    """撤除某个业务实体产生的全部记忆，返回删除条数。

    逐条走 `delete_memory`——它能同时同步删除向量库里的派生数据。绕过它直接
    `db.delete` 会留下"数据库已删、向量还在"的幽灵记忆，之后仍然会被召回。
    """
    rows = (
        db.query(AiMemory)
        .filter(
            AiMemory.user_id == user_id,
            AiMemory.source == source,
            AiMemory.source_id == source_id,
        )
        .all()
    )
    deleted = 0
    for memory in rows:
        try:
            delete_memory(db, memory.id, user_id)
            deleted += 1
        except ValueError:
            continue
    return deleted


def _to_dict(memory: AiMemory) -> dict:
    return {
        "id": memory.id,
        "user_id": memory.user_id,
        "relation_id": memory.relation_id,
        "memory_type": memory.memory_type,
        "memory_text": memory.memory_text,
        "visibility": memory.visibility,
        "created_at": memory.created_at.isoformat() if memory.created_at else None,
        # P-C1 §3.5：事件四要素（缺键调用方 .get() 拿不到）
        "occurred_at": memory.occurred_at.isoformat() if memory.occurred_at else None,
        "source": memory.source,
        "source_id": memory.source_id,
        "importance": memory.importance or 0,
    }


# --------------------------------------------------------------------------
# 记忆沉淀（写入端）
#
# 背景：`create_memory` 此前**全仓零调用**，导致 `AiMemory` 表永远为空、
# `get_memory_context()` 永远返回 ""、客户端「AI 记忆」页点开就是空列表。
# 这一节把写入端接上：一轮对话结束后，由模型判断其中是否含值得长期记住的信息。
#
# 几个刻意的取舍：
# 1. 宁可漏记，不要滥记 —— 靠 `should_remember` 闸门，噪音会通过
#    `get_memory_context()` 进入后续每一轮 prompt，代价比漏记大得多；
# 2. 记忆是锦上添花，**绝不能影响对话主链路** —— 所有异常在此吞掉只记日志；
# 3. 不阻塞响应 —— 放到后台线程 + 独立会话里跑。
# --------------------------------------------------------------------------

def _is_duplicate_memory(
    db: Session, user_id: int, relation_id: int, memory_text: str
) -> bool:
    """按 (user_id, relation_id, memory_text) 完全相等去重。

    从 distill_and_save 内联逻辑抽出：AI 抽取路径与结构化直写路径
    （纪念日 / 量表，不经模型）必须共用这一套，否则重复事件会刷重复记忆。
    """
    return (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.user_id == user_id,
                AiMemory.relation_id == relation_id,
                AiMemory.memory_text == memory_text,
            )
        )
        .first()
        is not None
    )


def save_structured_memory(
    db: Session,
    user_id: int,
    relation_id: int,
    memory_text: str,
    memory_type: Optional[str] = None,
    *,
    occurred_at: Optional[datetime] = None,
    source: Optional[str] = None,
    source_id: Optional[int] = None,
    importance: int = 0,
) -> Optional[dict]:
    """不经 AI、直接构造好的记忆落库。复用 `_is_duplicate_memory` 去重。

    供纪念日（名称+日期）、量表（关系模式）这类**确定性文本**使用——
    内容没有歧义，不值得花一次模型调用；但去重与异常吞掉的纪律与 AI 路径一致。

    P-C1 §3：事件四要素透传（可选 kwargs，默认 None → 旧调用逐字节不变）。
    """
    text = (memory_text or "").strip()
    if not text:
        return None
    mtype = memory_type if memory_type in MEMORY_TYPES else _DEFAULT_MEMORY_TYPE
    try:
        if _is_duplicate_memory(db, user_id, relation_id, text):
            return None
        saved = create_memory(
            db, user_id, relation_id, mtype, text,
            occurred_at=occurred_at, source=source,
            source_id=source_id, importance=importance,
        )
        logger.info("[MEMORY] 结构化落库 user=%s type=%s", user_id, mtype)
        return saved
    except Exception:
        logger.exception("[MEMORY] 结构化记忆落库失败 user=%s", user_id)
        db.rollback()
        return None


def build_distill_user_message(
    scene_key: str,
    user_input: str,
    assistant_text: str = "",
    focus: str = "",
    context: str = "",
) -> str:
    """组装提交给抽取模型的 user 消息。

    - `focus` 为空 → **对话模板，与本函数抽出之前的硬编码逐字节一致**
      （chat 链路零回归的保证，改动四坑 1）
    - `focus` 非空 → 事件模板：场景 + 可选事件描述 + 抽取重点 + 内容。
      信件/纪念馆/双视角等非对话源走这条，避免硬套「AI 回复」空段落
    """
    if not focus:
        return "场景：%s\n\n用户说：%s\n\nAI 回复：%s" % (
            scene_key,
            user_input,
            assistant_text or "",
        )
    parts = ["场景：%s" % scene_key]
    if context:
        parts.append("事件：%s" % context)
    parts.append("抽取重点：%s" % focus)
    parts.append("内容：\n%s" % user_input)
    return "\n\n".join(parts)


def memory_distill_enabled(db: Session, relation_id: Optional[int]) -> bool:
    """军师记忆沉淀总开关（记忆系统升级 P0④，关系级）。

    relation 行存在且 `memory_distill_enabled=False` → 阻断；其余情况
    （行缺失/列读失败）一律放行，保持旧行为——开关故障绝不能扩大成
    「所有记忆都停了」。调用点只有 distill 入口（AI 替用户做决定的路径）；
    用户主动「计入军师记忆」的直写不受它管。
    """
    if relation_id is None:
        return True
    try:
        from app.models.couple_relation import CoupleRelation

        row = (
            db.query(CoupleRelation.memory_distill_enabled)
            .filter(CoupleRelation.id == relation_id)
            .first()
        )
        if row is None:
            return True
        return bool(row[0])
    except Exception:  # noqa: BLE001——读不到开关时按「开」处理
        logger.warning("[MEMORY] 读取记忆沉淀开关失败 relation=%s", relation_id,
                       exc_info=True)
        return True


def distill_and_save(
    db: Session,
    user_id: int,
    relation_id: int,
    scene_key: str,
    user_input: str,
    assistant_text: str,
    focus: str = "",
    context: str = "",
    *,
    occurred_at: Optional[datetime] = None,
    source: Optional[str] = None,
    source_id: Optional[int] = None,
    importance: int = 0,
) -> Optional[dict]:
    """从一轮对话/一个事件里抽取一条长期记忆并落库。返回新记忆，未写库则返回 None。

    调用方需传入可用的 `db`（流式链路请用 `distill_in_background`，
    因为那时请求级会话已经销毁）。

    `focus` / `context` 为可选（P0-3 改动四）：为空时行为与旧版逐字节一致。
    P-C1 §3：事件四要素透传（可选 kwargs，默认 None → 旧调用逐字节不变；
    1200 字截断与 max_tokens=300 不动）。
    """
    text = (user_input or "").strip()
    if len(text) < _MIN_INPUT_LEN:
        return None

    # 军师记忆沉淀总开关（P0④）：关闭时在 distill 入口阻断，连模型调用都不发
    if not memory_distill_enabled(db, relation_id):
        logger.info(
            "[MEMORY] 军师记忆沉淀已关闭，跳过蒸馏 relation=%s scene=%s",
            relation_id, scene_key,
        )
        return None

    try:
        result = distill_llm.invoke_structured(
            [
                {"role": "system", "content": MEMORY_DISTILL_PROMPT},
                {
                    "role": "user",
                    "content": build_distill_user_message(
                        scene_key, user_input, assistant_text,
                        focus=focus, context=context,
                    ),
                },
            ],
            scene="memory_distill",
            temperature=0.2,
            max_tokens=300,
        )
    except LlmError as exc:
        logger.warning("[MEMORY] 记忆抽取失败 scene=%s: %s", scene_key, exc)
        return None
    except Exception:
        logger.exception("[MEMORY] 记忆抽取异常 scene=%s", scene_key)
        return None

    if not getattr(result, "should_remember", False):
        return None

    memory_type = getattr(result, "memory_type", None)
    if memory_type not in MEMORY_TYPES:
        memory_type = _DEFAULT_MEMORY_TYPE

    memory_text = (getattr(result, "memory_text", "") or "").strip()
    if not memory_text:
        return None

    try:
        if _is_duplicate_memory(db, user_id, relation_id, memory_text):
            return None
        saved = create_memory(
            db, user_id, relation_id, memory_type, memory_text,
            occurred_at=occurred_at, source=source,
            source_id=source_id, importance=importance,
        )
        logger.info("[MEMORY] 已沉淀记忆 user=%s type=%s", user_id, memory_type)
        return saved
    except Exception:
        logger.exception("[MEMORY] 记忆落库失败 user=%s", user_id)
        db.rollback()
        return None


def distill_in_background(
    user_id: int,
    relation_id: int,
    scene_key: str,
    user_input: str,
    assistant_text: str,
    focus: str = "",
    context: str = "",
) -> None:
    """后台线程里沉淀记忆：开独立会话，不阻塞也不影响对话响应。

    必须在**请求级会话之外**调用（流式链路的响应体是在请求会话销毁之后
    才被消费的），因此这里自己开 SessionLocal。

    测试隔离：`COUPLE_DISABLE_MEMORY_DISTILL=1` 时直接 return——
    daemon 线程在测试进程退出后才落库，用例 finally 抓不到，会污染开发库。
    默认不设该变量，线上行为零变化。
    """
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return None
    if not llm.api_key:
        return None
    if len((user_input or "").strip()) < _MIN_INPUT_LEN:
        return None

    def _worker():
        from app.core.database import SessionLocal

        db = SessionLocal()
        try:
            distill_and_save(
                db, user_id, relation_id, scene_key, user_input, assistant_text,
                focus=focus, context=context,
            )
        except Exception:
            logger.exception("[MEMORY] 后台沉淀异常 user=%s", user_id)
        finally:
            db.close()

    threading.Thread(
        target=_worker, name="ai-memory-distill", daemon=True
    ).start()
    return None


def distill_session_summary_in_background(
    session_id: int,
    user_id: int,
    relation_id: int,
    scene_key: str,
) -> None:
    """P0-10A 改动七：会话归档后蒸馏 ≤120 字叙事摘要写入 ai_memory。

    - memory_type=SESSION_SUMMARY_MEMORY_TYPE（P-C1 §2 登记）、visibility="private"
    - relation_id 必传（NOT NULL）
    - P-C1 §5.2：**改走 create_memory** —— 补 source='chat_summary' + occurred_at，
      并接上向量化挂钩（P0-4 时代直插绕过 → 四级漏斗第一级是断的，本路径此前
      从不进向量库）
    - 复用 distill_llm（qwen-turbo）与 distill_in_background 同款开关/线程纪律
    - 默认不设开关 → 线上行为不变
    """
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return None
    if not distill_llm.api_key:
        return None

    def _worker():
        from app.core.database import SessionLocal
        from app.models.ai import AiChatMessage

        db = SessionLocal()
        try:
            # 军师记忆沉淀总开关（P0④）：会话摘要同属蒸馏产物，一并受控
            if not memory_distill_enabled(db, relation_id):
                return
            msgs = (
                db.query(AiChatMessage)
                .filter(AiChatMessage.session_id == session_id)
                .order_by(AiChatMessage.created_at.asc())
                .all()
            )
            if not msgs:
                return
            conversation = "\n".join(
                "%s: %s" % (m.role, (m.content or "")[:500]) for m in msgs
            )[:6000]
            raw = distill_llm.invoke(
                [
                    {
                        "role": "user",
                        "content": SESSION_SUMMARY_PROMPT.format(
                            conversation=conversation
                        ),
                    }
                ],
                scene="session_summary",
                temperature=0.3,
                max_tokens=200,
            )
            summary = (raw or "").strip()
            if not summary:
                return
            if len(summary) > 120:
                summary = summary[:119] + "…"
            # P-C1 §5.2：改走 create_memory（source/occurred_at 落库 + 向量化挂钩）。
            # 不做字面去重——session_summary 每段会话一条，天然不重复
            saved = create_memory(
                db,
                user_id,
                relation_id,
                SESSION_SUMMARY_MEMORY_TYPE,
                summary,
                visibility="private",
                source="chat_summary",
                source_id=session_id,
                importance=0,
            )
            logger.info(
                "[MEMORY] 会话摘要已沉淀 session=%s len=%d id=%s",
                session_id,
                len(summary),
                saved.get("id"),
            )
        except Exception:
            logger.exception(
                "[MEMORY] 会话摘要蒸馏失败 session=%s", session_id
            )
            db.rollback()
        finally:
            db.close()

    threading.Thread(
        target=_worker, name="session-summary", daemon=True
    ).start()
    return None
