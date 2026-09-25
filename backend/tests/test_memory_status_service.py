"""v3.2 附录 §2.2/§2.3/§2.4 三套状态机（纯属性对象，无 DB，hermetic）

断言：
  1. ASSERTION_TRANSITIONS：合法转换放行、非法抛 InvalidTransition、
     purged 终态、superseded/expired 不回 active、archived→active 可恢复；
  2. 离开 active 自动联动索引失效（generation+1、index_status→pending_remove），
     skipped 行不联动；
  3. TASK_TRANSITIONS 主链 + noop/skipped/failed 出口；
  4. INDEX_TRANSITIONS + generation 联动（indexed→pending_upsert 等三处 +1）。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_status_service.py
"""
import os
import sys
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def _assertion_row(**kw):
    base = dict(status="active", status_reason=None,
                index_status="indexed", index_generation=1)
    base.update(kw)
    return SimpleNamespace(**base)


def _task_row(state="distill_pending"):
    return SimpleNamespace(state=state, last_error_code=None, last_error_message=None)


def _index_row(status="pending_upsert", gen=1):
    return SimpleNamespace(index_status=status, index_generation=gen, index_error=None)


def main() -> int:
    print("=" * 72)
    print("附录 §2.2/§2.3/§2.4 状态机")
    print("=" * 72)

    from app.services.memory_status_service import (
        ASSERTION_TRANSITIONS,
        INDEX_TRANSITIONS,
        TASK_TRANSITIONS,
        InvalidTransition,
        transition_assertion,
        transition_index,
        transition_task,
    )

    print("\n[1] Assertion 生命周期（§2.2）")
    row = _assertion_row()
    transition_assertion(None, row, "superseded", reason="superseded")
    check("active→superseded 放行", row.status == "superseded")
    check("reason 落库", row.status_reason == "superseded")
    check("离开 active 联动：generation+1", row.index_generation == 2, str(row.index_generation))
    check("离开 active 联动：index_status→pending_remove",
          row.index_status == "pending_remove", row.index_status)
    try:
        transition_assertion(None, _assertion_row(), "purged")
        check("active→purged 拒绝", False)
    except InvalidTransition:
        check("active→purged 拒绝", True)
    try:
        transition_assertion(None, _assertion_row(status="superseded"), "active")
        check("superseded→active 拒绝（不原地恢复）", False)
    except InvalidTransition:
        check("superseded→active 拒绝（不原地恢复）", True)
    try:
        transition_assertion(None, _assertion_row(status="purged"), "active")
        check("purged 终态拒绝", False)
    except InvalidTransition:
        check("purged 终态拒绝", True)
    row = _assertion_row(status="archived", index_status="skipped")
    transition_assertion(None, row, "active", reason="user_restored")
    check("archived→active 放行（restore+审计）", row.status == "active")
    check("restore 不联动索引", row.index_status == "skipped" and row.index_generation == 1)
    row = _assertion_row(index_status="skipped")
    transition_assertion(None, row, "archived", reason="user_archived")
    check("skipped 行离开 active 不联动索引",
          row.index_status == "skipped" and row.index_generation == 1)
    row = _assertion_row()
    transition_assertion(None, row, "active")
    check("同状态幂等 no-op", row.status == "active" and row.index_generation == 1)
    row = _assertion_row()
    transition_assertion(None, row, "archived", bump_generation=False)
    check("bump_generation=False 显式覆盖（generation 不变）",
          row.index_generation == 1 and row.index_status == "indexed")

    print("\n[2] Pipeline 任务状态（§2.3）")
    t = _task_row()
    for step in ("extraction_saved", "memory_committed", "indexing", "completed"):
        transition_task(None, t, step)
    check("主链走通 completed", t.state == "completed", t.state)
    try:
        transition_task(None, _task_row(), "completed")
        check("distill_pending→completed 拒绝（必须经主链）", False)
    except InvalidTransition:
        check("distill_pending→completed 拒绝（必须经主链）", True)
    t = _task_row()
    transition_task(None, t, "completed_noop")
    check("distill_pending→completed_noop 放行", t.state == "completed_noop")
    t = _task_row("extraction_saved")
    transition_task(None, t, "failed", error_code="LLM_TIMEOUT", error_message="模型超时")
    check("extraction_saved→failed + 错误落库",
          t.state == "failed" and t.last_error_code == "LLM_TIMEOUT", str(t.last_error_code))
    try:
        transition_task(None, _task_row("failed"), "distill_pending")
        check("failed 不可回退（附录未列）", False)
    except InvalidTransition:
        check("failed 不可回退（附录未列）", True)
    t = _task_row("indexing")
    transition_task(None, t, "memory_available_index_failed")
    check("indexing→memory_available_index_failed 放行",
          t.state == "memory_available_index_failed")
    check("三个状态表终态闭环",
          not any(ASSERTION_TRANSITIONS["purged"]),
          str(sorted(ASSERTION_TRANSITIONS["purged"]))
          + str(sorted(TASK_TRANSITIONS["completed"]))
          + str(sorted(INDEX_TRANSITIONS["removed"])))

    print("\n[3] Index 状态（§2.4）")
    ix = _index_row()
    transition_index(None, ix, "indexing")
    transition_index(None, ix, "indexed")
    check("pending_upsert→indexing→indexed", ix.index_status == "indexed")
    transition_index(None, ix, "pending_upsert")
    check("indexed→pending_upsert 放行且 generation+1",
          ix.index_generation == 2, str(ix.index_generation))
    ix = _index_row("indexed", gen=3)
    transition_index(None, ix, "pending_remove")
    check("indexed→pending_remove generation+1",
          ix.index_generation == 4, str(ix.index_generation))
    ix = _index_row("failed", gen=2)
    transition_index(None, ix, "pending_upsert")
    check("failed→pending_upsert generation+1", ix.index_generation == 3)
    ix = _index_row("indexing")
    transition_index(None, ix, "pending_remove")
    check("indexing→pending_remove（T5 §7 CAS 落空出口）",
          ix.index_status == "pending_remove" and ix.index_generation == 1)
    try:
        transition_index(None, _index_row("removed"), "indexing")
        check("removed 终态拒绝", False)
    except InvalidTransition:
        check("removed 终态拒绝", True)
    ix = _index_row("pending_upsert")
    transition_index(None, ix, "pending_upsert")
    check("同状态幂等 no-op（generation 不动）", ix.index_generation == 1)

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
