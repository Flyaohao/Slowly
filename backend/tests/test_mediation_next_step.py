# -*- coding: utf-8 -*-
"""整改 B4.3 · P0-3：`next_step` 必须使用严格状态机。

## 当前实现的两个真实漏洞（本脚本先跑失败，证明它们存在）

1. **continue 是无条件 UPDATE**：`WHERE id = ?`，不检查当前状态。于是
   `rewriting`（AI 正在生成）可以被打回 `inputting`，把在途任务的产出变成
   永远无人接收的孤儿，同时 revision 不变 → 任务回来时还以为自己新鲜。
   实测：rewriting --continue--> inputting。
2. **pause 假装成功**：后端 `elif action == "pause": pass`，然后广播并返回
   当前状态。客户端因此保留了一枚点了什么都不会发生的按钮。

## 断言口径

状态矩阵逐格验证：只有「正常 completed」允许 continue；`safety_blocked`
与所有在途/失败态一律拒绝；`end` 只允许在明确的状态集合里发生，终态重复
end 幂等且**不再推进 revision**；在途任务被 end 终止后不得写回结果。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from hermetic_harness import MediationHarness  # noqa: E402

from app.models.ai import AiChatSession  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.repositories import ai_repo, ai_task_repo  # noqa: E402
from app.services import ai_task_service, mediation_service  # noqa: E402

A_ID = 101
B_ID = 202
C_ID = 303  # 非成员

REWRITE_STUB = {
    "rewrite_a": "改写 A", "rewrite_b": "改写 B", "risk_level": "normal",
}
SUMMARY_STUB = {
    "common_points": ["a"], "differences": ["b"], "next_actions": ["c"],
    "risk_level": "normal",
}

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


def _relation(db):
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return rel


def _session(db, sid):
    return (
        db.query(AiChatSession)
        .filter(AiChatSession.id == sid)
        .populate_existing()
        .first()
    )


def _force_status(db, sid, status):
    """把会话强行摆到某个状态（用于逐格验证状态矩阵）。"""
    db.query(AiChatSession).filter(AiChatSession.id == sid).update(
        {"mediation_status": status}, synchronize_session=False
    )
    db.commit()


def _expect_rejected(db, sid, user_id, action):
    """返回 True 表示被明确拒绝（ValueError）。"""
    try:
        mediation_service.next_step(db, sid, user_id, action)
        return False
    except ValueError:
        return True


def _reach_inputting(h, db):
    rel = _relation(db)
    sid = mediation_service.start_mediation(db, A_ID, rel.id)["session_id"]
    mediation_service.accept_mediation(db, sid, B_ID)
    return sid


def _reach_confirming(h, db):
    sid = _reach_inputting(h, db)
    mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
    mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
    ai_task_service.run_due_tasks(db, worker_id="test-worker")
    return sid


def _reach_completed(h, db):
    sid = _reach_confirming(h, db)
    mediation_service.confirm_rewrite(db, sid, A_ID, True)
    mediation_service.confirm_rewrite(db, sid, B_ID, True)
    ai_task_service.run_due_tasks(db, worker_id="test-worker")
    return sid


# --------------------------------------------------------------------------- #
# 1. continue 只允许从「正常 completed」出发
# --------------------------------------------------------------------------- #
def t_continue_rejected_from_non_completed_states():
    """状态矩阵：在途态、失败态、safety_blocked 一律不得 continue。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_inputting(h, db)

            for status in (
                "inviting", "accepted", "inputting", "rewriting",
                "rewrite_failed", "confirming", "summarizing", "summary_failed",
                "safety_blocked",
            ):
                _force_status(db, sid, status)
                before = _session(db, sid)
                rejected = _expect_rejected(db, sid, A_ID, "continue")
                after = _session(db, sid)
                check(
                    "continue 被拒：%s" % status,
                    rejected and after.mediation_status == status,
                    "rejected=%s status=%s" % (rejected, after.mediation_status),
                )
                check(
                    "continue 被拒时不得改动 revision：%s" % status,
                    after.mediation_revision == before.mediation_revision,
                    "%s != %s" % (after.mediation_revision, before.mediation_revision),
                )
            db.close()
    finally:
        h.destroy()


def t_continue_allowed_only_from_completed():
    """正常 completed → inputting 是 continue 唯一的合法入口。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_completed(h, db)
            check("前置：会话已 completed",
                  _session(db, sid).mediation_status == "completed",
                  _session(db, sid).mediation_status)

            result = mediation_service.next_step(db, sid, A_ID, "continue")
            check("completed → inputting 成功",
                  result["mediation_status"] == "inputting",
                  str(result))
            check("重新占用活跃槽位",
                  _session(db, sid).mediation_active_slot is not None,
                  str(_session(db, sid).mediation_active_slot))
            db.close()
    finally:
        h.destroy()


def t_safety_blocked_cannot_continue():
    """安全终止是**终态**：不得用 continue 重新打开。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_completed(h, db)
            _force_status(db, sid, "safety_blocked")
            db.query(AiChatSession).filter(AiChatSession.id == sid).update(
                {"mediation_active_slot": None}, synchronize_session=False
            )
            db.commit()

            check("safety_blocked 拒绝 continue",
                  _expect_rejected(db, sid, A_ID, "continue"))
            rev_before = _session(db, sid).mediation_revision
            # 终态上的 end 是**幂等**的（不报错、不推进版本、不改状态）——
            # 与 completed 上的重复 end 同一口径。
            result = mediation_service.next_step(db, sid, A_ID, "end")
            check("safety_blocked 上的 end 幂等返回自身状态",
                  result["mediation_status"] == "safety_blocked", str(result))
            check("safety_blocked 上的 end 不推进 revision",
                  _session(db, sid).mediation_revision == rev_before,
                  "%s != %s" % (_session(db, sid).mediation_revision, rev_before))
            check("状态未被改动",
                  _session(db, sid).mediation_status == "safety_blocked",
                  _session(db, sid).mediation_status)
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 2. pause 是假功能：必须被明确拒绝
# --------------------------------------------------------------------------- #
def t_pause_is_rejected_not_faked():
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_inputting(h, db)
            before = _session(db, sid)
            rejected = _expect_rejected(db, sid, A_ID, "pause")
            after = _session(db, sid)
            check("pause 必须被拒绝（不得假装成功）", rejected)
            check("pause 被拒后状态不变",
                  after.mediation_status == before.mediation_status,
                  after.mediation_status)
            check("pause 被拒后 revision 不变",
                  after.mediation_revision == before.mediation_revision)
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 3. end：明确允许集合 + 终态幂等不重复推进版本
# --------------------------------------------------------------------------- #
def t_end_allowed_from_active_states_only():
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_inputting(h, db)

            for status in (
                "inviting", "accepted", "inputting", "rewriting",
                "rewrite_failed", "confirming", "summarizing", "summary_failed",
            ):
                _force_status(db, sid, status)
                db.query(AiChatSession).filter(AiChatSession.id == sid).update(
                    {"mediation_active_slot": "1:101"}, synchronize_session=False
                )
                db.commit()
                rev_before = _session(db, sid).mediation_revision
                try:
                    mediation_service.next_step(db, sid, A_ID, "end")
                    ended = True
                except ValueError:
                    ended = False
                session = _session(db, sid)
                check("end 允许于 %s" % status, ended)
                check("end 后进入终态：%s" % status,
                      session.mediation_status == "completed",
                      session.mediation_status)
                check("end 释放活跃槽位：%s" % status,
                      session.mediation_active_slot is None,
                      str(session.mediation_active_slot))
                check("end 推进一次版本：%s" % status,
                      session.mediation_revision == rev_before + 1,
                      "%s -> %s" % (rev_before, session.mediation_revision))
            db.close()
    finally:
        h.destroy()


def t_repeated_end_is_idempotent_without_revision_drift():
    """已终态的重复 end 幂等返回，且**不得**再次推进 revision。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.next_step(db, sid, A_ID, "end")
            rev_after_first = _session(db, sid).mediation_revision

            result = mediation_service.next_step(db, sid, A_ID, "end")
            check("重复 end 幂等返回 completed",
                  result["mediation_status"] == "completed", str(result))
            check("重复 end 不再推进 revision",
                  _session(db, sid).mediation_revision == rev_after_first,
                  "%s != %s" % (_session(db, sid).mediation_revision, rev_after_first))

            # 再来一次，仍不漂移
            mediation_service.next_step(db, sid, B_ID, "end")
            check("第三次 end 仍不推进 revision",
                  _session(db, sid).mediation_revision == rev_after_first,
                  str(_session(db, sid).mediation_revision))
            db.close()
    finally:
        h.destroy()


def t_end_terminates_inflight_task():
    """在途任务被 end 终止后不得写回结果，也不得把会话翻回失败态。"""
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")

            task = ai_task_repo.claim_next_task(db, "wA")
            check("前置：任务已被领走", task is not None and task.state == "running")

            mediation_service.next_step(db, sid, A_ID, "end")
            check("end 后会话 completed",
                  _session(db, sid).mediation_status == "completed",
                  _session(db, sid).mediation_status)

            mediation_service._commit_generation_result(
                db, task, dict(REWRITE_STUB), "confirming"
            )
            assistant = [
                m for m in ai_repo.get_messages_by_session(db, sid)
                if m.role == "assistant"
            ]
            check("在途任务不得写回结果", len(assistant) == 0, str(len(assistant)))
            check("会话不得被翻回 confirming",
                  _session(db, sid).mediation_status == "completed",
                  _session(db, sid).mediation_status)
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 4. 权限：非成员一律拒绝
# --------------------------------------------------------------------------- #
def t_non_member_cannot_act():
    h = MediationHarness()
    try:
        with h:
            h.driving({"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB})
            db = h.db()
            sid = _reach_completed(h, db)
            check("非成员 continue 被拒", _expect_rejected(db, sid, C_ID, "continue"))
            check("非成员 end 被拒", _expect_rejected(db, sid, C_ID, "end"))
            # 双方成员都可以 end
            mediation_service.next_step(db, sid, B_ID, "end")
            check("参与方可以 end",
                  _session(db, sid).mediation_status == "completed",
                  _session(db, sid).mediation_status)
            db.close()
    finally:
        h.destroy()


def main() -> int:
    print("[调解 next_step 严格状态机] 整改 B4.3 P0-3 验收")
    t_continue_rejected_from_non_completed_states()
    t_continue_allowed_only_from_completed()
    t_safety_blocked_cannot_continue()
    t_pause_is_rejected_not_faked()
    t_end_allowed_from_active_states_only()
    t_repeated_end_is_idempotent_without_revision_drift()
    t_end_terminates_inflight_task()
    t_non_member_cannot_act()
    print("========== 结果 ==========")
    print("断言数：%d" % CHECKS)
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
