"""断言边（v3.2 附录 §1.2 / §2.1）：校验 + 同事务副作用。

边方向（§2.1 终版）：
- supersedes：parent=旧断言 → 旧 `superseded/superseded`，新 `active`
- invalidates：parent=旧断言 → 旧 `archived/invalidated`，新 `active`
- merges：每个父一条边 → 父 `superseded/merged`，新 `active`
- contradicts：固定 `min(id)→max(id)`，**不改状态**（双方并存带来源标签）

校验（附录 §1.2）：同 relation、禁自环、环检测沿父链深度 ≤32（超限拒绝并
告警）、唯一键冲突 no-op。副作用走 `memory_status_service.transition_assertion`
（离开 active 自动联动索引失效），**不 commit**——调用方（T4/纠错端点）按
事务提交，保证边与状态原子。
"""
import logging
from typing import Optional

from sqlalchemy.exc import IntegrityError

logger = logging.getLogger("couple.memory.edge")

MAX_CHAIN_DEPTH = 32
EDGE_RELATIONS = ("supersedes", "invalidates", "merges", "contradicts")
#: 参与环检测的有向关系（contradicts 不改状态、min→max 固定，天然无环）
CYCLIC_RELATIONS = ("supersedes", "invalidates", "merges")

#: 边 → (旧断言新状态, status_reason)；contradicts 无副作用
_SIDE_EFFECTS = {
    "supersedes": ("superseded", "superseded"),
    "invalidates": ("archived", "invalidated"),
    "merges": ("superseded", "merged"),
    "contradicts": None,
}


class EdgeRejected(ValueError):
    """边被拒绝（跨关系 / 自环 / 成环 / 深度超限 / 身份红线）。"""


def assert_no_auto_supersede_of_partner_self_report(
    *, new_epistemic: str, parent_epistemic: str
) -> None:
    """身份红线（§2.1）：observation/attributed_report/interpretation
    **永不自动 supersede** 对方的 self_report。违反 → EdgeRejected
    （调用方应改用 contradicts 并存）。
    """
    if (
        parent_epistemic == "self_report"
        and new_epistemic in ("observation", "attributed_report", "interpretation")
    ):
        raise EdgeRejected(
            "identity guard: %s must not auto-supersede a self_report"
            % new_epistemic
        )


def _load_both(db, relation_id: int, parent_id: int, child_id: int):
    from app.models.ai import AiMemory

    rows = (
        db.query(AiMemory)
        .filter(AiMemory.id.in_([parent_id, child_id]))
        .all()
    )
    by_id = {r.id: r for r in rows}
    parent = by_id.get(parent_id)
    child = by_id.get(child_id)
    if parent is None or child is None:
        raise EdgeRejected("assertion not found: %s / %s" % (parent_id, child_id))
    for row in (parent, child):
        if row.relation_id != relation_id:
            raise EdgeRejected(
                "cross-relation edge rejected: assertion %s belongs to relation %s"
                % (row.id, row.relation_id)
            )
    return parent, child


def _assert_no_cycle(db, relation_id: int, parent_id: int, child_id: int) -> None:
    """沿父链查环（附录 §1.2）：加 parent→child 后若 parent 是 child 的后代即成环。

    沿 parent 的父链上溯 ≤ MAX_CHAIN_DEPTH；超限拒绝并告警。
    """
    from app.models.ai import MemoryAssertionEdge

    cur = parent_id
    for depth in range(MAX_CHAIN_DEPTH + 1):
        if cur == child_id:
            raise EdgeRejected(
                "edge cycle detected: %s -> %s" % (parent_id, child_id)
            )
        row = (
            db.query(MemoryAssertionEdge.parent_assertion_id)
            .filter(
                MemoryAssertionEdge.child_assertion_id == cur,
                MemoryAssertionEdge.relation_id == relation_id,
                MemoryAssertionEdge.relation_type.in_(CYCLIC_RELATIONS),
            )
            .first()
        )
        if row is None:
            return
        cur = row[0]
    logger.warning(
        "[MEM-EDGE] chain depth exceeds %d from assertion %s (relation %s); "
        "rejecting edge %s -> %s",
        MAX_CHAIN_DEPTH, parent_id, relation_id, parent_id, child_id,
    )
    raise EdgeRejected(
        "parent chain deeper than %d (relation %s)"
        % (MAX_CHAIN_DEPTH, relation_id)
    )


def create_edge(
    db,
    *,
    relation_id: int,
    parent_id: int,
    child_id: int,
    relation_type: str,
    created_by_user_id: Optional[int] = None,
    commit: bool = False,
):
    """插入边并执行同事务副作用。返回新边 id；已存在同边 → no-op 返回 None。

    **不 commit**（`commit=True` 仅供独立调用/测试）：T4/纠错端点要求边、
    状态、证据在同一事务原子提交。
    """
    from app.models.ai import MemoryAssertionEdge
    from app.services.memory_status_service import transition_assertion

    if relation_type not in EDGE_RELATIONS:
        raise EdgeRejected("unknown relation_type: %r" % relation_type)
    if parent_id == child_id:
        raise EdgeRejected("self-loop edge rejected: %s" % parent_id)

    # contradicts 固定 min→max（附录 §1.2）
    if relation_type == "contradicts":
        parent_id, child_id = min(parent_id, child_id), max(parent_id, child_id)

    parent, _child = _load_both(db, relation_id, parent_id, child_id)

    # 唯一键 no-op（uk_memory_edge）
    exists = (
        db.query(MemoryAssertionEdge.id)
        .filter(
            MemoryAssertionEdge.parent_assertion_id == parent_id,
            MemoryAssertionEdge.child_assertion_id == child_id,
            MemoryAssertionEdge.relation_type == relation_type,
        )
        .first()
    )
    if exists is not None:
        return None

    if relation_type in CYCLIC_RELATIONS:
        _assert_no_cycle(db, relation_id, parent_id, child_id)

    edge = MemoryAssertionEdge(
        relation_id=relation_id,
        parent_assertion_id=parent_id,
        child_assertion_id=child_id,
        relation_type=relation_type,
        created_by_user_id=created_by_user_id,
    )
    db.add(edge)
    try:
        db.flush()  # 拿到 edge.id；并发撞唯一键由调用方按任务级重试处理
    except IntegrityError:
        db.rollback()
        logger.info(
            "[MEM-EDGE] concurrent duplicate edge %s->%s(%s); treated as no-op",
            parent_id, child_id, relation_type,
        )
        return None

    # 同事务副作用：更新旧断言状态（离开 active 联动索引失效，status_service 内）。
    # 新断言（child）保持 active——其写入方（T4/纠错端点）负责插入时即为 active。
    effect = _SIDE_EFFECTS[relation_type]
    if effect is not None:
        new_status, reason = effect
        transition_assertion(db, parent, new_status, reason=reason)

    if commit:
        db.commit()
    return edge.id
