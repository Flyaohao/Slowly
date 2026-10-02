# -*- coding: utf-8 -*-
"""调解身份 / 状态机回归（契约 §2.3/§2.4/§8.5，SQLite 临时库，不需要 MySQL / API Key）。

## 覆盖什么

P0-3（调解没有真正创建会话）与 P0-4（双方身份可能颠倒）的验收点：

1. start 响应带 `my_role="inviter"`，且**确实调用** `notify_mediation_invite`
   （契约 §2.3-5，此前零调用点）；
2. 「双方已提交」按 **distinct user** 判定：同一人提交两次不进 confirming，
   任意发言顺序（伴侣先发言）下第二个人提交后才进；
3. `user` 消息落 `user_id`（契约 §2.4-1）；
4. `GET {id}` 返回 `my_role` / `partner_submitted` / `updated_at` /
   `my_rewrite` / `partner_rewrite` / `rewrites`——**按身份而不是发言顺序**解析：
   伴侣先发言时，发起方仍拿到 rewrite_a、参与方仍拿到 rewrite_b；
   旧字段 `messages[].structured_output.rewrite_a/b` 原样保留（只增不减）；
5. confirm(true) → 双方齐了生成总结并 completed；
   confirm(false) 带 supplement 落库并重新生成改写（契约 §2.3-6）；
   **兜底改写失败绝不静默**：会话进明确失败态、不产总结（审查 H2 + B4.1-4）；
6. 状态变更 broadcast 到会话双方（契约 §2.3-4 的推送侧）；
7. 列表端点语义（契约 §2.3-3）：invited / mine / all / history；
8. 首页 active_mediation 过滤用真实状态集，`in_progress` 恒不命中（契约 §2.3-7）；
9. WS 源码契约：session_id 成员校验 close 4003、回声分支已删、心跳保留。

## 与整改 B4.1 的关系

整改后生成全部走「持久化任务 + worker」：请求只入队，执行由 worker 承担。
本脚本用 `tests/hermetic_harness.py` 把「请求侧踢 worker」这一步变成**同步执行**
（被测的仍是生产路径 `run_due_tasks`，没有内联开关），所以多数断言与整改前
写法一致；需要观察「入队后、执行前」中间态的两个用例显式关掉踢一脚、
手动驱动 worker。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 python tests/test_mediation_identity.py
"""
import os
import sys
import threading
import time
from contextlib import contextmanager

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)
sys.path.insert(0, HERE)

from hermetic_harness import MediationHarness  # noqa: E402

from app.models.ai import AiChatMessage, AiChatSession  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.repositories import ai_task_repo  # noqa: E402
from app.services import ai_task_service, mediation_service  # noqa: E402
from app.services import home_service  # noqa: E402

FAILURES = []

A_ID = 101  # 发起方（inviter）
B_ID = 202  # 参与方（partner）

REWRITE_STUB = {
    "rewrite_a": "发起方的温和改写",
    "rewrite_b": "参与方的承接改写",
    "risk_level": "normal",
}
SUMMARY_STUB = {
    "common_points": ["都希望对方好"],
    "differences": ["节奏不一致"],
    "next_actions": ["今晚各说一件今天对方做的小事"],
    "risk_level": "normal",
}
STUBS = {"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB}

INPUT_A = "这周我加班很晚回家，希望到家时能有句问候"
INPUT_B = "我这几天也很忙，其实一直惦记着你"


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


@contextmanager
def _env():
    """一次性环境：测试库接上应用 + LLM 桩 + 通知桩 + 同步 worker。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            try:
                yield h, db
            finally:
                db.close()
    finally:
        h.destroy()


def _relation(db):
    db.query(AiChatMessage).delete()
    db.query(AiChatSession).delete()
    db.query(CoupleRelation).delete()
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return rel


def _db_status(db, session_id):
    """**重新读库**取会话状态（绕开 identity map 里的旧对象）。"""
    return (
        db.query(AiChatSession)
        .filter(AiChatSession.id == session_id)
        .populate_existing()
        .first()
    )


def _tasks(db, session_id, task_type=None):
    rows = ai_task_repo.list_tasks(db, session_id)
    return [t for t in rows if task_type is None or t.task_type == task_type]


def _reach_confirming(db, partner_first=True):
    """start → accept → 双方输入，停在 confirming（含改写）。

    整改 B4.2 之后 API 进程不执行 LLM：入队后必须显式跑 worker，改写才落地。
    """
    rel = _relation(db)
    started = mediation_service.start_mediation(db, A_ID, rel.id)
    sid = started["session_id"]
    mediation_service.accept_mediation(db, sid, B_ID)
    if partner_first:
        mediation_service.submit_input(db, sid, B_ID, INPUT_B)
        # 同一人再提交一次：不得触发 confirming（契约 §2.4-2）
        mediation_service.submit_input(db, sid, B_ID, INPUT_B + "（追加）")
        mediation_service.submit_input(db, sid, A_ID, INPUT_A)
    else:
        mediation_service.submit_input(db, sid, A_ID, INPUT_A)
        mediation_service.submit_input(db, sid, B_ID, INPUT_B)
    ai_task_service.run_due_tasks(db, worker_id="test-worker")
    return sid, started


# --------------------------------------------------------------------- #
# 1. start：my_role + 邀请通知
# --------------------------------------------------------------------- #
def t_start_my_role_and_notify():
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        check("start 响应带 my_role=inviter", started.get("my_role") == "inviter", str(started))
        check("start 状态 inviting", started.get("mediation_status") == "inviting")
        check(
            "start 调用 notify_mediation_invite（§2.3-5）",
            len(h.notify_calls) == 1 and h.notify_calls[0][0] == B_ID,
            str(h.notify_calls),
        )
        if h.notify_calls:
            check(
                "通知带 session_id",
                h.notify_calls[0][1] == started["session_id"],
                str(h.notify_calls),
            )


# --------------------------------------------------------------------- #
# 2+3. distinct user 判定 / 身份解析（伴侣先发言）
# --------------------------------------------------------------------- #
def t_both_submitted_by_distinct_user():
    with _env() as (h, db):
        sid, _ = _reach_confirming(db, partner_first=True)
        session = _db_status(db, sid)
        check(
            "伴侣先发言 + 发起方后发言 → confirming",
            session.mediation_status == "confirming",
            session.mediation_status,
        )
        check(
            "同人双提交未提前触发（只生成 1 次改写）",
            len(h.rewrite_calls) == 1,
            str(h.llm_calls),
        )
        msgs = db.query(AiChatMessage).filter(
            AiChatMessage.session_id == sid, AiChatMessage.role == "user"
        ).all()
        check(
            "user 消息全部带 user_id（§2.4-1）",
            all(m.user_id in (A_ID, B_ID) for m in msgs),
            str([(m.user_id, m.content[:6]) for m in msgs]),
        )
        b_inputs = [m for m in msgs if m.user_id == B_ID]
        a_inputs = [m for m in msgs if m.user_id == A_ID]
        check("参与方两条输入", len(b_inputs) == 2, str(len(b_inputs)))
        check("发起方一条输入", len(a_inputs) == 1, str(len(a_inputs)))


def t_single_user_double_submit_not_confirming():
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        sid = started["session_id"]
        mediation_service.accept_mediation(db, sid, B_ID)
        mediation_service.submit_input(db, sid, B_ID, INPUT_B)
        session = _db_status(db, sid)
        check(
            "同一个人提交两次仍是 inputting（§2.4-2）",
            session.mediation_status == "inputting",
            session.mediation_status,
        )
        check("未进 confirming 时不调 LLM", h.llm_calls == [], str(h.llm_calls))
        check("未进 confirming 时不建任务", _tasks(db, sid) == [], str(_tasks(db, sid)))


def t_get_status_identity_parsing():
    with _env() as (h, db):
        sid, _ = _reach_confirming(db, partner_first=True)

        # 发起方视角
        st_a = mediation_service.get_status(db, sid, A_ID)
        check("A 视角 my_role=inviter", st_a.get("my_role") == "inviter", str(st_a.get("my_role")))
        check("A 视角 partner_submitted=true", st_a.get("partner_submitted") is True)
        check("updated_at 非空", bool(st_a.get("updated_at")), str(st_a.get("updated_at")))
        check(
            "A 的 my_rewrite = rewrite_a（身份而非顺序）",
            (st_a.get("my_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_a"],
            str(st_a.get("my_rewrite")),
        )
        check(
            "A 的 my_rewrite.original = A 的原话",
            (st_a.get("my_rewrite") or {}).get("original") == INPUT_A,
            str(st_a.get("my_rewrite")),
        )
        check(
            "A 的 partner_rewrite 未公开前不返回（§8.5-3）",
            st_a.get("partner_rewrite") is None,
            str(st_a.get("partner_rewrite")),
        )

        # 参与方视角（同一条会话）
        st_b = mediation_service.get_status(db, sid, B_ID)
        check("B 视角 my_role=partner", st_b.get("my_role") == "partner", str(st_b.get("my_role")))
        check(
            "B 的 my_rewrite = rewrite_b",
            (st_b.get("my_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_b"],
            str(st_b.get("my_rewrite")),
        )
        check(
            "B 的 my_rewrite.original = B 的原话",
            (st_b.get("my_rewrite") or {}).get("original") == INPUT_B + "（追加）",
            str(st_b.get("my_rewrite")),
        )

        # rewrites：公开前**只有自己那一侧**（§8.5-3，对方的改写不得泄露）
        rewrites = st_a.get("rewrites") or []
        check(
            "未公开时 rewrites 只含自己那一侧",
            rewrites == [
                {"author_user_id": A_ID, "content": REWRITE_STUB["rewrite_a"]},
            ],
            str(rewrites),
        )

        # 旧字段只增不减：messages[].structured_output 仍在（且不含对方那一侧）
        so = None
        for m in st_a.get("messages") or []:
            if m.get("role") == "assistant" and (m.get("structured_output") or {}).get("rewrite_a"):
                so = m["structured_output"]
        check(
            "messages[].structured_output.rewrite_a 保留（只增不减）",
            so is not None and so.get("rewrite_a") == REWRITE_STUB["rewrite_a"],
            str(so),
        )
        check(
            "未公开时 messages 的 structured_output 不含 rewrite_b",
            so is not None and "rewrite_b" not in so,
            str(so),
        )
        leaked = [
            m for m in (st_a.get("messages") or [])
            if REWRITE_STUB["rewrite_b"] in (m.get("content") or "")
        ]
        check(
            "未公开时 content 也不泄露对方改写文本",
            leaked == [],
            str([m.get("content")[:40] for m in leaked]),
        )
        b_leaked = [
            m for m in (st_a.get("messages") or [])
            if INPUT_B in (m.get("content") or "")
        ]
        check(
            "未公开时 A 看不到 B 的原始输入",
            b_leaked == [],
            str([(m.get("role"), (m.get("content") or "")[:20]) for m in b_leaked]),
        )


# --------------------------------------------------------------------- #
# 4. 状态帧广播（§2.3-4 推送侧）
# --------------------------------------------------------------------- #
def t_status_frames_broadcast():
    with _env() as (h, db):
        sid, _ = _reach_confirming(db, partner_first=True)
        statuses = h.statuses_broadcast
        check(
            "accept → 广播 inputting",
            "inputting" in statuses,
            str(statuses),
        )
        check(
            "改写完成 → 广播 confirming",
            "confirming" in statuses,
            str(statuses),
        )
        check(
            "广播发给会话双方",
            all(f[0] == [A_ID, B_ID] for f in h.frames),
            str(h.frames[:2]),
        )
        check(
            "帧形状 type=mediation_status + session_id + status",
            all(
                f[1].get("type") == "mediation_status"
                and f[1].get("session_id") == sid
                for f in h.frames
            ),
            str(h.frames[:1]),
        )


# --------------------------------------------------------------------- #
# 5. 双方各自确认 → summary（§8.5-5）
# --------------------------------------------------------------------- #
def t_confirm_requires_both_sides():
    with _env() as (h, db):
        sid, _ = _reach_confirming(db)

        # 确认阶段只入队，由本用例驱动 worker——这样才能观察到 summarizing 中间态
        first = mediation_service.confirm_rewrite(db, sid, A_ID, True)
        check(
            "单人确认不前推（停在 confirming）",
            first.get("mediation_status") == "confirming",
            str(first),
        )
        check("单人确认 my_confirmed=true", first.get("my_confirmed") is True, str(first))
        check(
            "单人确认 partner_confirmed=false",
            first.get("partner_confirmed") is False,
            str(first),
        )
        check(
            "单人确认不生成总结",
            h.summary_calls == [],
            str(h.llm_calls),
        )
        check(
            "单人确认不建总结任务",
            _tasks(db, sid, "mediation_summary") == [],
            str(_tasks(db, sid)),
        )

        # 对方视角：自己没确认、对方已确认
        st_b = mediation_service.get_status(db, sid, B_ID)
        check(
            "B 视角 my_confirmed=false / partner_confirmed=true（§8.5-7 断线恢复）",
            st_b.get("my_confirmed") is False and st_b.get("partner_confirmed") is True,
            str((st_b.get("my_confirmed"), st_b.get("partner_confirmed"))),
        )

        result = mediation_service.confirm_rewrite(db, sid, B_ID, True)
        check(
            "双方确认 → summarizing（§8.5-8 后台生成中）",
            result.get("mediation_status") == "summarizing",
            str(result),
        )
        check(
            "双方确认建了总结任务且只建一个",
            len(_tasks(db, sid, "mediation_summary")) == 1,
            str([(t.id, t.state) for t in _tasks(db, sid, "mediation_summary")]),
        )
        check("此时总结还没生成（确实是异步的）", h.summary_calls == [], str(h.llm_calls))

        check("worker 执行总结任务", ai_task_service.run_due_tasks(db, worker_id="t") == 1)
        check(
            "双方确认后总结生成（§2.3-6）",
            len(h.summary_calls) == 1,
            str(h.llm_calls),
        )
        session = _db_status(db, sid)
        check(
            "总结生成后 completed",
            session.mediation_status == "completed",
            session.mediation_status,
        )
        st = mediation_service.get_status(db, sid, A_ID)
        summary_msgs = [
            m for m in st["messages"]
            if m.get("role") == "assistant"
            and (m.get("structured_output") or {}).get("common_points")
        ]
        check(
            "总结落库且 FE 可从 messages 派生 common_points",
            len(summary_msgs) == 1,
            str(len(summary_msgs)),
        )
        check(
            "公开后 A 才看到 B 的改写（§8.5-3）",
            (st.get("partner_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_b"],
            str(st.get("partner_rewrite")),
        )
        check(
            "公开后 rewrites 两侧齐全",
            st.get("rewrites") == [
                {"author_user_id": A_ID, "content": REWRITE_STUB["rewrite_a"]},
                {"author_user_id": B_ID, "content": REWRITE_STUB["rewrite_b"]},
            ],
            str(st.get("rewrites")),
        )
        check(
            "总结帧广播 summarizing",
            "summarizing" in h.statuses_broadcast,
            str(h.statuses_broadcast),
        )
        check(
            "completed 帧广播",
            "completed" in h.statuses_broadcast,
            str(h.statuses_broadcast),
        )


def t_confirm_after_reveal_only_one_more():
    """双方确认齐了就该直接 completed，不再接受第三个确认。"""
    with _env() as (h, db):
        sid, _ = _reach_confirming(db)
        mediation_service.confirm_rewrite(db, sid, A_ID, True)
        mediation_service.confirm_rewrite(db, sid, B_ID, True)
        try:
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
        except ValueError as e:
            check("completed 后 confirm 被状态机拒绝", str(e) == "50003", "实际 %s" % e)
        else:
            check("completed 后 confirm 被状态机拒绝", False, "未抛 ValueError(50003)")


def t_concurrent_start_single_mediation():
    """P0-5：两路**真并发** start（双击 / 双设备 / 超时重发）只能有一场活跃调解。

    为什么必须真线程：先 SELECT 后 INSERT 在并发下必然双双查不到行、双双插入，
    唯一槽位约束才是真正的兜底。两条路径都要被走到——一个请求正常插入，另一个
    命中 IntegrityError 后回读既有会话并把同一个 session_id 返回。
    """
    h = MediationHarness(sync_threads=False)
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            rel_id = _relation(db).id
            db.close()

            barrier = threading.Barrier(2, timeout=20)
            results = {}
            errors = []

            def start():
                d = h.db()
                try:
                    barrier.wait()
                    results[id(d)] = mediation_service.start_mediation(d, A_ID, rel_id)
                except Exception as exc:  # noqa: BLE001
                    errors.append(repr(exc))
                finally:
                    d.close()

            threads = [threading.Thread(target=start), threading.Thread(target=start)]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=30)

            check("并发 start 无异常", errors == [], str(errors))
            check("两个请求都拿到回执", len(results) == 2, str(results))
            check(
                "两个请求指向同一场会话（幂等）",
                len({r["session_id"] for r in results.values()}) == 1,
                str(results),
            )
            check(
                "回执角色都是 inviter",
                all(r.get("my_role") == "inviter" for r in results.values()),
                str(results),
            )

            db = h.db()
            rows = (
                db.query(AiChatSession)
                .filter(
                    AiChatSession.relation_id == rel_id,
                    AiChatSession.session_type == "mediation",
                )
                .all()
            )
            check("库里只有一场调解会话", len(rows) == 1, str(len(rows)))
            check(
                "活跃槽位已写入（<relation>:<inviter>）",
                bool(rows) and rows[0].mediation_active_slot == "%s:%s" % (rel_id, A_ID),
                str(rows[0].mediation_active_slot) if rows else "",
            )
            db.close()
    finally:
        h.destroy()


def t_start_idempotent_same_session():
    """顺序重复 start（响应丢失后客户端重试）返回同一场会话，且不重复发邀请。"""
    with _env() as (h, db):
        rel_id = _relation(db).id
        first = mediation_service.start_mediation(db, A_ID, rel_id)
        for _ in range(2):
            again = mediation_service.start_mediation(db, A_ID, rel_id)
            check(
                "重复 start 返回同一场会话",
                again["session_id"] == first["session_id"],
                "%s vs %s" % (again, first),
            )
        check("只在真正新建时发一次邀请", len(h.notify_calls) == 1, str(h.notify_calls))
        check(
            "会话数没有增长",
            db.query(AiChatSession)
            .filter(AiChatSession.relation_id == rel_id)
            .count()
            == 1,
        )


def t_confirm_false_supplement_regenerates():
    with _env() as (h, db):
        sid, _ = _reach_confirming(db)
        # A 先确认，B 再要修改：A 的确认必须失效（旧稿的确认不能算在新稿上）
        mediation_service.confirm_rewrite(db, sid, A_ID, True)
        supplement = "补充：其实我想要的只是到家那句问候"

        # 桩换成「只改我这一侧」的返回，才能验证对方那侧是原样保留的
        def llm_mine_only(prompt, scene_key, client=None):
            if scene_key == "mediation_rewrite":
                return {
                    "rewrite_a": REWRITE_STUB["rewrite_a"],
                    "rewrite_b": "参与方的**新**改写",
                    "risk_level": "normal",
                }
            return dict(SUMMARY_STUB)

        h.driving(llm_mine_only)
        result = mediation_service.confirm_rewrite(
            db, sid, B_ID, False, supplement
        )
        check(
            "confirm(false) 立即受理（不等 LLM）",
            result.get("accepted") is True,
            str(result),
        )
        # API 进程不执行 LLM：重生成只入队，worker 跑完才回到 confirming
        ai_task_service.run_due_tasks(db, worker_id="test-worker")
        check(
            "confirm(false) 重新生成后回到 confirming",
            _db_status(db, sid).mediation_status == "confirming",
            _db_status(db, sid).mediation_status,
        )
        check(
            "改写被重新生成一次",
            len(h.rewrite_calls) == 2,
            str(h.llm_calls),
        )
        check(
            "重生成走的是独立任务类型（mediation_regenerate）",
            len(_tasks(db, sid, "mediation_regenerate")) == 1,
            str([(t.id, t.task_type) for t in _tasks(db, sid)]),
        )
        msgs = db.query(AiChatMessage).filter(
            AiChatMessage.session_id == sid,
            AiChatMessage.role == "user",
            AiChatMessage.content == supplement,
        ).all()
        check(
            "supplement 以作者身份落库",
            len(msgs) == 1 and msgs[0].user_id == B_ID,
            str([(m.user_id, m.content) for m in msgs]),
        )
        check("confirm(false) 不产 summary", h.summary_calls == [], str(h.llm_calls))

        # §8.5-5：只重生成 B 那一侧——A 侧文本必须与改写前逐字一致
        st_a = mediation_service.get_status(db, sid, A_ID)
        check(
            "A 侧改写被原样保留（不覆盖对方）",
            (st_a.get("my_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_a"],
            str(st_a.get("my_rewrite")),
        )
        st_b = mediation_service.get_status(db, sid, B_ID)
        check(
            "B 侧改写已更新",
            (st_b.get("my_rewrite") or {}).get("rewritten") == "参与方的**新**改写",
            str(st_b.get("my_rewrite")),
        )
        check(
            "重生成后 A 的确认被撤回（双方都要重新确认）",
            st_a.get("my_confirmed") is False and st_a.get("partner_confirmed") is False,
            str((st_a.get("my_confirmed"), st_a.get("partner_confirmed"))),
        )


def t_missing_own_rewrite_compensation_is_async_and_fails_loudly():
    """审查 H2 + 整改 B4.1-4：自己那一侧没有改写时，补生成走后台任务。

    修复前的两个毛病，本用例分别盯住：

    - **静默**：`_regenerate_rewrite` 吞掉改写异常，confirm(true) 继续走
      summarizing → 出现 completed 却整场没有改写的会话。
      现在：失败落成明确失败态（`rewrite_failed`）+ 失败码，**绝不产总结**。
    - **阻塞**：补生成此前同步跑在请求里（用户等 120s）。
      现在：请求立刻返回，会话进 rewriting，客户端继续轮询。
    """
    with _env() as (h, db):
        sid, _ = _reach_confirming(db)

        # 删掉已生成的改写，逼出 confirm(true) 的兜底重写路径
        db.query(AiChatMessage).filter(
            AiChatMessage.session_id == sid,
            AiChatMessage.role == "assistant",
        ).delete()
        db.commit()

        def broken_rewrite_llm(prompt, scene_key, client=None):
            if scene_key == "mediation_rewrite":
                raise RuntimeError("LLM 不可用（测试桩）")
            return dict(SUMMARY_STUB)

        h.driving(broken_rewrite_llm)
        # 只入队、不执行：这样才能确定地观察到「请求已返回、生成还没跑」这一刻
        calls_before = len(h.rewrite_calls)
        t0 = time.time()
        try:
            result = mediation_service.confirm_rewrite(db, sid, A_ID, True)
        except ValueError as e:
            check("补生成不再同步抛错（改为异步任务）", False, "意外抛 %s" % e)
            return
        elapsed = time.time() - t0
        check("补生成请求立即返回（不等 LLM）", elapsed < 0.25, "实际 %.3fs" % elapsed)
        check(
            "补生成把会话推进到 rewriting",
            result.get("mediation_status") == "rewriting",
            str(result),
        )
        check(
            "补生成只建一个「重生成」任务（不复制既有任务）",
            [(t.task_type, t.state) for t in _tasks(db, sid) if t.state == "pending"]
            == [("mediation_regenerate", "pending")],
            str([(t.id, t.task_type, t.state) for t in _tasks(db, sid)]),
        )
        check(
            "补生成返回时这一轮 LLM 还没被调用",
            len(h.rewrite_calls) == calls_before,
            str(h.llm_calls),
        )

        # 重试耗尽（每次失败都退避重排，本用例手动把退避拨到过去）
        from datetime import datetime, timedelta

        for _ in range(3):
            rows = _tasks(db, sid)
            if not rows:
                break
            rows[-1].next_retry_at = datetime.utcnow() - timedelta(seconds=1)
            db.commit()
            ai_task_service.run_due_tasks(db, worker_id="t")

        session = _db_status(db, sid)
        check(
            "改写失败后会话进明确失败态（不伪装成 confirming）",
            session.mediation_status == "rewrite_failed",
            session.mediation_status,
        )
        check(
            "重试耗尽后失败码是终态（TASK_EXHAUSTED）",
            session.mediation_failure_code == "TASK_EXHAUSTED",
            str(session.mediation_failure_code),
        )
        check(
            "不产总结（不会 completed 却无改写）",
            h.summary_calls == [],
            str(h.llm_calls),
        )
        check(
            "总结任务根本没被创建",
            _tasks(db, sid, "mediation_summary") == [],
            str(_tasks(db, sid)),
        )
        st = mediation_service.get_status(db, sid, A_ID)
        check(
            "GET {id} 暴露失败信息与重试入口",
            (st.get("failure") or {}).get("retryable") is True,
            str(st.get("failure")),
        )
        check(
            "失败态不丢用户原话（自己那一侧仍可读）",
            any((m.get("content") or "") == INPUT_A for m in st.get("messages") or []),
            str([(m.get("role"), (m.get("content") or "")[:20]) for m in st.get("messages") or []]),
        )


def t_confirm_false_failure_is_visible_not_silent():
    """confirm(false) 的重新改写失败：进失败态 + 可重试，绝不静默成功。"""
    with _env() as (h, db):
        sid, _ = _reach_confirming(db)

        def broken_rewrite_llm(prompt, scene_key, client=None):
            if scene_key == "mediation_rewrite":
                raise RuntimeError("LLM 不可用（测试桩）")
            return dict(SUMMARY_STUB)

        h.driving(broken_rewrite_llm)
        result = mediation_service.confirm_rewrite(db, sid, B_ID, False, "补充一句")
        check(
            "confirm(false) 立即受理（不阻塞等 LLM）",
            result.get("mediation_status") == "rewriting",
            str(result),
        )
        from datetime import datetime, timedelta

        for _ in range(3):
            rows = _tasks(db, sid, "mediation_regenerate")
            if not rows:
                break
            rows[-1].next_retry_at = datetime.utcnow() - timedelta(seconds=1)
            db.commit()
            ai_task_service.run_due_tasks(db, worker_id="t")

        session = _db_status(db, sid)
        check(
            "重生成失败后会话进明确失败态",
            session.mediation_status == "rewrite_failed",
            session.mediation_status,
        )
        check("失败不产总结", h.summary_calls == [], str(h.llm_calls))

        # 用户点重试 → 立刻受理（版本推进 + 新任务），worker 跑完才回到确认页
        h.driving(STUBS)
        retried = mediation_service.retry_generation(db, sid, B_ID)
        check(
            "重试立即受理并进入 rewriting（不阻塞等 LLM）",
            retried.get("mediation_status") == "rewriting",
            str(retried),
        )
        check("重试任务被执行", ai_task_service.run_due_tasks(db, worker_id="t") == 1)
        check(
            "重试成功后回到 confirming",
            _db_status(db, sid).mediation_status == "confirming",
            _db_status(db, sid).mediation_status,
        )


# --------------------------------------------------------------------- #
# 6. 列表端点语义（§2.3-3）
# --------------------------------------------------------------------- #
def t_list_roles():
    """列表端点语义（§2.3-3）。

    P0-5 之后 start **幂等**：同一 (relation, inviter) 只能有一场活跃调解。
    所以这里先把第一场跑成 completed（释放活跃槽位），才能再发一场新邀请——
    「同一发起者不能同时开两场」正是 start 幂等要保证的。
    """
    with _env() as (h, db):
        rel_id = _relation(db).id

        # 第一场：跑到 completed（结束的调解不占活跃槽位）
        sid_done = mediation_service.start_mediation(db, A_ID, rel_id)["session_id"]
        mediation_service.accept_mediation(db, sid_done, B_ID)
        mediation_service.submit_input(db, sid_done, A_ID, INPUT_A)
        mediation_service.submit_input(db, sid_done, B_ID, INPUT_B)
        ai_task_service.run_due_tasks(db, worker_id="test-worker")  # 改写
        mediation_service.confirm_rewrite(db, sid_done, A_ID, True)
        mediation_service.confirm_rewrite(db, sid_done, B_ID, True)
        ai_task_service.run_due_tasks(db, worker_id="test-worker")  # 总结 → completed
        check(
            "第一场调解已完成",
            _db_status(db, sid_done).mediation_status == "completed",
            _db_status(db, sid_done).mediation_status,
        )

        # 第二场：A 重新发起，B 未回应 → inviting（此刻的 invited 列表才有内容）
        started2 = mediation_service.start_mediation(db, A_ID, rel_id)
        check(
            "start 幂等：已完成的不阻塞新一场",
            started2["session_id"] != sid_done,
            str(started2),
        )
        invited_b = mediation_service.list_mediations(db, B_ID, "invited")
        check(
            "invited = partner 侧 inviting 会话",
            invited_b["total"] == 1
            and invited_b["items"][0]["session_id"] == started2["session_id"]
            and invited_b["items"][0]["my_role"] == "partner",
            str(invited_b),
        )

        invited_a = mediation_service.list_mediations(db, A_ID, "invited")
        check("invited 对发起方为空", invited_a["total"] == 0, str(invited_a))

        mine_a = mediation_service.list_mediations(db, A_ID, "mine")
        check(
            "mine = 我发起的会话（含未结束），my_role=inviter",
            mine_a["total"] == 2
            and all(i["my_role"] == "inviter" for i in mine_a["items"]),
            str(mine_a),
        )

        all_b = mediation_service.list_mediations(db, B_ID, "all")
        check(
            "all 含参与双方的会话，B 视角 my_role=partner",
            all_b["total"] == 2
            and all(i["my_role"] == "partner" for i in all_b["items"]),
            str(all_b),
        )

        # 拒绝邀请 → completed：§8.5-6「能回看」，不得从列表里消失
        mediation_service.reject_mediation(db, started2["session_id"], B_ID)
        mine_a2 = mediation_service.list_mediations(db, A_ID, "mine")
        check(
            "§8.5-6 已完成调解仍在 mine（可回看）",
            mine_a2["total"] == 2
            and any(
                i["session_id"] == started2["session_id"]
                and i["mediation_status"] == "completed"
                for i in mine_a2["items"]
            ),
            str(mine_a2),
        )
        hist_a = mediation_service.list_mediations(db, A_ID, "history")
        check(
            "history 只含已完成",
            hist_a["total"] == 2
            and {i["session_id"] for i in hist_a["items"]} == {sid_done, started2["session_id"]}
            and all(i["mediation_status"] == "completed" for i in hist_a["items"]),
            str(hist_a),
        )
        hist_b = mediation_service.list_mediations(db, B_ID, "history")
        check(
            "history 对参与方同样可见（我的角色=partner）",
            hist_b["total"] == 2
            and all(i["my_role"] == "partner" for i in hist_b["items"]),
            str(hist_b),
        )
        invited_b2 = mediation_service.list_mediations(db, B_ID, "invited")
        check(
            "completed 不出现在 invited（拒绝后不再提示待回应）",
            invited_b2["total"] == 0,
            str(invited_b2),
        )


# --------------------------------------------------------------------- #
# 7. 首页 active_mediation 过滤（§2.3-7）
# --------------------------------------------------------------------- #
def t_home_active_mediation_filter():
    with _env() as (h, db):
        rel = _relation(db)
        # 存量脏状态 in_progress（旧过滤条件恒命中的那个值其实永不写入——
        # 这里造一条证明新过滤不命中）
        db.add(AiChatSession(
            user_id=A_ID, relation_id=rel.id, scene_key="mediation",
            title="旧卡", privacy_level="couple", session_type="mediation",
            partner_user_id=B_ID, mediation_status="in_progress",
        ))
        db.commit()

        home = home_service._get_couple_home_data(db, A_ID, rel)
        check(
            "in_progress 不命中 active_mediation",
            home.get("active_mediation") is None,
            str(home.get("active_mediation")),
        )

        started = mediation_service.start_mediation(db, A_ID, rel.id)
        home2 = home_service._get_couple_home_data(db, A_ID, rel)
        am = home2.get("active_mediation")
        check(
            "inviting 命中 active_mediation",
            am is not None and am.get("id") == started["session_id"],
            str(am),
        )
        check(
            "active_mediation 带 my_role=inviter",
            am is not None and am.get("my_role") == "inviter",
            str(am),
        )
        check(
            "active_mediation 带 mediation_status",
            am is not None and am.get("mediation_status") == "inviting",
            str(am),
        )

        home_b = home_service._get_couple_home_data(db, B_ID, rel)
        am_b = home_b.get("active_mediation")
        check(
            "B 视角 my_role=partner",
            am_b is not None and am_b.get("my_role") == "partner",
            str(am_b),
        )

        # 整改 B4.1-4：生成失败态仍在首页可见（用户要能回去重试）
        mediation_service.accept_mediation(db, started["session_id"], B_ID)

        def broken_llm(prompt, scene_key, client=None):
            if scene_key == "mediation_rewrite":
                raise RuntimeError("LLM 不可用（测试桩）")
            return dict(SUMMARY_STUB)

        h.driving(broken_llm)
        mediation_service.submit_input(db, started["session_id"], B_ID, INPUT_B)
        mediation_service.submit_input(db, started["session_id"], A_ID, INPUT_A)
        from datetime import datetime, timedelta

        for _ in range(3):
            rows = _tasks(db, started["session_id"], "mediation_rewrite")
            if not rows:
                break
            rows[-1].next_retry_at = datetime.utcnow() - timedelta(seconds=1)
            db.commit()
            ai_task_service.run_due_tasks(db, worker_id="t")

        session = _db_status(db, started["session_id"])
        check(
            "生成失败确实落在会话上（首页可见性的前提）",
            session.mediation_status == "rewrite_failed",
            session.mediation_status,
        )
        home3 = home_service._get_couple_home_data(db, A_ID, rel)
        check(
            "失败态调解仍在首页（可回去重试）",
            home3.get("active_mediation") is not None
            and home3["active_mediation"]["id"] == started["session_id"],
            str(home3.get("active_mediation")),
        )


# --------------------------------------------------------------------- #
# 8. WS 源码契约（§2.3-4）
# --------------------------------------------------------------------- #
def t_ws_source_contract():
    from pathlib import Path
    import app.api.v1.couple.ws as ws_module

    src = Path(ws_module.__file__).read_text(encoding="utf-8")
    check("WS 接收 session_id 查询参数", "session_id: Optional[int] = Query(None)" in src)
    check("非成员 close 4003", "Not a member of this session" in src and "4003" in src)
    check("回声分支已删除（不再代执行动作）", "mediation_accept" not in src)
    check("心跳 ping 保留", '"type": "ping"' in src or "'type': 'ping'" in src)
    check("pong 协议保留", "pong" in src)


# --------------------------------------------------------------------- #
# 9. §8.5-6 已完成可重新查看
# --------------------------------------------------------------------- #
def _reach_completed(db):
    sid, _ = _reach_confirming(db, partner_first=False)
    mediation_service.confirm_rewrite(db, sid, A_ID, True)
    mediation_service.confirm_rewrite(db, sid, B_ID, True)
    # 双方确认后总结任务入队，显式跑 worker 完成生成
    ai_task_service.run_due_tasks(db, worker_id="test-worker")
    return sid


def t_completed_reviewable():
    with _env() as (h, db):
        sid = _reach_completed(db)
        check("会话已完成", _db_status(db, sid).mediation_status == "completed")
        for uid, label in ((A_ID, "发起方"), (B_ID, "参与方")):
            try:
                st = mediation_service.get_status(db, sid, uid)
            except ValueError as e:
                check("§8.5-6 completed 可读（%s）" % label, False, "抛 %s" % e)
                continue
            check(
                "§8.5-6 completed 可读（%s）" % label,
                st.get("mediation_status") == "completed",
                str(st.get("mediation_status")),
            )
            check(
                "completed 回看能拿到总结（%s）" % label,
                any(
                    (m.get("structured_output") or {}).get("common_points")
                    for m in st.get("messages") or []
                ),
                "无总结消息",
            )
        # 非成员越权仍然拒绝
        try:
            mediation_service.get_status(db, sid, 999)
        except ValueError as e:
            check("非成员读 completed 仍 50002", str(e) == "50002", "实际 %s" % e)
        else:
            check("非成员读 completed 仍 50002", False, "未抛 ValueError(50002)")


# --------------------------------------------------------------------- #
# 10. §8.5-9 四个真实角色场景
# --------------------------------------------------------------------- #
def t_continue_reopens_and_holds_slot():
    """「继续沟通」重新打开会话时必须**重新占用活跃槽位**。

    不变量：同一 (relation, inviter) 最多一场活跃调解。completed 时槽位已释放，
    若继续沟通不再占用，用户就能一边接着谈这一场、一边再发起新的一场。
    """
    with _env() as (h, db):
        sid = _reach_completed(db)
        rel_id = db.query(CoupleRelation).first().id
        check(
            "completed 时槽位已释放",
            _db_status(db, sid).mediation_active_slot is None,
            str(_db_status(db, sid).mediation_active_slot),
        )

        r = mediation_service.next_step(db, sid, A_ID, "continue")
        check("继续沟通回到 inputting", r["mediation_status"] == "inputting", str(r))
        check(
            "重新占用活跃槽位",
            _db_status(db, sid).mediation_active_slot == "%s:%s" % (rel_id, A_ID),
            str(_db_status(db, sid).mediation_active_slot),
        )
        again = mediation_service.start_mediation(db, A_ID, rel_id)
        check("继续沟通后 start 幂等返回同一场", again["session_id"] == sid, str(again))

        mediation_service.next_step(db, sid, A_ID, "end")
        check(
            "结束调解后槽位再次释放",
            _db_status(db, sid).mediation_active_slot is None,
            str(_db_status(db, sid).mediation_active_slot),
        )


def t_partner_first_order():
    """提交顺序相反：参与方先提交 → 第一方进等待态，第二方提交后才进改写。"""
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        sid = started["session_id"]
        mediation_service.accept_mediation(db, sid, B_ID)

        # 观察「第二方提交 → rewriting」这一段中间态：只入队，不执行
        first = mediation_service.submit_input(db, sid, B_ID, INPUT_B)
        check(
            "第一方（参与方）提交后停在等待态 inputting，不进确认页（§8.5-2）",
            first.get("mediation_status") == "inputting",
            str(first),
        )
        check(
            "第一方提交不触发改写",
            h.rewrite_calls == [],
            str(h.llm_calls),
        )
        # 契约里读侧的「对方是否提交」由 GET {id} 的 partner_submitted 承担：
        # 发起方 A 此刻还没提交，所以从 **A 的视角** 看 partner_submitted=true
        # （B 提交过）；从 B 的视角它恒为 false——因为 B 自己就是 partner 侧，
        # 「partner_submitted」问的是「对方（=发起方）提交没」。
        st_a0 = mediation_service.get_status(db, sid, A_ID)
        check(
            "第一方提交后发起方看到 partner_submitted=true",
            st_a0.get("partner_submitted") is True,
            str(st_a0.get("partner_submitted")),
        )
        check(
            "第一方提交后自己看不到对方发言（A 还没说话）",
            st_a0.get("messages") == [],
            str(st_a0.get("messages")),
        )

        second = mediation_service.submit_input(db, sid, A_ID, INPUT_A)
        check(
            "第二方提交后立即返回（§8.5-8 生成在后台任务里）",
            second.get("mediation_status") == "rewriting",
            str(second),
        )
        check(
            "改写任务已入队但尚未执行",
            len(_tasks(db, sid, "mediation_rewrite")) == 1
            and _tasks(db, sid, "mediation_rewrite")[0].state == "pending",
            str([(t.id, t.state) for t in _tasks(db, sid)]),
        )
        check(
            "第二方提交不等 LLM（改写还没跑）",
            h.rewrite_calls == [],
            str(h.llm_calls),
        )

        check("worker 执行改写任务", ai_task_service.run_due_tasks(db, worker_id="t") == 1)
        check(
            "改写由第二方提交触发，且只生成一次",
            len(h.rewrite_calls) == 1,
            str(h.llm_calls),
        )
        session = _db_status(db, sid)
        check(
            "改写完成后统一停在 confirming",
            session.mediation_status == "confirming",
            session.mediation_status,
        )
        st_b2 = mediation_service.get_status(db, sid, B_ID)
        check(
            "参与方身份解析不被提交顺序颠倒（B 拿到 rewrite_b）",
            (st_b2.get("my_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_b"],
            str(st_b2.get("my_rewrite")),
        )
        check(
            "参与方的 my_rewrite.original 是 B 的原话",
            (st_b2.get("my_rewrite") or {}).get("original") == INPUT_B,
            str(st_b2.get("my_rewrite")),
        )


def t_duplicate_submit():
    """重复提交：同一人多次提交不推进状态，输入按条落库。"""
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        sid = started["session_id"]
        mediation_service.accept_mediation(db, sid, B_ID)

        for _ in range(3):
            r = mediation_service.submit_input(db, sid, A_ID, INPUT_A)
            check(
                "同人重复提交始终停在 inputting",
                r.get("mediation_status") == "inputting",
                str(r),
            )
        check("重复提交不触发改写", h.rewrite_calls == [], str(h.llm_calls))
        mediation_service.submit_input(db, sid, B_ID, INPUT_B)
        # API 进程不执行 LLM：第二人提交只入队，由 worker 跑出改写
        ai_task_service.run_due_tasks(db, worker_id="test-worker")
        check(
            "第二个人提交后才生成改写（重复提交不加速）",
            len(h.rewrite_calls) == 1,
            str(h.llm_calls),
        )
        check(
            "只建一个改写任务",
            len(_tasks(db, sid)) == 1,
            str([(t.id, t.task_type) for t in _tasks(db, sid)]),
        )
        # 确认阶段重复点「确认」：第二次幂等短路，不产生第二个总结
        mediation_service.confirm_rewrite(db, sid, A_ID, True)
        mediation_service.confirm_rewrite(db, sid, A_ID, True)
        mediation_service.confirm_rewrite(db, sid, B_ID, True)
        # 双方确认只入队，总结由 worker 跑
        ai_task_service.run_due_tasks(db, worker_id="test-worker")
        check(
            "重复确认不产生第二个总结",
            len(h.summary_calls) == 1,
            str(h.llm_calls),
        )
        check(
            "只建一个总结任务",
            len(_tasks(db, sid, "mediation_summary")) == 1,
            str([(t.id, t.task_type) for t in _tasks(db, sid)]),
        )


def t_single_side_offline():
    """单方掉线：另一方的提交与确认照常推进，掉线方回来能按服务端状态续上。"""
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        sid = started["session_id"]
        mediation_service.accept_mediation(db, sid, B_ID)

        # B 提交后就「掉线」（不再有任何 B 的请求）
        mediation_service.submit_input(db, sid, B_ID, INPUT_B)
        mediation_service.submit_input(db, sid, A_ID, INPUT_A)
        # API 进程不执行 LLM：双方提交只入队，由 worker 跑出改写 → confirming
        ai_task_service.run_due_tasks(db, worker_id="test-worker")

        # A 全程不停：确认
        mediation_service.confirm_rewrite(db, sid, A_ID, True)
        session = _db_status(db, sid)
        check(
            "单方确认时会话不退化成完成（等 B）",
            session.mediation_status == "confirming",
            session.mediation_status,
        )

        # B 重新进入：按服务端状态恢复——拿到 confirming + 自己的改写 + 自己的确认态
        st_b = mediation_service.get_status(db, sid, B_ID)
        check(
            "掉线方重进看到 confirming（§8.5-7）",
            st_b.get("mediation_status") == "confirming",
            str(st_b.get("mediation_status")),
        )
        check(
            "掉线方重进知道对方已确认、自己还没",
            st_b.get("partner_confirmed") is True and st_b.get("my_confirmed") is False,
            str((st_b.get("my_confirmed"), st_b.get("partner_confirmed"))),
        )
        check(
            "掉线方重进能拿到自己的改写",
            (st_b.get("my_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_b"],
            str(st_b.get("my_rewrite")),
        )
        mediation_service.confirm_rewrite(db, sid, B_ID, True)
        # 双方确认只入队，总结由 worker 跑完才 completed
        ai_task_service.run_due_tasks(db, worker_id="test-worker")
        session2 = _db_status(db, sid)
        check(
            "掉线方补上确认后正常完成",
            session2.mediation_status == "completed",
            session2.mediation_status,
        )


def t_invite_rejected():
    """拒绝邀请：会话终止为 completed，双方不再能提交输入。"""
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        sid = started["session_id"]

        invited_b = mediation_service.list_mediations(db, B_ID, "invited")
        check(
            "B 能看到待回应邀请",
            invited_b["total"] == 1 and invited_b["items"][0]["session_id"] == sid,
            str(invited_b),
        )

        rejected = mediation_service.reject_mediation(db, sid, B_ID)
        check(
            "拒绝后状态 completed",
            rejected.get("mediation_status") == "completed",
            str(rejected),
        )
        invited_b2 = mediation_service.list_mediations(db, B_ID, "invited")
        check("拒绝后邀请列表清空", invited_b2["total"] == 0, str(invited_b2))

        try:
            mediation_service.submit_input(db, sid, B_ID, INPUT_B)
        except ValueError as e:
            check("拒绝后不能再提交输入", str(e) == "50003", "实际 %s" % e)
        else:
            check("拒绝后不能再提交输入", False, "未抛 ValueError(50003)")

        try:
            mediation_service.accept_mediation(db, sid, B_ID)
        except ValueError as e:
            check("已结束的邀请不能再接受", str(e) == "50003", "实际 %s" % e)
        else:
            check("已结束的邀请不能再接受", False, "未抛 ValueError(50003)")

        # 发起方视角：能回看这次被拒绝的会话（§8.5-6）
        st_a = mediation_service.get_status(db, sid, A_ID)
        check(
            "发起方仍能查看被拒绝的会话",
            st_a.get("mediation_status") == "completed",
            str(st_a.get("mediation_status")),
        )


def t_input_not_revealed_before_finish():
    """§8.5-3：inputting 阶段双方互不可见；篡改状态也拿不到对方原话。"""
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        sid = started["session_id"]
        mediation_service.accept_mediation(db, sid, B_ID)
        mediation_service.submit_input(db, sid, B_ID, INPUT_B)

        st_a = mediation_service.get_status(db, sid, A_ID)
        check(
            "inputting 时 A 拿不到 B 的输入",
            all(INPUT_B not in (m.get("content") or "") for m in st_a.get("messages") or []),
            str([(m.get("role"), (m.get("content") or "")[:20]) for m in st_a.get("messages") or []]),
        )
        check(
            "inputting 时 A 的 messages 为空（自己还没说话）",
            st_a.get("messages") == [],
            str(st_a.get("messages")),
        )
        check(
            "inputting 时 A 仍能看到「对方已提交」",
            st_a.get("partner_submitted") is True,
            str(st_a.get("partner_submitted")),
        )
        check(
            "未进改写时 my_rewrite 为空",
            st_a.get("my_rewrite") is None,
            str(st_a.get("my_rewrite")),
        )

        st_b = mediation_service.get_status(db, sid, B_ID)
        check(
            "inputting 时 B 能看到自己的输入",
            any((m.get("content") or "") == INPUT_B for m in st_b.get("messages") or []),
            str([(m.get("role"), (m.get("content") or "")[:20]) for m in st_b.get("messages") or []]),
        )

        # 进入 confirming 后仍不公开
        mediation_service.submit_input(db, sid, A_ID, INPUT_A)
        ai_task_service.run_due_tasks(db, worker_id="test-worker")  # 改写 → confirming
        st_a2 = mediation_service.get_status(db, sid, A_ID)
        check(
            "confirming 时 A 仍看不到 B 的原始输入",
            all(INPUT_B not in (m.get("content") or "") for m in st_a2.get("messages") or []),
            str([(m.get("role"), (m.get("content") or "")[:20]) for m in st_a2.get("messages") or []]),
        )


def t_generation_not_blocking():
    """§8.5-8：生成是**后台任务**——本用例关掉「踢一脚」，只入队不执行。

    请求必须立即返回（桩 LLM 慢 300ms，逻辑上不可能在请求里跑完），
    任务此时在库里是 pending；随后手动跑 worker（生产里调度器容器走的那条路），
    才看到改写落地、状态翻到 confirming。
    """
    with _env() as (h, db):
        rel = _relation(db)
        started = mediation_service.start_mediation(db, A_ID, rel.id)
        sid = started["session_id"]
        mediation_service.accept_mediation(db, sid, B_ID)
        mediation_service.submit_input(db, sid, B_ID, INPUT_B)

        def slow_llm(prompt, scene_key, client=None):
            time.sleep(0.3)
            return dict(STUBS[scene_key])

        h.driving(slow_llm)

        t0 = time.time()
        result = mediation_service.submit_input(db, sid, A_ID, INPUT_A)
        elapsed = time.time() - t0
        check(
            "第二方提交立即返回 rewriting（不等 LLM）",
            result.get("mediation_status") == "rewriting",
            str(result),
        )
        check(
            "请求耗时远小于一次 LLM 调用（< 0.25s）",
            elapsed < 0.25,
            "实际 %.3fs" % elapsed,
        )
        check(
            "返回时改写尚未生成（确实是异步的）",
            h.rewrite_calls == [],
            str(h.llm_calls),
        )
        check(
            "任务在库里等待执行（pending）",
            [t.state for t in _tasks(db, sid)] == ["pending"],
            str([(t.id, t.state) for t in _tasks(db, sid)]),
        )
        check(
            "会话状态是 rewriting（客户端应继续轮询）",
            _db_status(db, sid).mediation_status == "rewriting",
            _db_status(db, sid).mediation_status,
        )

        # 生产里由调度器容器（或请求侧那一脚）来执行
        check("worker 执行任务", ai_task_service.run_due_tasks(db, worker_id="scheduler") == 1)
        check(
            "任务完成后状态翻到 confirming",
            _db_status(db, sid).mediation_status == "confirming",
            _db_status(db, sid).mediation_status,
        )
        check(
            "worker 确实调用了改写 LLM",
            len(h.rewrite_calls) == 1,
            str(h.llm_calls),
        )


def main() -> int:
    print("[调解身份/状态机] 契约 §2.3/§2.4/§8.5 回归")
    t_start_my_role_and_notify()
    t_concurrent_start_single_mediation()
    t_start_idempotent_same_session()
    t_both_submitted_by_distinct_user()
    t_single_user_double_submit_not_confirming()
    t_get_status_identity_parsing()
    t_status_frames_broadcast()
    t_confirm_requires_both_sides()
    t_confirm_after_reveal_only_one_more()
    t_confirm_false_supplement_regenerates()
    t_missing_own_rewrite_compensation_is_async_and_fails_loudly()
    t_confirm_false_failure_is_visible_not_silent()
    t_list_roles()
    t_home_active_mediation_filter()
    t_ws_source_contract()
    t_completed_reviewable()
    t_continue_reopens_and_holds_slot()
    t_partner_first_order()
    t_duplicate_submit()
    t_single_side_offline()
    t_invite_rejected()
    t_input_not_revealed_before_finish()
    t_generation_not_blocking()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
