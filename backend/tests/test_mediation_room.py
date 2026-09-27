"""共同调解室 · 逻辑自检（hermetic，SQLite，不依赖 MySQL/LLM）。

运行：
    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 python tests/test_mediation_room.py

覆盖：
1. 房间创建 + 风格注册表
2. @军师 pending mutex（占位唯一 + 忙时消息不丢）
3. 军师写回事务边界（消息+状态推进+pending 释放同 commit；token 单次有效）
4. 应答协议（同意→靠边；补充→作废+重开）
5. 结束调解投票（双方凑齐→settling）
6. 结算 worker（调解书+事件+双方观点落库，D-OPINION source 标注）
7. 调解书双方确认→settled
"""

import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("PYTHONUTF8", "1")

from sqlalchemy import Integer, create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.database import Base
import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata
from app.models.user import User
from app.models.couple_relation import CoupleRelation
from app.models import mediation_room as mr_models
from app.repositories import mediation_room_repo as room_repo
from app.services import mediation_room_service as svc
from app.services import room_settlement_service as settle_svc
from app.services import mediation_styles

# StaticPool：结算 worker 用独立会话，必须共享同一个内存库
_engine = create_engine(
    "sqlite:///:memory:",
    poolclass=StaticPool,
    connect_args={"check_same_thread": False},
)

# SQLite 只对 INTEGER PRIMARY KEY 自增（同 hermetic_harness 的做法）
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, Integer().__class__) and str(_c.type) == "BIGINT":
            _c.type = Integer()
Base.metadata.create_all(_engine)
TestSession = sessionmaker(bind=_engine, expire_on_commit=False)

_checks = []


def check(name, cond):
    _checks.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name)


def make_user(db, i):
    u = User(
        email="user%d@test.local" % i,
        password_hash="x",
        has_couple=True,
    )
    db.add(u)
    db.flush()
    return u


class FakeReq:
    def __init__(self, **kw):
        self.name = kw.get("name", "周末加班吵了一架")
        self.event_time = kw.get("event_time", "2026-09-27 晚上")
        self.cause_text = kw.get("cause_text", "临时被叫去加班")
        self.process_text = kw.get("process_text", "约好的电影没去成，双方都说了气话")
        self.current_text = kw.get("current_text", "冷战了一天，谁都没先开口")
        self.style_key = kw.get("style_key", "gentle_empathy")


def main():
    db = TestSession()
    ua = make_user(db, 1)
    ub = make_user(db, 2)
    rel = CoupleRelation(user_a_id=ua.id, user_b_id=ub.id, status="active")
    db.add(rel)
    db.commit()
    db.expire_all()

    # ---- 1. 创建房间 ----
    room = svc.create_room(db, relation=rel, user_id=ua.id, req=FakeReq())
    rid = room["id"]
    check("创建房间返回摘要", room["name"] == "周末加班吵了一架" and room["status"] == "active")
    check("风格注册表 MVP 两个", [s["key"] for s in mediation_styles.list_styles()] ==
          ["gentle_empathy", "rational_review"])

    # ---- 2. @军师 mutex ----
    r = svc.post_message(db, relation=rel, user_id=ua.id, room_id=rid,
                         content="这事到底怎么解决？@军师", mention=True)
    check("首召成功拿 token", r["advisor_claimed"] and r["token"])
    r2 = svc.post_message(db, relation=rel, user_id=ub.id, room_id=rid,
                          content="我也想说@军师", mention=True)
    check("并发召唤被拒（军师正在思考）", r2["advisor_busy"] and not r2["advisor_claimed"])
    check("忙时用户消息不丢", room_repo.get_messages(db, rid, after_id=0) and
          len(room_repo.get_messages(db, rid, after_id=0)) == 2)

    # ---- 3. 军师写回事务边界 ----
    token = r["token"]
    from app.services import room_advisor_service as advisor_svc
    advisor_svc.use_session_factory(TestSession)  # 测试钩子：写回用测试库
    msg = advisor_svc._write_back(db, rid, content="先各自把感受说完，我建议…", thinking="思考")
    fresh = room_repo.get_room(db, rid)
    check("写回：advisor 消息落库", msg is not None and msg.sender_type == "advisor")
    check("写回：round_no 推进 + waiting_reply", fresh.round_no == 1 and fresh.waiting_reply)
    check("写回：pending 已释放", room_repo.get_pending(db, rid) is None)
    msg2 = advisor_svc._write_back(db, rid, content="重复写回应被丢弃", thinking=None)
    check("token 单次有效：二次写回被丢弃", msg2 is None)
    check("丢弃未产生第二条 advisor 消息",
          len([m for m in room_repo.get_messages(db, rid, after_id=0)
               if m.sender_type == "advisor"]) == 1)

    # ---- 4. 应答协议 ----
    svc.agree(db, relation=rel, user_id=ua.id, room_id=rid)
    fresh = room_repo.get_room(db, rid)
    check("单方同意不靠边", fresh.waiting_reply and fresh.advisor_phase == "engaged")
    svc.agree(db, relation=rel, user_id=ub.id, room_id=rid)
    fresh = room_repo.get_room(db, rid)
    check("双方同意→军师靠边", fresh.advisor_phase == "aside" and not fresh.waiting_reply)

    r = svc.supplement(db, relation=rel, user_id=ub.id, room_id=rid, content="补充一点…")
    fresh = room_repo.get_room(db, rid)
    check("补充→本轮同意作废", not fresh.agree_user_a and not fresh.agree_user_b)
    check("补充→重新召唤成功", r["advisor_claimed"])

    # ---- 5. 结束调解投票 ----
    svc.post_message(db, relation=rel, user_id=ua.id, room_id=rid, content="普通消息", mention=False)
    o = svc.end_vote(db, relation=rel, user_id=ua.id, room_id=rid, action="vote")
    fresh = room_repo.get_room(db, rid)
    check("单方投票不结算", fresh.status == "active" and not o["both_voted"])
    o = svc.end_vote(db, relation=rel, user_id=ub.id, room_id=rid, action="vote")
    fresh = room_repo.get_room(db, rid)
    check("双方投票→settling", fresh.status == "settling" and o["both_voted"])
    try:
        svc.end_vote(db, relation=rel, user_id=ua.id, room_id=rid, action="cancel")
        check("settling 后不可再投票", False)
    except ValueError as e:
        check("settling 后不可再投票", str(e) == svc.ERR_BAD_STATE)

    # ---- 6. 结算 worker（伪造 LLM）----
    class FakeOut:
        result = "reconciled"
        summary_text = "双方已互相理解：加班沟通不足是核心。"
        agreements = ["下次加班提前 2 小时告知", "当晚复盘不翻旧账"]
        responsibility_a = "有情绪当天说出来"
        responsibility_b = "加班先报备"
        viewpoint_a = "我需要被优先考虑的感受被看见了。"
        viewpoint_b = "我意识到临时加班也要先沟通。"

    orig_invoke = settle_svc.llm.invoke_structured
    settle_svc.llm.invoke_structured = lambda *a, **kw: FakeOut()
    orig_sl = settle_svc.SessionLocal
    settle_svc.SessionLocal = TestSession  # 测试库会话工厂
    try:
        handled = settle_svc.run_due_settlements(db, "w1")
    finally:
        settle_svc.llm.invoke_structured = orig_invoke
        settle_svc.SessionLocal = orig_sl

    fresh = room_repo.get_room(db, rid)
    db.expire_all()  # 结算走独立会话，测试会话 identity map 需失效
    fresh = room_repo.get_room(db, rid)
    check("结算→settlement_ready", handled and fresh.status == "settlement_ready")
    check("调解书落库（结果=和解）", fresh.result == "reconciled" and fresh.settlement)
    import json as _json
    st = _json.loads(fresh.settlement)
    check("调解书含约定与双方责任",
          len(st["agreements"]) == 2 and st["responsibilities"]["user_a"])
    ev = db.query(mr_models.MediationEvent).filter_by(room_id=rid).first()
    check("吵架事件结构化落库", ev is not None and ev.result == "reconciled")
    diaries = db.query(mr_models.DiaryEntry).filter_by(source="mediation_room").all() \
        if hasattr(mr_models, "DiaryEntry") else []
    from app.models.diary_entry import DiaryEntry
    diaries = db.query(DiaryEntry).filter_by(source="mediation_room").all()
    check("双方观点落库（source=mediation_room）", len(diaries) == 2)
    check("观点 title 带调解室标注", all(d.title.startswith("调解室·") for d in diaries))

    # ---- 7. 双方确认结算 ----
    o = svc.settlement_confirm_action(db, relation=rel, user_id=ua.id, room_id=rid)
    check("单方确认不终局", not o["both_confirmed"])
    o = svc.settlement_confirm_action(db, relation=rel, user_id=ub.id, room_id=rid)
    fresh = room_repo.get_room(db, rid)
    check("双方确认→settled", o["both_confirmed"] and fresh.status == "settled")
    check("settled_at 回填 + 事件行同步", fresh.settled_at is not None and ev_settled(db, rid))

    # ---- 消息短轮询契约 ----
    out = svc.get_messages(db, relation=rel, user_id=ua.id, room_id=rid, after_id=0)
    check("短轮询返回 state 快照", out["state"]["my_role"] == "user_a"
          and out["state"]["status"] == "settled")

    # ---- 8. 性别称呼标签（09-28 军师叫错性别事故回归）----
    from app.services.party_labels import (
        NEUTRAL_A, NEUTRAL_B, gender_label, has_explicit_gender, party_labels,
    )
    from app.models.user_profile import UserProfile
    check("gender_label 映射", gender_label("男") == "男方" and gender_label("F") == "女方"
          and gender_label("不愿透露") is None and gender_label(None) is None)
    check("绑定校验：明确性别才放行",
          has_explicit_gender("男") and not has_explicit_gender("其他"))
    labels = party_labels(db, rel)
    check("未填 profile → 中性称呼",
          labels["user_a"] == NEUTRAL_A and labels["user_b"] == NEUTRAL_B)
    db.add(UserProfile(user_id=ua.id, gender="男"))
    db.add(UserProfile(user_id=ub.id, gender="女"))
    db.commit()
    db.expire_all()
    labels = party_labels(db, rel)
    check("填性别 → 男方/女方", labels["user_a"] == "男方" and labels["user_b"] == "女方")

    class _FakeMsg:
        def __init__(self, sender_type, content):
            self.sender_type = sender_type
            self.content = content

    rendered = advisor_svc._render_window(
        [_FakeMsg("user_a", "hi"), _FakeMsg("advisor", "yo")], labels)
    check("窗口渲染带性别标签", "[男方] hi" in rendered and "[军师] yo" in rendered)

    # ---- 9. 绑定性别强制（30008=自己未填 / 30009=对方未填）----
    from app.services import couple_service
    u3 = make_user(db, 3)  # 无 profile → 不能发邀请码
    try:
        couple_service.generate_invite_code(db, u3.id)
        check("未填性别不能发邀请码(30008)", False)
    except ValueError as e:
        check("未填性别不能发邀请码(30008)", str(e) == "30008")
    db.add(UserProfile(user_id=u3.id, gender="女"))
    db.commit()
    code_row = couple_service.generate_invite_code(db, u3.id)
    check("填了性别能发邀请码", bool(code_row["invite_code"]))
    u4 = make_user(db, 4)  # 绑定方无 profile → 绑定被拒
    try:
        couple_service.bind_couple(db, u4.id, code_row["invite_code"])
        check("绑定方未填性别被拒(30008)", False)
    except ValueError as e:
        check("绑定方未填性别被拒(30008)", str(e) == "30008")
    db.add(UserProfile(user_id=u4.id, gender="男"))
    db.commit()
    out = couple_service.bind_couple(db, u4.id, code_row["invite_code"])
    check("双方性别齐全绑定成功", out["user_a_id"] in (u3.id, u4.id))

    # ---- 10. 军师回复纯文本清洗（09-28 用户要求禁 #/* 等符号）----
    dirty = "#### 给双方的复述\n\n**女方这边**：你想要的不是「赢」，`代码`、~~删~~、3*4\n- 甲"
    clean = advisor_svc._strip_markdown(dirty)
    check("清洗：标题/粗体/代码/删除线/孤立符号全剥",
          all(ch not in clean for ch in "#*`~") and "女方这边" in clean and "3 4" not in clean)
    check("清洗：正文与换行保留", "给双方的复述" in clean and "\n\n" in clean and clean.startswith("给双方"))

    db.close()
    fails = [n for n, ok in _checks if not ok]
    print("\n== %d checks, %d failed ==" % (len(_checks), len(fails)))
    if fails:
        for n in fails:
            print("FAIL:", n)
        sys.exit(1)


def ev_settled(db, rid):
    ev = db.query(mr_models.MediationEvent).filter_by(room_id=rid).first()
    return ev is not None and ev.settled_at is not None


if __name__ == "__main__":
    main()
