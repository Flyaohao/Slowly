"""三套状态机（v3.2 附录 §2.2/§2.3/§2.4）——转换校验与联动的唯一入口。

纯校验 + 行内更新，**不 commit**（调用方按事务边界提交，T6 要求与边/审计同事务）。
非法转换抛 `InvalidTransition`，绝不静默放行。

索引联动（§2.4）：
- 断言离开 `active`（superseded/expired/archived/purging）→ `index_generation+1`
  且 `index_status→pending_remove`（`skipped` 行不动——legacy 从未进新索引）；
- `indexed→pending_upsert/pending_remove`、`failed→pending_upsert` → generation+1。
"""
import logging
from typing import Dict, FrozenSet, Optional, Tuple

logger = logging.getLogger("couple.memory.status")


class InvalidTransition(Exception):
    """状态机非法转换。"""


#: 附录 §2.2 Assertion 生命周期（archived→active 仅 restore+审计；purged 终态）
ASSERTION_TRANSITIONS: Dict[str, FrozenSet[str]] = {
    "active": frozenset({"superseded", "expired", "archived", "purging"}),
    "superseded": frozenset({"purging"}),
    "expired": frozenset({"purging"}),
    "archived": frozenset({"purging", "active"}),  # 撤销归档 = 审计恢复
    "purging": frozenset({"purged"}),
    "purged": frozenset(),  # 终态，不得恢复
}

#: 附录 §2.3 Pipeline 任务状态
TASK_TRANSITIONS: Dict[str, FrozenSet[str]] = {
    "distill_pending": frozenset(
        {"extraction_saved", "completed_noop", "completed_skipped", "failed"}
    ),
    "extraction_saved": frozenset(
        {"memory_committed", "completed_noop", "completed_skipped", "failed"}
    ),
    "memory_committed": frozenset(
        {"indexing", "completed", "memory_available_index_failed"}
    ),
    "indexing": frozenset({"completed", "memory_available_index_failed"}),
    "completed": frozenset(),
    "completed_noop": frozenset(),
    "completed_skipped": frozenset(),
    "failed": frozenset(),
    "memory_available_index_failed": frozenset(),
}

#: 附录 §2.4 Index 状态。`indexing→pending_remove/skipped/removed` 是 T5 §7
#: CAS 落空的合法出口（附录事务边界），一并收入。
INDEX_TRANSITIONS: Dict[str, FrozenSet[str]] = {
    "pending_upsert": frozenset({"indexing", "skipped"}),
    "indexing": frozenset({"indexed", "failed", "pending_remove", "skipped", "removed"}),
    "indexed": frozenset({"pending_upsert", "pending_remove"}),
    "failed": frozenset({"pending_upsert"}),
    "pending_remove": frozenset({"removed"}),
    "removed": frozenset(),
    "skipped": frozenset({"pending_upsert"}),  # §8 ③ 批量回填入口
}

#: 这些 index 转换必须 generation+1（§2.4：embedding 换代 / 断言离场）
INDEX_GENERATION_BUMP: FrozenSet[Tuple[str, str]] = frozenset(
    {
        ("indexed", "pending_upsert"),
        ("indexed", "pending_remove"),
        ("failed", "pending_upsert"),
    }
)

#: 断言离开 active 的状态（触发索引失效联动）
_ASSERTION_EXIT_ACTIVE = frozenset({"superseded", "expired", "archived", "purging"})


def _check(transitions: Dict[str, FrozenSet[str]], current: str, target: str, kind: str):
    if current == target:
        return  # 幂等 no-op：已是目标状态
    allowed = transitions.get(current)
    if allowed is None:
        raise InvalidTransition("%s: unknown state %r" % (kind, current))
    if target not in allowed:
        raise InvalidTransition(
            "%s: %s -> %s not allowed (allowed: %s)"
            % (kind, current, target, sorted(allowed) or "none")
        )


def transition_assertion(db, row, new_status: str, *, reason: Optional[str] = None,
                         bump_generation: Optional[bool] = None):
    """断言生命周期转换（附录 §2.2）。不 commit——调用方决定事务边界。

    离开 active 默认联动索引失效（generation+1、index_status→pending_remove）；
    `skipped`（legacy/未入索引）行不联动。`bump_generation` 可显式覆盖。
    """
    _check(ASSERTION_TRANSITIONS, row.status, new_status, "assertion")
    should_bump = bump_generation if bump_generation is not None else (
        row.status == "active" and new_status in _ASSERTION_EXIT_ACTIVE
    )
    row.status = new_status
    if reason is not None:
        row.status_reason = reason
    if should_bump and row.index_status not in ("skipped", "removed"):
        row.index_generation = (row.index_generation or 1) + 1
        row.index_status = "pending_remove"
    return row


def transition_task(db, row, new_state: str, *, error_code: Optional[str] = None,
                    error_message: Optional[str] = None):
    """任务状态转换（附录 §2.3）。不 commit。"""
    _check(TASK_TRANSITIONS, row.state, new_state, "task")
    row.state = new_state
    if error_code is not None:
        row.last_error_code = str(error_code)[:50]
    if error_message is not None:
        row.last_error_message = str(error_message)[:500]
    return row


def transition_index(db, row, new_status: str, *, error: Optional[str] = None):
    """索引状态转换（附录 §2.4）。不 commit。

    generation 联动按 `INDEX_GENERATION_BUMP` 自动执行。
    """
    _check(INDEX_TRANSITIONS, row.index_status, new_status, "index")
    key = (row.index_status, new_status)
    row.index_status = new_status
    if key in INDEX_GENERATION_BUMP:
        row.index_generation = (row.index_generation or 1) + 1
    if error is not None:
        row.index_error = str(error)[:500]
    return row
