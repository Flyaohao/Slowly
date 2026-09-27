# -*- coding: utf-8 -*-
"""整改 B4.3 · P0-1：结果写入权必须绑定任务所有权（真实竞争交错）。

## 这份脚本要证的那件危险事

B4.2 的收尾顺序是：**先推进会话状态 + 插入 assistant 消息 + commit，之后**
才调 `mark_succeeded()`。`_claim_generation()` 只看会话状态，不看任务行的
`state` / `locked_by`。于是存在这样一条真实交错：

    1. Worker A 领取任务（running, locked_by=A）
    2. A 的租约过期
    3. Worker B 回收并领取同一任务（running, locked_by=B）
    4. A 的 LLM 先返回（A 手里的快照仍是 locked_by=A）
    5. A 抢先推进会话并写入 assistant 消息 —— 因为会话还停在 rewriting，
       `_claim_generation` 的 CAS 对 A **是命中的**
    6. A 最后才发现 mark_succeeded 失败

第 5 步已经发生：**旧 Worker 的结果展示给了用户**。B 的结果随后被丢弃
（会话已 confirming，CAS 不命中）。最终用户看到的是过期执行者的稿子。

B4.2 的 `t_duplicate_executor_cannot_double_write` 只覆盖「第一个执行者
**已经写完**、会话已到 confirming」的顺序，所以从来没碰到这个窗口。

## 断言口径

危险交错发生后，旧执行者必须**一个字都写不进去**：
会话状态、revision、assistant 消息数、任务状态全部不变；随后 B 写入成功，
最终只有一条 assistant 消息，且内容来自 B。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 \
        python tests/test_mediation_ownership.py
"""
import os
import sys
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from hermetic_harness import MediationHarness  # noqa: E402
from sqlalchemy import update  # noqa: E402

from app.models.ai import AiChatSession  # noqa: E402
from app.models.ai_task import AiTask  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.repositories import ai_repo, ai_task_repo  # noqa: E402
from app.services import ai_task_service, mediation_service  # noqa: E402

A_ID = 101
B_ID = 202

FAILURES = []
CHECKS = 0


def check(name, cond, detail=""):
    global CHECKS
    CHECKS += 1
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


class TaskSnapshot:
    """旧执行者手里的任务快照：字段停在**领取那一刻**，与库里的行无关。

    这正是危险交错的成因——A 手里攥着一份「我是 locked_by」的旧认知。
    """

    def __init__(self, task):
        self.id = task.id
        self.session_id = task.session_id
        self.task_type = task.task_type
        self.revision = task.revision
        self.locked_by = task.locked_by
        self.requested_by_user_id = task.requested_by_user_id
        self.attempt = task.attempt
        self.max_attempts = task.max_attempts
        self.last_error = task.last_error


def _relation(db):
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return rel


def _reach_inputting(h, db):
    rel = _relation(db)
    sid = mediation_service.start_mediation(db, A_ID, rel.id)["session_id"]
    mediation_service.accept_mediation(db, sid, B_ID)
    return sid


def _session(db, sid):
    return (
        db.query(AiChatSession)
        .filter(AiChatSession.id == sid)
        .populate_existing()
        .first()
    )


def _task(db, task_id):
    return ai_task_repo.get_task_by_id(db, task_id)


def _assistant(db, sid):
    return [
        m for m in ai_repo.get_messages_by_session(db, sid) if m.role == "assistant"
    ]


def _enqueue_rewrite(db, h, sid):
    mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
    mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")


def _dangerous_interleave(db, task_type):
    """A 领取 → A 租约过期 → B 回收并领取。返回 (A 的旧快照, B 的任务行)。"""
    a = ai_task_repo.claim_next_task(db, "wA")
    assert a is not None and a.task_type == task_type, "wA 没领到 %s" % task_type
    # 租约过期：A 还在跑 LLM，但这一行已经可以被回收
    db.execute(
        update(AiTask)
        .where(AiTask.id == a.id)
        .values(locked_until=datetime.utcnow() - timedelta(seconds=60))
    )
    db.commit()
    stale_a = TaskSnapshot(a)

    recovered = ai_task_service.recover_stale_tasks(db)
    assert recovered == 1, "A 的过期任务应被回收，实际 recovered=%s" % recovered
    b = ai_task_repo.claim_next_task(db, "wB")
    assert b is not None and b.id == a.id, "B 应领到同一行"
    assert b.locked_by == "wB"
    return stale_a, b


# --------------------------------------------------------------------------- #
# 1. 改写路径：危险交错
# --------------------------------------------------------------------------- #
def t_stale_executor_cannot_write_after_reclaim():
    """P0-1（改写路径）：A 过期被回收、B 接管后，A 先回来也不得写任何东西。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({
                "mediation_rewrite": {
                    "rewrite_a": "改写", "rewrite_b": "改写", "risk_level": "normal",
                },
            })
            db = h.db()
            sid = _reach_inputting(h, db)
            _enqueue_rewrite(db, h, sid)

            stale_a, task_b = _dangerous_interleave(db, "mediation_rewrite")
            before_rev = _session(db, sid).mediation_revision
            check("交错开始时会话仍在 rewriting",
                  _session(db, sid).mediation_status == "rewriting",
                  _session(db, sid).mediation_status)

            # A 的 LLM 先返回，拿着旧快照写结果
            mediation_service._commit_generation_result(
                db, stale_a,
                {"rewrite_a": "A 的过期改写", "rewrite_b": "A 的过期改写",
                 "risk_level": "normal"},
                "confirming",
            )

            check("A 不得推进会话（仍 rewriting）",
                  _session(db, sid).mediation_status == "rewriting",
                  _session(db, sid).mediation_status)
            check("A 不得改动 revision",
                  _session(db, sid).mediation_revision == before_rev,
                  "%s != %s" % (_session(db, sid).mediation_revision, before_rev))
            check("A 不得插入 assistant 消息",
                  len(_assistant(db, sid)) == 0, str(len(_assistant(db, sid))))
            row = _task(db, task_b.id)
            check("A 不得收口任务（仍 running 且归 B）",
                  row.state == "running" and row.locked_by == "wB",
                  "%s/%s" % (row.state, row.locked_by))

            # B 写入成功
            mediation_service._commit_generation_result(
                db, task_b,
                {"rewrite_a": "B 的改写 A", "rewrite_b": "B 的改写 B",
                 "risk_level": "normal"},
                "confirming",
            )
            check("B 写入后会话 confirming",
                  _session(db, sid).mediation_status == "confirming",
                  _session(db, sid).mediation_status)
            msgs = _assistant(db, sid)
            check("最终只有一条 assistant 消息", len(msgs) == 1, str(len(msgs)))
            so = (msgs[0].structured_output or {}) if msgs else {}
            check("消息内容来自 B", so.get("rewrite_a") == "B 的改写 A", str(so))
            row = _task(db, task_b.id)
            check("任务由 B 收口 succeeded",
                  row.state == "succeeded" and row.locked_by is None,
                  "%s/%s" % (row.state, row.locked_by))
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 2. 总结路径：危险交错
# --------------------------------------------------------------------------- #
def t_stale_executor_cannot_write_summary():
    """P0-1（总结路径）：同一交错发生在 summarizing → completed 上。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({
                "mediation_rewrite": {
                    "rewrite_a": "改写 A", "rewrite_b": "改写 B", "risk_level": "normal",
                },
                "mediation_summary": {
                    "common_points": ["a"], "differences": ["b"],
                    "next_actions": ["c"], "risk_level": "normal",
                },
            })
            db = h.db()
            sid = _reach_inputting(h, db)
            _enqueue_rewrite(db, h, sid)
            ai_task_service.run_due_tasks(db, worker_id="w0")
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            mediation_service.confirm_rewrite(db, sid, B_ID, True)
            check("会话进入 summarizing",
                  _session(db, sid).mediation_status == "summarizing",
                  _session(db, sid).mediation_status)

            stale_a, task_b = _dangerous_interleave(db, "mediation_summary")
            before_rev = _session(db, sid).mediation_revision

            mediation_service._commit_generation_result(
                db, stale_a,
                {"common_points": ["A 的过期总结"], "differences": [],
                 "next_actions": [], "risk_level": "normal"},
                "completed",
            )
            check("A 不得推进会话（仍 summarizing）",
                  _session(db, sid).mediation_status == "summarizing",
                  _session(db, sid).mediation_status)
            check("A 不得改动 revision",
                  _session(db, sid).mediation_revision == before_rev)
            check("A 不得插入总结消息",
                  len(_assistant(db, sid)) == 1, str(len(_assistant(db, sid))))

            mediation_service._commit_generation_result(
                db, task_b,
                {"common_points": ["B 的总结"], "differences": [],
                 "next_actions": [], "risk_level": "normal"},
                "completed",
            )
            check("B 写入后会话 completed",
                  _session(db, sid).mediation_status == "completed",
                  _session(db, sid).mediation_status)
            msgs = _assistant(db, sid)
            check("最终两条 assistant（改写 + 总结）", len(msgs) == 2, str(len(msgs)))
            so = (msgs[-1].structured_output or {}) if msgs else {}
            check("总结来自 B", so.get("common_points") == ["B 的总结"], str(so))
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 3. 安全阻断路径：危险交错
# --------------------------------------------------------------------------- #
def t_stale_executor_cannot_write_safety_block():
    """P0-1（安全阻断路径）：正常结果与阻断结果必须共用同一套所有权规则。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({
                "mediation_rewrite": {
                    "rewrite_a": "改写", "rewrite_b": "改写", "risk_level": "normal",
                },
            })
            db = h.db()
            sid = _reach_inputting(h, db)
            _enqueue_rewrite(db, h, sid)

            stale_a, task_b = _dangerous_interleave(db, "mediation_rewrite")
            before_rev = _session(db, sid).mediation_revision

            mediation_service._commit_generation_result(
                db, stale_a,
                {"rewrite_a": "", "rewrite_b": "", "risk_level": "abuse_risk"},
                "confirming",
            )
            check("A 不得用安全阻断收口会话",
                  _session(db, sid).mediation_status == "rewriting",
                  _session(db, sid).mediation_status)
            check("A 不得改动 revision",
                  _session(db, sid).mediation_revision == before_rev)
            check("A 不得落安全提示消息",
                  len(_assistant(db, sid)) == 0, str(len(_assistant(db, sid))))
            check("A 不得释放活跃槽位",
                  _session(db, sid).mediation_active_slot is not None,
                  str(_session(db, sid).mediation_active_slot))

            # B 正常产出
            mediation_service._commit_generation_result(
                db, task_b,
                {"rewrite_a": "B 的改写 A", "rewrite_b": "B 的改写 B",
                 "risk_level": "normal"},
                "confirming",
            )
            check("B 写入后会话 confirming",
                  _session(db, sid).mediation_status == "confirming",
                  _session(db, sid).mediation_status)
            msgs = _assistant(db, sid)
            check("最终只有一条 assistant 消息", len(msgs) == 1, str(len(msgs)))
            check("消息是 B 的正常改写而非安全提示",
                  (msgs[0].structured_output or {}).get("rewrite_a") == "B 的改写 A",
                  str(msgs[0].structured_output if msgs else None))
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 4. 同一事务：消息插入失败时任务不得被收口
# --------------------------------------------------------------------------- #
def t_message_failure_rolls_back_task_finalize():
    """P0-1：任务收口、会话 CAS、消息插入必须同生共死。

    在消息插入处注入异常，断言**任务仍在 running 且仍归本执行者**——
    否则会出现「任务 succeeded，但结果没落库」的静默丢失。
    """
    h = MediationHarness()
    try:
        with h:
            h.driving({
                "mediation_rewrite": {
                    "rewrite_a": "改写", "rewrite_b": "改写", "risk_level": "normal",
                },
            })
            db = h.db()
            sid = _reach_inputting(h, db)
            _enqueue_rewrite(db, h, sid)
            task = ai_task_repo.claim_next_task(db, "wA")

            original = ai_repo.create_message
            calls = {"n": 0}

            def boom(*args, **kwargs):
                calls["n"] += 1
                raise RuntimeError("注入的消息写入故障")

            ai_repo.create_message = boom
            try:
                mediation_service._commit_generation_result(
                    db, task,
                    {"rewrite_a": "改写", "rewrite_b": "改写", "risk_level": "normal"},
                    "confirming",
                )
            except Exception:  # noqa: BLE001 —— 异常允许外抛，状态必须干净
                pass
            finally:
                ai_repo.create_message = original

            check("消息写入被真的调用过", calls["n"] == 1, str(calls["n"]))
            check("会话未被推进",
                  _session(db, sid).mediation_status == "rewriting",
                  _session(db, sid).mediation_status)
            check("没有消息落库", len(_assistant(db, sid)) == 0)
            row = _task(db, task.id)
            check("任务未被收口（仍 running 且仍归 wA）",
                  row.state == "running" and row.locked_by == "wA",
                  "%s/%s" % (row.state, row.locked_by))
            # 事务仍可用
            check("事务仍可继续查询",
                  ai_task_repo.get_task_by_id(db, task.id) is not None)
            db.close()
    finally:
        h.destroy()


def main() -> int:
    print("[调解结果写入权 / 任务所有权] 整改 B4.3 P0-1 验收")
    t_stale_executor_cannot_write_after_reclaim()
    t_stale_executor_cannot_write_summary()
    t_stale_executor_cannot_write_safety_block()
    t_message_failure_rolls_back_task_finalize()
    print("========== 结果 ==========")
    print("断言数：%d" % CHECKS)
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
