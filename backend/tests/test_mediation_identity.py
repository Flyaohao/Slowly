# -*- coding: utf-8 -*-
"""调解身份 / 状态机回归（契约 §2.3/§2.4，SQLite 内存库，不需要 MySQL / API Key）。

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
5. confirm(true) 接线 `generate_summary` → 状态 summarizing；
   confirm(false) 带 supplement 落库并重新生成改写（契约 §2.3-6）；
   兜底改写失败 → 明确 50000 且停在 confirming，绝不推 summarizing（审查 H2）；
6. 状态变更 broadcast 到会话双方（契约 §2.3-4 的推送侧）；
7. 列表端点语义（契约 §2.3-3）：invited / mine / all；
8. 首页 active_mediation 过滤用真实状态集，`in_progress` 恒不命中（契约 §2.3-7）；
9. WS 源码契约：session_id 成员校验 close 4003、回声分支已删、心跳保留。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_mediation_identity.py
"""
import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from sqlalchemy import BigInteger, Integer, create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

from app.core.database import Base  # noqa: E402
import app.core.database as core_db  # noqa: E402  测试把 SessionLocal 绑到内存库
import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata

# SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()

from app.models.ai import AiChatMessage, AiChatSession  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.services import mediation_service  # noqa: E402
from app.services import home_service  # noqa: E402

SQLITE_PATH = os.path.join(HERE, "_mediation_test.sqlite3")
# 文件库而不是 :memory:：§8.5-8 之后生成跑在**另一个连接**上（后台线程或同步模式下
# 的 SessionLocal），:memory: 的每个连接都是独立的空库，后台线程会什么都读不到。
if os.path.exists(SQLITE_PATH):
    os.remove(SQLITE_PATH)
engine = create_engine(
    "sqlite:///%s" % SQLITE_PATH,
    connect_args={"check_same_thread": False, "timeout": 10},
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

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

INPUT_A = "这周我加班很晚回家，希望到家时能有句问候"
INPUT_B = "我这几天也很忙，其实一直惦记着你"


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def _fresh():
    """清空调解相关表，造一条 A↔B 的 active 关系。"""
    db = Session()
    db.query(AiChatMessage).delete()
    db.query(AiChatSession).delete()
    db.query(CoupleRelation).delete()
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return db, rel


def _wait_until(predicate, timeout=5.0):
    """轮询等待后台生成落地（只用于 §8.5-8 的**非阻塞**断言）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.02)
    return predicate()


def _db_status(db, session_id):
    """**重新读库**取会话状态。

    生成（改写/总结）现在由 SessionLocal 的另一个会话提交，本测试的 `db` 手里
    那份 ORM 对象不会自动跟着变（expire_on_commit=False）。直接拿它断言会读到
    过期值——PASS/FAIL 都不可信。所以状态一律经这里读。
    """
    db.expire_all()
    return db.query(AiChatSession).filter(AiChatSession.id == session_id).first()


class _Stubs:
    """monkeypatch _call_llm / notify / broadcast，全部可还原。

    同时把生成切到**同步模式**（`mediation_service.GENERATION_INLINE = True`）并把
    `SessionLocal` 绑到本文件的内存 SQLite：§8.5-8 之后改写/总结跑在后台线程里，
    不切换的话断言只能靠 sleep 赌线程调度——PASS 可能来自「还没跑到那一步」。
    """

    def __enter__(self):
        self.orig_llm = mediation_service._call_llm
        self.orig_notify = mediation_service.notify_mediation_invite
        self.orig_broadcast = mediation_service.manager.broadcast_to_session
        self.orig_inline = mediation_service.GENERATION_INLINE
        self.llm_calls = []
        self.notify_calls = []
        self.frames = []

        def fake_llm(prompt, scene_key):
            self.llm_calls.append(scene_key)
            return dict(REWRITE_STUB if scene_key == "mediation_rewrite" else SUMMARY_STUB)

        async def fake_notify(partner_id, session_id, inviter_name):
            self.notify_calls.append((partner_id, session_id, inviter_name))

        async def fake_broadcast(user_ids, message):
            self.frames.append((list(user_ids), dict(message)))

        mediation_service._call_llm = fake_llm
        mediation_service.notify_mediation_invite = fake_notify
        mediation_service.manager.broadcast_to_session = fake_broadcast
        mediation_service.GENERATION_INLINE = True
        core_db.SessionLocal.configure(bind=engine)
        return self

    def __exit__(self, *exc):
        mediation_service._call_llm = self.orig_llm
        mediation_service.notify_mediation_invite = self.orig_notify
        mediation_service.manager.broadcast_to_session = self.orig_broadcast
        mediation_service.GENERATION_INLINE = self.orig_inline
        return False

    @property
    def rewrite_calls(self):
        return [s for s in self.llm_calls if s == "mediation_rewrite"]

    @property
    def summary_calls(self):
        return [s for s in self.llm_calls if s == "mediation_summary"]


def _reach_confirming(db, rel, stubs, partner_first=True):
    """start → accept → 双方输入，停在 confirming（含改写）。"""
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
    return sid, started


# --------------------------------------------------------------------- #
# 1. start：my_role + 邀请通知
# --------------------------------------------------------------------- #
def t_start_my_role_and_notify():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            started = mediation_service.start_mediation(db, A_ID, rel.id)
            check("start 响应带 my_role=inviter", started.get("my_role") == "inviter", str(started))
            check("start 状态 inviting", started.get("mediation_status") == "inviting")
            check(
                "start 调用 notify_mediation_invite（§2.3-5）",
                len(stubs.notify_calls) == 1 and stubs.notify_calls[0][0] == B_ID,
                str(stubs.notify_calls),
            )
            if stubs.notify_calls:
                check(
                    "通知带 session_id",
                    stubs.notify_calls[0][1] == started["session_id"],
                    str(stubs.notify_calls),
                )
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 2+3. distinct user 判定 / 身份解析（伴侣先发言）
# --------------------------------------------------------------------- #
def t_both_submitted_by_distinct_user():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs, partner_first=True)
            session = _db_status(db, sid)
            check(
                "伴侣先发言 + 发起方后发言 → confirming",
                session.mediation_status == "confirming",
                session.mediation_status,
            )
            check(
                "同人双提交未提前触发（只生成 1 次改写）",
                len(stubs.rewrite_calls) == 1,
                str(stubs.llm_calls),
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
    finally:
        db.close()


def t_single_user_double_submit_not_confirming():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
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
            check("未进 confirming 时不调 LLM", stubs.llm_calls == [], str(stubs.llm_calls))
    finally:
        db.close()


def t_get_status_identity_parsing():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs, partner_first=True)

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
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 4. 状态帧广播（§2.3-4 推送侧）
# --------------------------------------------------------------------- #
def t_status_frames_broadcast():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs, partner_first=True)
            statuses = [f[1].get("status") for f in stubs.frames]
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
                all(f[0] == [A_ID, B_ID] for f in stubs.frames),
                str(stubs.frames[:2]),
            )
            check(
                "帧形状 type=mediation_status + session_id + status",
                all(
                    f[1].get("type") == "mediation_status"
                    and f[1].get("session_id") == sid
                    for f in stubs.frames
                ),
                str(stubs.frames[:1]),
            )
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 5. 双方各自确认 → summary（§8.5-5）
# --------------------------------------------------------------------- #
def t_confirm_requires_both_sides():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs)

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
                stubs.summary_calls == [],
                str(stubs.llm_calls),
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
                "双方确认 → summarizing",
                result.get("mediation_status") == "summarizing",
                str(result),
            )
            check(
                "双方确认后 generate_summary 被调用（§2.3-6）",
                len(stubs.summary_calls) == 1,
                str(stubs.llm_calls),
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
                any(f[1].get("status") == "summarizing" for f in stubs.frames),
                str([f[1].get("status") for f in stubs.frames]),
            )
            check(
                "completed 帧广播",
                any(f[1].get("status") == "completed" for f in stubs.frames),
                str([f[1].get("status") for f in stubs.frames]),
            )
    finally:
        db.close()


def t_confirm_after_reveal_only_one_more():
    """双方确认齐了就该直接 completed，不再接受第三个确认。"""
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs)
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            mediation_service.confirm_rewrite(db, sid, B_ID, True)
            try:
                mediation_service.confirm_rewrite(db, sid, A_ID, True)
            except ValueError as e:
                check("completed 后 confirm 被状态机拒绝", str(e) == "50003", "实际 %s" % e)
            else:
                check("completed 后 confirm 被状态机拒绝", False, "未抛 ValueError(50003)")
    finally:
        db.close()


def t_confirm_false_supplement_regenerates():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs)
            # A 先确认，B 再要修改：A 的确认必须失效（旧稿的确认不能算在新稿上）
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            supplement = "补充：其实我想要的只是到家那句问候"

            # 桩换成「只改我这一侧」的返回，才能验证对方那侧是原样保留的
            def llm_mine_only(prompt, scene_key):
                stubs.llm_calls.append(scene_key)
                if scene_key == "mediation_rewrite":
                    return {
                        "rewrite_a": REWRITE_STUB["rewrite_a"],
                        "rewrite_b": "参与方的**新**改写",
                        "risk_level": "normal",
                    }
                return dict(SUMMARY_STUB)

            mediation_service._call_llm = llm_mine_only
            result = mediation_service.confirm_rewrite(
                db, sid, B_ID, False, supplement
            )
            check(
                "confirm(false) 重新生成后回到 confirming",
                result.get("mediation_status") == "confirming",
                str(result),
            )
            check(
                "改写被重新生成一次",
                len(stubs.rewrite_calls) == 2,
                str(stubs.llm_calls),
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
            check("confirm(false) 不产 summary", stubs.summary_calls == [], str(stubs.llm_calls))

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
    finally:
        db.close()


def t_confirm_rewrite_fallback_failure_blocks_summary():
    """审查 H2 关联：兜底改写失败 → 停在 confirming + 50000，不推 summarizing。

    修复前 `_regenerate_rewrite` 吞掉改写异常，confirm(true) 继续走
    summarizing → 会出现 completed 却整场没有改写的会话。
    """
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs)

            # 删掉已生成的改写，逼出 confirm(true) 的兜底重写路径
            db.query(AiChatMessage).filter(
                AiChatMessage.session_id == sid,
                AiChatMessage.role == "assistant",
            ).delete()
            db.commit()

            def broken_rewrite_llm(prompt, scene_key):
                if scene_key == "mediation_rewrite":
                    raise RuntimeError("LLM 不可用（测试桩）")
                stubs.llm_calls.append(scene_key)
                return dict(SUMMARY_STUB)

            mediation_service._call_llm = broken_rewrite_llm
            try:
                mediation_service.confirm_rewrite(db, sid, A_ID, True)
            except ValueError as e:
                check("改写兜底失败 → 明确错误 50000",
                      str(e) == "50000", "实际 %s" % e)
            else:
                check("改写兜底失败 → 明确错误 50000", False, "未抛 ValueError(50000)")

            session = _db_status(db, sid)
            check(
                "停在 confirming（状态不推进）",
                session.mediation_status == "confirming",
                session.mediation_status,
            )
            check(
                "generate_summary 未被调用（不会 completed 却无改写）",
                stubs.summary_calls == [],
                str(stubs.llm_calls),
            )
            check(
                "失败后广播回到 confirming",
                any(f[1].get("status") == "confirming" for f in stubs.frames),
                str([f[1].get("status") for f in stubs.frames]),
            )

            # confirm(false) 的重新改写失败路径同样不再静默成功
            try:
                mediation_service.confirm_rewrite(db, sid, B_ID, False, "补充一句")
            except ValueError as e:
                check("confirm(false) 改写失败 → 明确错误 50000",
                      str(e) == "50000", "实际 %s" % e)
            else:
                check("confirm(false) 改写失败 → 明确错误 50000",
                      False, "未抛 ValueError(50000)")
            session = _db_status(db, sid)
            check(
                "confirm(false) 失败后仍在 confirming",
                session.mediation_status == "confirming",
                session.mediation_status,
            )
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 6. 列表端点语义（§2.3-3）
# --------------------------------------------------------------------- #
def t_list_roles():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs, partner_first=True)

            invited_b = mediation_service.list_mediations(db, B_ID, "invited")
            # 已进 confirming，不再是 inviting；先造一条新邀请
            started2 = mediation_service.start_mediation(db, A_ID, rel.id)
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
                hist_a["total"] == 1
                and hist_a["items"][0]["session_id"] == started2["session_id"],
                str(hist_a),
            )
            hist_b = mediation_service.list_mediations(db, B_ID, "history")
            check(
                "history 对参与方同样可见（我的角色=partner）",
                hist_b["total"] == 1
                and hist_b["items"][0]["my_role"] == "partner",
                str(hist_b),
            )
            invited_b2 = mediation_service.list_mediations(db, B_ID, "invited")
            check(
                "completed 不出现在 invited（拒绝后不再提示待回应）",
                invited_b2["total"] == 0,
                str(invited_b2),
            )
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 7. 首页 active_mediation 过滤（§2.3-7）
# --------------------------------------------------------------------- #
def t_home_active_mediation_filter():
    db, rel = _fresh()
    try:
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
    finally:
        db.close()


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
def _reach_completed(db, rel, stubs):
    sid, _ = _reach_confirming(db, rel, stubs, partner_first=False)
    mediation_service.confirm_rewrite(db, sid, A_ID, True)
    mediation_service.confirm_rewrite(db, sid, B_ID, True)
    return sid


def t_completed_reviewable():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid = _reach_completed(db, rel, stubs)
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
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 10. §8.5-9 四个真实角色场景
# --------------------------------------------------------------------- #
def t_partner_first_order():
    """提交顺序相反：参与方先提交 → 第一方进等待态，第二方提交后才进改写。"""
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            started = mediation_service.start_mediation(db, A_ID, rel.id)
            sid = started["session_id"]
            mediation_service.accept_mediation(db, sid, B_ID)

            first = mediation_service.submit_input(db, sid, B_ID, INPUT_B)
            check(
                "第一方（参与方）提交后停在等待态 inputting，不进确认页（§8.5-2）",
                first.get("mediation_status") == "inputting",
                str(first),
            )
            check(
                "第一方提交不触发改写",
                stubs.rewrite_calls == [],
                str(stubs.llm_calls),
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
                "第二方提交后进入 rewriting（§8.5-8 后台生成）",
                second.get("mediation_status") in ("rewriting", "confirming"),
                str(second),
            )
            check(
                "改写由 A 的提交触发，且只生成一次",
                len(stubs.rewrite_calls) == 1,
                str(stubs.llm_calls),
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
    finally:
        db.close()


def t_duplicate_submit():
    """重复提交：同一人多次提交不推进状态，输入按条落库。"""
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
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
            check("重复提交不触发改写", stubs.rewrite_calls == [], str(stubs.llm_calls))
            mediation_service.submit_input(db, sid, B_ID, INPUT_B)
            check(
                "第二个人提交后才生成改写（重复提交不加速）",
                len(stubs.rewrite_calls) == 1,
                str(stubs.llm_calls),
            )
            # 确认阶段重复点「确认」：第二次被状态机拒绝，不产生第二个总结
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            mediation_service.confirm_rewrite(db, sid, B_ID, True)
            check(
                "重复确认不产生第二个总结",
                len(stubs.summary_calls) == 1,
                str(stubs.llm_calls),
            )
    finally:
        db.close()


def t_single_side_offline():
    """单方掉线：另一方的提交与确认照常推进，掉线方回来能按服务端状态续上。"""
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            started = mediation_service.start_mediation(db, A_ID, rel.id)
            sid = started["session_id"]
            mediation_service.accept_mediation(db, sid, B_ID)

            # B 提交后就「掉线」（不再有任何 B 的请求）
            mediation_service.submit_input(db, sid, B_ID, INPUT_B)
            mediation_service.submit_input(db, sid, A_ID, INPUT_A)

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
            session2 = _db_status(db, sid)
            check(
                "掉线方补上确认后正常完成",
                session2.mediation_status == "completed",
                session2.mediation_status,
            )
    finally:
        db.close()


def t_invite_rejected():
    """拒绝邀请：会话终止为 completed，双方不再能提交输入。"""
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
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
    finally:
        db.close()


def t_input_not_revealed_before_finish():
    """§8.5-3：inputting 阶段双方互不可见；篡改状态也拿不到对方原话。"""
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
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
            st_a2 = mediation_service.get_status(db, sid, A_ID)
            check(
                "confirming 时 A 仍看不到 B 的原始输入",
                all(INPUT_B not in (m.get("content") or "") for m in st_a2.get("messages") or []),
                str([(m.get("role"), (m.get("content") or "")[:20]) for m in st_a2.get("messages") or []]),
            )
    finally:
        db.close()


def t_generation_not_blocking():
    """§8.5-8：后台生成是异步的——本用例**故意关掉**同步开关。

    真实线程路径下，「第二方提交」必须**立即**返回 rewriting，不能等 LLM
    （这里让桩 LLM 慢 300ms）；生成完成后再由后台线程把状态翻到 confirming。
    """
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            mediation_service.GENERATION_INLINE = False
            started = mediation_service.start_mediation(db, A_ID, rel.id)
            sid = started["session_id"]
            mediation_service.accept_mediation(db, sid, B_ID)
            mediation_service.submit_input(db, sid, B_ID, INPUT_B)

            def slow_llm(prompt, scene_key):
                stubs.llm_calls.append(scene_key)
                time.sleep(0.3)
                return dict(REWRITE_STUB if scene_key == "mediation_rewrite" else SUMMARY_STUB)

            mediation_service._call_llm = slow_llm
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
                stubs.rewrite_calls == [],
                str(stubs.llm_calls),
            )

            def _status():
                s = _db_status(db, sid)
                db.expire_all()
                return s.mediation_status

            check(
                "后台线程完成后状态翻到 confirming",
                _wait_until(lambda: _status() == "confirming", timeout=5.0),
                _status(),
            )
            check(
                "后台线程确实调用了改写 LLM",
                len(stubs.rewrite_calls) >= 1,
                str(stubs.llm_calls),
            )
    finally:
        db.close()


def main() -> int:
    print("[调解身份/状态机] 契约 §2.3/§2.4/§8.5 回归")
    t_start_my_role_and_notify()
    t_both_submitted_by_distinct_user()
    t_single_user_double_submit_not_confirming()
    t_get_status_identity_parsing()
    t_status_frames_broadcast()
    t_confirm_requires_both_sides()
    t_confirm_after_reveal_only_one_more()
    t_confirm_false_supplement_regenerates()
    t_confirm_rewrite_fallback_failure_blocks_summary()
    t_list_roles()
    t_home_active_mediation_filter()
    t_ws_source_contract()
    t_completed_reviewable()
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
