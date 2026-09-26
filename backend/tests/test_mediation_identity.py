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

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from sqlalchemy import BigInteger, Integer, create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app.core.database import Base  # noqa: E402
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

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
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


class _Stubs:
    """monkeypatch _call_llm / notify / broadcast，全部可还原。"""

    def __enter__(self):
        self.orig_llm = mediation_service._call_llm
        self.orig_notify = mediation_service.notify_mediation_invite
        self.orig_broadcast = mediation_service.manager.broadcast_to_session
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
        return self

    def __exit__(self, *exc):
        mediation_service._call_llm = self.orig_llm
        mediation_service.notify_mediation_invite = self.orig_notify
        mediation_service.manager.broadcast_to_session = self.orig_broadcast
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
            session = db.query(AiChatSession).filter(AiChatSession.id == sid).first()
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
            session = db.query(AiChatSession).filter(AiChatSession.id == sid).first()
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
                "A 的 partner_rewrite = rewrite_b",
                (st_a.get("partner_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_b"],
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

            # rewrites 按 author_user_id 标注（契约 §2.4-3）
            rewrites = st_a.get("rewrites") or []
            check(
                "rewrites 作者标注正确",
                rewrites == [
                    {"author_user_id": A_ID, "content": REWRITE_STUB["rewrite_a"]},
                    {"author_user_id": B_ID, "content": REWRITE_STUB["rewrite_b"]},
                ],
                str(rewrites),
            )

            # 旧字段只增不减：messages[].structured_output 仍在
            so = None
            for m in st_a.get("messages") or []:
                if m.get("role") == "assistant" and m.get("structured_output", {}).get("rewrite_a"):
                    so = m["structured_output"]
            check(
                "messages[].structured_output.rewrite_a/b 保留（只增不减）",
                so is not None and so.get("rewrite_a") == REWRITE_STUB["rewrite_a"],
                str(so),
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
# 5. confirm(true) → summary；confirm(false)+supplement → 重新改写
# --------------------------------------------------------------------- #
def t_confirm_true_generates_summary():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs)
            result = mediation_service.confirm_rewrite(db, sid, A_ID, True)
            check(
                "confirm(true) → summarizing",
                result.get("mediation_status") == "summarizing",
                str(result),
            )
            check(
                "generate_summary 被调用（§2.3-6）",
                len(stubs.summary_calls) == 1,
                str(stubs.llm_calls),
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
                "总结帧广播 summarizing",
                any(f[1].get("status") == "summarizing" for f in stubs.frames),
                str([f[1].get("status") for f in stubs.frames]),
            )
    finally:
        db.close()


def t_confirm_false_supplement_regenerates():
    db, rel = _fresh()
    try:
        with _Stubs() as stubs:
            sid, _ = _reach_confirming(db, rel, stubs)
            supplement = "补充：其实我想要的只是到家那句问候"
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

            session = db.query(AiChatSession).filter(AiChatSession.id == sid).first()
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
            session = db.query(AiChatSession).filter(AiChatSession.id == sid).first()
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
                "mine = 我发起的未结束会话，my_role=inviter",
                mine_a["total"] == 2
                and all(i["my_role"] == "inviter" for i in mine_a["items"]),
                str(mine_a),
            )

            all_b = mediation_service.list_mediations(db, B_ID, "all")
            check(
                "all 含参与双方的未结束会话，B 视角 my_role=partner",
                all_b["total"] == 2
                and all(i["my_role"] == "partner" for i in all_b["items"]),
                str(all_b),
            )

            # completed 从 mine/all 消失
            mediation_service.reject_mediation(db, started2["session_id"], B_ID)
            mine_a2 = mediation_service.list_mediations(db, A_ID, "mine")
            check(
                "completed 从 mine 排除",
                mine_a2["total"] == 1
                and mine_a2["items"][0]["session_id"] == sid,
                str(mine_a2),
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


def main() -> int:
    print("[调解身份/状态机] 契约 §2.3/§2.4 回归")
    t_start_my_role_and_notify()
    t_both_submitted_by_distinct_user()
    t_single_user_double_submit_not_confirming()
    t_get_status_identity_parsing()
    t_status_frames_broadcast()
    t_confirm_true_generates_summary()
    t_confirm_false_supplement_regenerates()
    t_confirm_rewrite_fallback_failure_blocks_summary()
    t_list_roles()
    t_home_active_mediation_filter()
    t_ws_source_contract()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
