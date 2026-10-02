# -*- coding: utf-8 -*-
"""整改 B4.3 · P0-2 / P0-4：`safety_blocked` 独立终态 + 未知风险 fail closed。

## P0-2 要证的事

高风险阻断此前把会话收口到 `completed`——与「正常谈完」同名。后果是三重的：

- 客户端无法区分「这次谈成了」和「这次被安全终止了」，只能靠猜；
- 结果页会给出一整排对安全终止**不该存在**的推进动作
  （继续沟通 / 暂停一下 / 结束调解 / 重试生成）；
- `continue` 能从 `completed` 重新打开，等于把一次安全终止又拉回正常流程。

现在 `safety_blocked` 是独立终态：释放活跃槽位、可回看安全提示、
**不给任何推进动作**、不能被 continue 打开。

## P0-4 要证的事

未知风险值（null / 空串 / 大小写混杂 / 自造值）此前被降级成 `normal` 放行，
这是 fail open。现在一律按 `unknown` 处理并**阻断产物**——
「不得依赖 Prompt 保证模型永远返回合法值」。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from hermetic_harness import MediationHarness  # noqa: E402
from live_llm_guard import live_llm_enabled, skip_reason  # noqa: E402

from app.models.ai import AiChatSession  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.models.user import User  # noqa: E402
from app.repositories import ai_repo, ai_task_repo, safety_repo  # noqa: E402
from app.services import ai_task_service, home_service, mediation_service  # noqa: E402

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


def _relation(db):
    for uid in (A_ID, B_ID):
        if db.query(User).filter(User.id == uid).first() is None:
            db.add(User(
                id=uid,
                email="u%s@example.com" % uid,
                password_hash="x",
                has_couple=True,
            ))
    db.commit()
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


def _assistant(db, sid):
    return [
        m for m in ai_repo.get_messages_by_session(db, sid) if m.role == "assistant"
    ]


def _reach_inputting(h, db):
    rel = _relation(db)
    sid = mediation_service.start_mediation(db, A_ID, rel.id)["session_id"]
    mediation_service.accept_mediation(db, sid, B_ID)
    return sid


def _run_rewrite_with_risk(h, db, risk_value):
    """走完整改写链路，桩返回给定的 risk 值。返回 session_id。"""
    h.driving({"mediation_rewrite": {
        "rewrite_a": "改写 A", "rewrite_b": "改写 B", "risk_level": risk_value,
    }})
    sid = _reach_inputting(h, db)
    mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
    mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
    ai_task_service.run_due_tasks(db, worker_id="test-worker")
    return sid


# --------------------------------------------------------------------------- #
# P0-2：safety_blocked 独立终态
# --------------------------------------------------------------------------- #
def t_high_risk_lands_in_safety_blocked():
    h = MediationHarness()
    try:
        with h:
            db = h.db()
            sid = _run_rewrite_with_risk(h, db, " Abuse_Risk ")
            session = _session(db, sid)

            check("高风险会话进入 safety_blocked",
                  session.mediation_status == "safety_blocked",
                  session.mediation_status)
            check("不再冒充 completed",
                  session.mediation_status != "completed",
                  session.mediation_status)
            check("不是失败态（不该给用户「重试」）",
                  session.mediation_failure_code is None,
                  str(session.mediation_failure_code))
            check("活跃槽位已释放", session.mediation_active_slot is None,
                  str(session.mediation_active_slot))

            msgs = _assistant(db, sid)
            check("只落一条安全提示消息", len(msgs) == 1, str(len(msgs)))
            so = (msgs[0].structured_output or {}) if msgs else {}
            check("没有落任何调解改写",
                  "rewrite_a" not in so and "rewrite_b" not in so, str(so))
            check("消息带安全资源正文", bool(so.get("safety_response")), str(so))
            check("风险等级已归一化落库",
                  bool(msgs) and msgs[0].risk_level == "abuse_risk",
                  str(msgs[0].risk_level) if msgs else "")
            db.close()
    finally:
        h.destroy()


def t_safety_blocked_can_review_but_has_no_progress_actions():
    """可回看安全提示，但**不能**从它继续推进任何流程。"""
    h = MediationHarness()
    try:
        with h:
            db = h.db()
            sid = _run_rewrite_with_risk(h, db, "self_harm_risk")

            status = mediation_service.get_status(db, sid, A_ID)
            check("GET {id} 可读（能回看）",
                  status["mediation_status"] == "safety_blocked",
                  str(status["mediation_status"]))
            check("回看时能看到安全提示",
                  any(m.get("structured_output", {}).get("safety_response")
                      for m in status["messages"] if m.get("role") == "assistant"),
                  str(status["messages"]))
            check("没有总结内容（不得显示正常调解总结）",
                  not status.get("rewrites"), str(status.get("rewrites")))

            try:
                mediation_service.next_step(db, sid, A_ID, "continue")
                check("safety_blocked 拒绝 continue", False)
            except ValueError:
                check("safety_blocked 拒绝 continue", True)
            check("状态保持 safety_blocked",
                  _session(db, sid).mediation_status == "safety_blocked",
                  _session(db, sid).mediation_status)
            db.close()
    finally:
        h.destroy()


def t_safety_blocked_not_in_history_or_active():
    """历史列表明确显示已安全终止；活跃列表不得把它当「进行中」。"""
    h = MediationHarness()
    try:
        with h:
            db = h.db()
            sid = _run_rewrite_with_risk(h, db, "manipulation_risk")

            # 历史列表只筛 completed，safety_blocked 不该混进去当「谈完了」
            history = mediation_service.list_mediations(db, A_ID, "history")
            ids = [i["session_id"] for i in history["items"]]
            check("safety_blocked 不计入「已完成」历史列表",
                  sid not in ids, str(ids))

            # 但 mine 列表要能看到它（用户得找得到那一场），状态原样下发
            mine = mediation_service.list_mediations(db, A_ID, "mine")
            row = next((i for i in mine["items"] if i["session_id"] == sid), None)
            check("mine 列表仍能找到这一场", row is not None, str(mine["items"]))
            check("列表状态是 safety_blocked（客户端据此显示「已安全终止」）",
                  row is not None and row["mediation_status"] == "safety_blocked",
                  str(row))

            # 活跃列表（首页 active_mediation）不得把它当进行中
            home = home_service.get_home_data(db, A_ID)
            check("首页 active_mediation 不含 safety_blocked",
                  home.get("active_mediation") is None,
                  str(home.get("active_mediation")))
            db.close()
    finally:
        h.destroy()


def t_safety_blocked_audit_event_written():
    """安全审计落库（与输入侧同一个出口）。"""
    h = MediationHarness()
    try:
        with h:
            db = h.db()
            _run_rewrite_with_risk(h, db, "abuse_risk")
            events = safety_repo.get_recent_events(db, limit=20)
            med = [e for e in events if e.scene == "mediation" and e.source == "output"]
            check("落了一条调解输出侧的安全审计",
                  len(med) == 1, str([(e.scene, e.source, e.risk_level) for e in events]))
            check("审计记录风险等级",
                  med and med[0].risk_level == "abuse_risk",
                  str(med[0].risk_level) if med else "")
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# P0-4：未知风险 fail closed（参数化矩阵）
# --------------------------------------------------------------------------- #
#: (用例名, 桩返回的原始值, 是否期望阻断)
RISK_MATRIX = [
    ("normal 放行", "normal", False),
    ("heated_conflict 放行（保留降温提示）", "heated_conflict", False),
    ("manipulation_risk 阻断", "manipulation_risk", True),
    ("abuse_risk 阻断", "abuse_risk", True),
    ("self_harm_risk 阻断", "self_harm_risk", True),
    ("null 阻断", None, True),
    ("空串阻断", "", True),
    ("纯空格阻断", "   ", True),
    ("大写阻断（无法识别）", "HIGH", True),
    ("大小写混杂的合法值放行", "Heated_Conflict", False),
    ("前后空格的合法值放行", "  abuse_risk  ", True),
    ("模型自造值阻断", "very_high", True),
    ("模型自造中文值阻断", "高风险", True),
    ("数字阻断", 3, True),
]


def t_risk_level_matrix_fail_closed():
    """逐值验证：只有白名单里的正常/激动放行，其余一律阻断。"""
    for label, raw, expect_block in RISK_MATRIX:
        h = MediationHarness()
        try:
            with h:
                db = h.db()
                sid = _run_rewrite_with_risk(h, db, raw)
                session = _session(db, sid)
                msgs = _assistant(db, sid)
                blocked = session.mediation_status == "safety_blocked"
                if expect_block:
                    check("[阻断] %s" % label, blocked,
                          "status=%s msgs=%s" % (session.mediation_status, len(msgs)))
                    so = (msgs[0].structured_output or {}) if msgs else {}
                    check("[阻断] %s 不落调解改写" % label,
                          "rewrite_a" not in so, str(so))
                else:
                    check("[放行] %s" % label,
                          session.mediation_status == "confirming",
                          session.mediation_status)
                    check("[放行] %s 落了改写" % label,
                          bool(msgs) and bool((msgs[0].structured_output or {}).get("rewrite_a")),
                          str(msgs[0].structured_output if msgs else None))
                db.close()
        finally:
            h.destroy()


def t_normalize_risk_level_is_fail_closed():
    """纯函数口径：未知一律 unknown，合法值归一化后原样返回。"""
    n = mediation_service.normalize_risk_level
    for raw in (None, "", "   ", "HIGH", "very_high", "高风险", 3, [], {}):
        check("normalize(%r) == unknown" % (raw,),
              n(raw) == mediation_service.RISK_LEVEL_UNKNOWN, n(raw))
    for raw, expected in (
        ("normal", "normal"),
        ("Normal", "normal"),
        (" HEATED_CONFLICT ", "heated_conflict"),
        ("abuse_risk", "abuse_risk"),
        ("self_harm_risk", "self_harm_risk"),
        ("manipulation_risk", "manipulation_risk"),
    ):
        check("normalize(%r) == %s" % (raw, expected), n(raw) == expected, n(raw))


def t_unknown_risk_is_not_a_safety_resource_level():
    """`unknown` 是内部阻断标记，不得被当成一个有安全文案的危险等级去展示。"""
    from app.services.safety_service import get_safety_response

    check("unknown 不在 RISK_LEVEL_ORDER（不是危险度档位）",
          mediation_service.RISK_LEVEL_UNKNOWN
          not in __import__("app.services.safety_service", fromlist=["x"]).RISK_LEVEL_ORDER)
    # 阻断时用的仍是兜底文案，不是把 "unknown" 当成一个等级讲给用户
    h = MediationHarness()
    try:
        with h:
            db = h.db()
            sid = _run_rewrite_with_risk(h, db, "totally_made_up")
            msgs = _assistant(db, sid)
            so = (msgs[0].structured_output or {}) if msgs else {}
            check("阻断文案非空且不是裸等级名",
                  bool(so.get("safety_response"))
                  and so["safety_response"] != "unknown",
                  str(so))
            check("落库等级记成 unknown（供审计对账）",
                  msgs and msgs[0].risk_level == "unknown",
                  str(msgs[0].risk_level) if msgs else "")
            db.close()
    finally:
        h.destroy()


def t_summary_risk_also_fail_closed():
    """总结阶段的产物同样受门控：高风险/未知风险不得产出「关系总结」。

    只堵改写那一步是不够的——总结是把双方关系「总结成可复述的结论」，
    在控制/暴力/自伤语境下同样会被当成继续这样相处的许可。
    """
    for raw, expect_block in (("abuse_risk", True), ("totally_unknown", True),
                              ("normal", False)):
        h = MediationHarness()
        try:
            with h:
                h.driving({
                    "mediation_rewrite": {
                        "rewrite_a": "改写 A", "rewrite_b": "改写 B",
                        "risk_level": "normal",
                    },
                    "mediation_summary": {
                        "common_points": ["a"], "differences": ["b"],
                        "next_actions": ["c"], "risk_level": raw,
                    },
                })
                db = h.db()
                sid = _reach_inputting(h, db)
                mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
                mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
                ai_task_service.run_due_tasks(db, worker_id="test-worker")
                mediation_service.confirm_rewrite(db, sid, A_ID, True)
                mediation_service.confirm_rewrite(db, sid, B_ID, True)
                ai_task_service.run_due_tasks(db, worker_id="test-worker")

                session = _session(db, sid)
                assistant = _assistant(db, sid)
                so = (assistant[-1].structured_output or {}) if assistant else {}
                if expect_block:
                    check("[总结·阻断] %s" % raw,
                          session.mediation_status == "safety_blocked",
                          session.mediation_status)
                    check("[总结·阻断] %s 不落关系总结" % raw,
                          "common_points" not in so, str(so))
                else:
                    check("[总结·放行] %s" % raw,
                          session.mediation_status == "completed",
                          session.mediation_status)
                    check("[总结·放行] %s 落了关系总结" % raw,
                          "common_points" in so, str(so))
                db.close()
        finally:
            h.destroy()


def main() -> int:
    print("[调解安全终态 / 未知风险 fail closed] 整改 B4.3 P0-2 + P0-4 验收")
    if not live_llm_enabled("mediation_safety_terminal"):
        print(skip_reason("mediation_safety_terminal"))
        return 0
    t_high_risk_lands_in_safety_blocked()
    t_safety_blocked_can_review_but_has_no_progress_actions()
    t_safety_blocked_not_in_history_or_active()
    t_safety_blocked_audit_event_written()
    t_normalize_risk_level_is_fail_closed()
    t_risk_level_matrix_fail_closed()
    t_summary_risk_also_fail_closed()
    t_unknown_risk_is_not_a_safety_resource_level()
    print("========== 结果 ==========")
    print("断言数：%d" % CHECKS)
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
