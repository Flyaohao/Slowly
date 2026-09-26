# -*- coding: utf-8 -*-
"""W4 端点回归（契约 §3.1/§3.3/§3.4，SQLite 内存库，不需要 MySQL / API Key）。

## 覆盖什么

1. **§3.3 军师设置**：`GET/PUT /api/v1/advisor/settings` 同构读写、
   单人模式 GET 默认值 / PUT 30005、枚举非法 → 10002、`address_name`
   永不为 null（FE 非空 Kotlin String）、`show_evidence=false` 真落库；
2. **§3.4 反馈闭环**：`FeedbackRequest` 可选 `adopted/outcome` 扩展写入
   （旧客户端只传 rating 照常 → 两列 NULL）、
   `GET /couple/ai/feedback/pending` 近 N 天「有建议但无 outcome」过滤；
3. **§3.1 首页任务卡**：`GET /home` 新增 `task_cards`（契约顺序、每类至多
   1 张、route 字符串），旧字段只增不减；单人侧 `task_cards: []`；
4. **§3.3 prompt 注入**：`build_persona_instruction` 的 keyword-only 设置段
   ——基线不产出文本，偏离基线才追加。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_w4_endpoints.py
"""
import os
import sys
from datetime import datetime, timedelta

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

from app.models.user import User  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.models.letter import Letter  # noqa: E402
from app.models.ai import AiChatSession, AiChatMessage, AiOutputFeedback  # noqa: E402
from app.models.dual_perspective import (  # noqa: E402
    DualPerspectiveEvent,
    DualPerspectiveRecord,
)
from app.models.profile import RelationshipProfile  # noqa: E402
from app.repositories import ai_repo  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101  # 我（有绑定）
B_ID = 202  # 伴侣
C_ID = 303  # 单人模式用户


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def _mk_user(db, uid: int) -> User:
    u = User(id=uid, email="u%d@example.com" % uid, password_hash="x", has_couple=True)
    db.add(u)
    return u


def _mk_session(db, sid: int, user_id: int, relation_id: int, **kw) -> AiChatSession:
    s = AiChatSession(
        id=sid,
        user_id=user_id,
        relation_id=relation_id,
        scene_key=kw.pop("scene_key", "chat"),
        **kw,
    )
    db.add(s)
    return s


def _mk_msg(db, mid: int, session_id: int, role="assistant", **kw) -> AiChatMessage:
    m = AiChatMessage(
        id=mid, session_id=session_id, role=role, content=kw.pop("content", "内容")
    )
    for k, v in kw.items():
        setattr(m, k, v)
    db.add(m)
    return m


IDS = {}


def seed() -> Session:
    """一次性铺全量夹具；各 t_* 按序消费/变异。"""
    db = Session()
    db.query(AiOutputFeedback).delete()
    db.query(AiChatMessage).delete()
    db.query(AiChatSession).delete()
    db.query(DualPerspectiveRecord).delete()
    db.query(DualPerspectiveEvent).delete()
    db.query(Letter).delete()
    db.query(RelationshipProfile).delete()
    db.query(CoupleRelation).delete()
    db.query(User).delete()
    db.commit()

    _mk_user(db, A_ID)
    _mk_user(db, B_ID)
    c = _mk_user(db, C_ID)
    c.has_couple = False
    rel = CoupleRelation(
        user_a_id=A_ID, user_b_id=B_ID, status="active",
        bind_time=datetime.utcnow() - timedelta(days=100),
    )
    db.add(rel)
    db.commit()
    db.refresh(rel)

    # 待回信（B→A，普通已发送）
    letter = Letter(
        relation_id=rel.id, sender_id=B_ID, receiver_id=A_ID,
        title="手写信", content="正文", letter_type="normal",
        status="sent", send_time=datetime.utcnow(),
    )
    db.add(letter)

    # 调解邀请：B 邀 A（应出卡）
    _mk_session(
        db, 901, B_ID, rel.id, scene_key="mediation",
        session_type="mediation", partner_user_id=A_ID,
        mediation_status="inviting", title="双人调解",
    )
    # 我发起、等对方接受（FE 固定拼 isInviter=false → 绝不能出卡）
    _mk_session(
        db, 902, A_ID, rel.id, scene_key="mediation",
        session_type="mediation", partner_user_id=B_ID,
        mediation_status="inviting", title="双人调解",
    )

    # 双视角：E1 我未提交（出卡）；E2 我已提交；E3 双方完成态
    now = datetime.utcnow()
    e1 = DualPerspectiveEvent(
        id=701, relation_id=rel.id, title="周末爬山",
        event_time=now - timedelta(days=1), status="one_side",
    )
    e2 = DualPerspectiveEvent(
        id=702, relation_id=rel.id, title="谁洗碗",
        event_time=now - timedelta(days=2), status="one_side",
    )
    e3 = DualPerspectiveEvent(
        id=703, relation_id=rel.id, title="见家长",
        event_time=now - timedelta(days=3), status="both_sides",
    )
    db.add_all([e1, e2, e3])
    db.flush()
    db.add(
        DualPerspectiveRecord(
            event_id=e2.id, user_id=A_ID, content="我觉得…", visibility="hidden"
        )
    )

    # 反馈夹具
    # S_FB1：有建议、无 outcome → pending（+首页卡候选）
    _mk_session(db, 501, A_ID, rel.id)
    _mk_msg(db, 601, 501)
    ai_repo.create_feedback(db, 601, A_ID, 5, "有用", "谢谢", adopted=None, outcome=None)
    # S_FB2：已有 outcome → 排除
    _mk_session(db, 502, A_ID, rel.id)
    _mk_msg(db, 602, 502)
    ai_repo.create_feedback(db, 602, A_ID, 4, None, None, adopted=True, outcome="已和好")
    # S_FB3：消息 10 天前 → 窗口外
    _mk_session(db, 503, A_ID, rel.id)
    m3 = _mk_msg(db, 603, 503)
    m3.created_at = datetime.utcnow() - timedelta(days=10)
    ai_repo.create_feedback(db, 603, A_ID, 3, None, None, adopted=None, outcome=None)
    # S_FB4：伴侣的反馈 → 不进我的列表
    _mk_session(db, 504, B_ID, rel.id)
    _mk_msg(db, 604, 504)
    ai_repo.create_feedback(db, 604, B_ID, 5, None, None, adopted=None, outcome=None)
    # S_FB5/S_FB6：给 API 提交用（各需一条 assistant 消息）
    _mk_session(db, 505, A_ID, rel.id)
    _mk_msg(db, 605, 505)
    _mk_session(db, 506, A_ID, rel.id)
    _mk_msg(db, 606, 506)
    # S_FB7/S_FB8：给服务层 submit_feedback 用
    _mk_session(db, 507, A_ID, rel.id)
    _mk_msg(db, 607, 507)
    _mk_session(db, 508, A_ID, rel.id)
    _mk_msg(db, 608, 508)

    db.commit()
    IDS.update(
        rel_id=rel.id,
        letter_id=letter.id,
    )
    return db


def _fresh_client(db: Session, uid: int):
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.database import get_db
    from app.core.dependencies import get_current_user

    current = type("U", (), {"id": uid})()
    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_current_user] = lambda: current
    return TestClient(app, raise_server_exceptions=False), app, get_db, get_current_user


def _drop_client(app, get_db, get_current_user):
    app.dependency_overrides.pop(get_db, None)
    app.dependency_overrides.pop(get_current_user, None)


# --------------------------------------------------------------------- #
# 1. §3.4 服务层写入：扩展字段落库 / 旧形状回退
# --------------------------------------------------------------------- #
def t_feedback_write(db: Session):
    from app.services import ai_service

    # 带 adopted/outcome 的完整提交（会话 507 / assistant 消息 607 已 seed）
    ai_service.submit_feedback(
        db, A_ID, 507, 607,
        {"rating": 5, "feedback_tag": "有用", "feedback_text": "采纳了",
         "adopted": True, "outcome": "对方主动道歉了"},
    )
    row = (
        db.query(AiOutputFeedback)
        .join(AiChatMessage, AiOutputFeedback.message_id == AiChatMessage.id)
        .filter(AiChatMessage.session_id == 507)
        .first()
    )
    check("带 adopted/outcome 的写入成功", row is not None, str(row))
    check("adopted=True 落库", row is not None and row.adopted is True, str(row))
    check("outcome 落库", row is not None and row.outcome == "对方主动道歉了", str(row))

    # 旧形状（只 rating）→ 两列 NULL，照常工作
    ai_service.submit_feedback(
        db, A_ID, 508, 608, {"rating": 3, "feedback_tag": None, "feedback_text": None}
    )
    row2 = (
        db.query(AiOutputFeedback)
        .join(AiChatMessage, AiOutputFeedback.message_id == AiChatMessage.id)
        .filter(AiChatMessage.session_id == 508)
        .first()
    )
    check("旧形状（只 rating）写入成功", row2 is not None, str(row2))
    check("旧形状 adopted 均 NULL",
          row2 is not None and row2.adopted is None and row2.outcome is None, str(row2))


# --------------------------------------------------------------------- #
# 2. §3.4 pending 读取
# --------------------------------------------------------------------- #
def t_feedback_pending(db: Session):
    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.get("/api/v1/couple/ai/feedback/pending")
        body = r.json()
        data = body.get("data") or {}
        items = data.get("items") or []
        session_ids = {i.get("session_id") for i in items}

        check(
            "pending → 200 + code 0",
            r.status_code == 200 and body.get("code") == 0,
            "%s %s" % (r.status_code, str(body)[:160]),
        )
        # 期望：501（种子无 outcome）+ t_feedback_write 的 508（旧形状行）
        #       502 有 outcome、503 窗口外、504 是伴侣的、507 有 outcome → 全排除
        check(
            "pending 只含「无 outcome + 近 7 天 + 我的会话」",
            session_ids == {501, 508},
            str(sorted(session_ids)),
        )
        check("total 与 items 长度一致", data.get("total") == len(items), str(data.get("total")))
        if items:
            keys = set(items[0].keys())
            need = {"session_id", "scene_key", "title", "rating", "adopted",
                    "outcome", "created_at", "message_id"}
            check("item 字段齐全", need <= keys, str(sorted(keys)))
            check("pending 行 outcome 恒 None", all(i.get("outcome") is None for i in items))

        # days 参数边界
        r2 = client.get("/api/v1/couple/ai/feedback/pending?days=0")
        check("days<1 → 参数校验 10002", r2.json().get("code") == 10002, str(r2.json())[:160])
    finally:
        _drop_client(app, g_db, g_user)


# --------------------------------------------------------------------- #
# 3. §3.4 API 写入：POST feedback 携带 adopted/outcome
# --------------------------------------------------------------------- #
def t_feedback_post_api(db: Session):
    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.post(
            "/api/v1/couple/ai/sessions/505/feedback",
            json={"rating": 5, "feedback_tag": "准", "feedback_text": None,
                  "adopted": False, "outcome": None},
        )
        check(
            "POST feedback（带 adopted=False）→ 200 + code 0",
            r.status_code == 200 and r.json().get("code") == 0,
            "%s %s" % (r.status_code, str(r.json())[:160]),
        )
        row505 = (
            db.query(AiOutputFeedback)
            .join(AiChatMessage, AiOutputFeedback.message_id == AiChatMessage.id)
            .filter(AiChatMessage.session_id == 505)
            .first()
        )
        check("adopted=False 真落库（未被 None 跳过）",
              row505 is not None and row505.adopted is False, str(row505))
        # 整改 §8.3：明确「未采用」= 无需回访 → 不进 pending
        check("outcome=None 保持 NULL（但 adopted=False 不回访）",
              row505 is not None and row505.outcome is None, str(row505))

        # 旧 APK 形状：只有 rating
        r2 = client.post(
            "/api/v1/couple/ai/sessions/506/feedback",
            json={"rating": 4},
        )
        check("旧形状只传 rating 照常写入",
              r2.status_code == 200 and r2.json().get("code") == 0,
              str(r2.json())[:160])
        row506 = (
            db.query(AiOutputFeedback)
            .join(AiChatMessage, AiOutputFeedback.message_id == AiChatMessage.id)
            .filter(AiChatMessage.session_id == 506)
            .first()
        )
        check("旧形状 adopted/outcome 均 NULL",
              row506 is not None and row506.adopted is None and row506.outcome is None,
              str(row506))

        # 列表随之增长：501 + 508（服务层旧形状）+ 506（API 旧形状）
        # —— 505 是 adopted=False（未采用），整改 §8.3 后不进 pending
        r3 = client.get("/api/v1/couple/ai/feedback/pending")
        ids3 = {i.get("session_id") for i in (r3.json().get("data") or {}).get("items", [])}
        check("新增无 outcome 行进入 pending（adopted=False 除外）",
              ids3 == {501, 506, 508}, str(sorted(ids3)))
    finally:
        _drop_client(app, g_db, g_user)


# --------------------------------------------------------------------- #
# 4. §3.3 军师设置 GET/PUT
# --------------------------------------------------------------------- #
def t_advisor_settings(db: Session):
    # ---- 单人模式（C）：GET 默认值 / PUT 30005 ----
    client, app, g_db, g_user = _fresh_client(db, C_ID)
    try:
        r = client.get("/api/v1/advisor/settings")
        data = r.json().get("data") or {}
        check("单人 GET → 200 + code 0",
              r.status_code == 200 and r.json().get("code") == 0, str(r.json())[:160])
        check("address_name 为 str（非 null）", data.get("address_name") == "",
              repr(data.get("address_name")))
        check("默认枚举与 FE 同构",
              data.get("detail_level") == "standard"
              and data.get("proactivity") == "moderate"
              and data.get("show_evidence") is True
              and data.get("voice_style") == "gentle",
              str(data))
        check("GET 恰好 5 字段（同构契约）", set(data) == {
            "address_name", "detail_level", "proactivity", "show_evidence",
            "voice_style"}, str(set(data)))

        r2 = client.put("/api/v1/advisor/settings",
                        json={"address_name": "宝宝"})
        check("单人 PUT → 30005（归一化 200）",
              r2.status_code == 200 and r2.json().get("code") == 30005,
              "%s %s" % (r2.status_code, str(r2.json())[:160]))
    finally:
        _drop_client(app, g_db, g_user)

    # ---- 双人模式（A）：写入 / 回读同构 ----
    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        payload = {
            "address_name": "宝宝",
            "detail_level": "brief",
            "proactivity": "active",
            "show_evidence": False,
            "voice_style": "direct",
        }
        r = client.put("/api/v1/advisor/settings", json=payload)
        body = r.json()
        check("PUT 全量 → 200 + code 0",
              r.status_code == 200 and body.get("code") == 0,
              "%s %s" % (r.status_code, str(body)[:160]))
        check("PUT 回显同构", body.get("data") == payload, str(body.get("data")))

        rg = client.get("/api/v1/advisor/settings")
        check("GET → PUT 同构（回读一致）",
              (rg.json().get("data") or {}) == payload,
              str(rg.json().get("data")))
        check("回读 address_name 非 null",
              (rg.json().get("data") or {}).get("address_name") == "宝宝")

        # 部分更新：只动 detail_level，其余不动；False 不被跳过
        r3 = client.put("/api/v1/advisor/settings", json={"detail_level": "detailed"})
        d3 = r3.json().get("data") or {}
        check("部分 PUT 只改提交字段",
              d3.get("detail_level") == "detailed"
              and d3.get("address_name") == "宝宝"
              and d3.get("show_evidence") is False,
              str(d3))

        # 空串清空称呼（同构回读为 ""）
        r4 = client.put("/api/v1/advisor/settings", json={"address_name": ""})
        check("空串清空 address_name",
              (r4.json().get("data") or {}).get("address_name") == "",
              str(r4.json().get("data")))

        # 非法枚举 → 归一化 10002
        r5 = client.put("/api/v1/advisor/settings", json={"detail_level": "verbose"})
        check("非法枚举 → 10002",
              r5.status_code == 200 and r5.json().get("code") == 10002,
              "%s %s" % (r5.status_code, str(r5.json())[:160]))
    finally:
        _drop_client(app, g_db, g_user)


# --------------------------------------------------------------------- #
# 5. §3.1 首页任务卡（couple）
# --------------------------------------------------------------------- #
def t_home_task_cards(db: Session):
    from app.services import home_service

    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.get("/api/v1/home")
        body = r.json()
        data = body.get("data") or {}
        check("GET /home → 200 + code 0",
              r.status_code == 200 and body.get("code") == 0,
              "%s %s" % (r.status_code, str(body)[:160]))

        # 旧字段只增不减
        for key in ("mode", "relation", "avatar", "pending_letters",
                    "pending_letter_count", "active_mediation", "future_letter",
                    "recent_museum_items", "recommended_practices",
                    "upcoming_anniversary", "space", "task_cards"):
            check("旧/新字段保留：%s" % key, key in data, str(sorted(data)))

        cards = data.get("task_cards") or []
        types = [c.get("type") for c in cards]
        check(
            "task_cards 顺序 = 契约 §3.1 优先级",
            types == ["mediation_invite", "dual_perspective", "pending_letter",
                      "feedback_outcome", "questionnaire"],
            str(types),
        )
        by_type = {c.get("type"): c for c in cards}

        inv = by_type.get("mediation_invite") or {}
        check("调解卡 = B 邀我的那场（901，非我发起的 902）",
              inv.get("id") == 901, str(inv))
        check("调解卡 route 带 sessionId",
              inv.get("route") == "mediation_invite?sessionId=901", str(inv.get("route")))
        check("调解卡 title", inv.get("title") == "双人调解邀请", str(inv.get("title")))

        dp = by_type.get("dual_perspective") or {}
        check("双视角卡 = 我未提交的 E1（701）", dp.get("id") == 701, str(dp))
        check("双视角卡 route", dp.get("route") == "dual_perspective_detail/701",
              str(dp.get("route")))
        check("双视角卡 title = 事件标题", dp.get("title") == "周末爬山", str(dp.get("title")))

        pl = by_type.get("pending_letter") or {}
        check("待回信卡 = 那封信", pl.get("id") == IDS["letter_id"], str(pl))
        check("待回信卡 route",
              pl.get("route") == "letter_detail/%d" % IDS["letter_id"],
              str(pl.get("route")))
        check("待回信卡 title", pl.get("title") == "手写信", str(pl.get("title")))

        fb = by_type.get("feedback_outcome") or {}
        check("反馈卡 title（契约固定）",
              fb.get("title") == "上次建议采用了么？", str(fb))
        check("反馈卡 route", fb.get("route") == "feedback_outcome", str(fb.get("route")))
        check("反馈卡 id = 会话 id（pending 首条）",
              fb.get("id") in {501, 505, 506, 508}, str(fb.get("id")))

        q = by_type.get("questionnaire") or {}
        check("问卷卡（画像未完成）",
              q.get("id") == 1 and q.get("route") == "questionnaire_intro"
              and q.get("title") == "关系画像未完成",
              str(q))

        check("pending_letter_count 真实总数（非截断长度）",
              data.get("pending_letter_count") == 1, str(data.get("pending_letter_count")))
    finally:
        _drop_client(app, g_db, g_user)

    # ---- 变异后逐类消失（service 直调，省去重复起 client）----
    # 双视角：我提交了 → 卡消失
    db.add(DualPerspectiveRecord(event_id=701, user_id=A_ID, content="我到了",
                                 visibility="hidden"))
    # 画像完成 → 问卷卡消失
    db.add(RelationshipProfile(user_id=A_ID, questionnaire_id=1,
                               profile_type="single", confidence=0.8, version=1))
    # 调解被接受 → 邀请卡消失
    db.query(AiChatSession).filter(AiChatSession.id == 901).first().mediation_status = "accepted"
    # 信被读掉 → 待回信卡消失
    db.query(Letter).filter(Letter.id == IDS["letter_id"]).first().status = "read"
    db.commit()

    data2 = home_service.get_home_data(db, A_ID)
    types2 = [c.get("type") for c in (data2.get("task_cards") or [])]
    check("提交后双视角卡消失", "dual_perspective" not in types2, str(types2))
    check("画像完成后问卷卡消失", "questionnaire" not in types2, str(types2))
    check("邀请被接受后调解卡消失", "mediation_invite" not in types2, str(types2))
    check("信读后待回信卡消失", "pending_letter" not in types2, str(types2))
    # 反馈卡仍在（501/507 仍无 outcome）
    check("反馈卡仍在", "feedback_outcome" in types2, str(types2))
    check("旧字段变异后仍全量保留",
          all(k in data2 for k in ("mode", "pending_letters", "active_mediation",
                                   "recent_museum_items", "space")))


# --------------------------------------------------------------------- #
# 6. §3.1 单人首页：task_cards = []
# --------------------------------------------------------------------- #
def t_single_home(db: Session):
    client, app, g_db, g_user = _fresh_client(db, C_ID)
    try:
        r = client.get("/api/v1/home")
        data = r.json().get("data") or {}
        check("单人 GET /home 成功", r.json().get("code") == 0, str(r.json())[:160])
        check("单人 mode", data.get("mode") == "single", str(data.get("mode")))
        check("单人 task_cards = []", data.get("task_cards") == [],
              str(data.get("task_cards")))
        for key in ("recent_diaries", "has_profile", "user_nickname"):
            check("单人旧字段保留：%s" % key, key in data, str(sorted(data)))
    finally:
        _drop_client(app, g_db, g_user)


# --------------------------------------------------------------------- #
# 7. §3.3 prompt 注入（基线零变化 / 偏离基线才追加）
# --------------------------------------------------------------------- #
def t_persona_injection():
    from app.services.prompt_builder import build_persona_instruction

    base = build_persona_instruction("军师", "gentle")
    check("基线（无设置参数）不含称呼段", "称呼用户" not in base, base)
    check("基线（默认枚举）不含详细程度段", "简短" not in base and "展开" not in base, base)
    check("基线（默认主动度）不含主动段", "主动" not in base, base)

    default_avatar = build_persona_instruction(
        "军师", "gentle", address_name=None, detail_level="standard",
        proactivity="moderate", show_evidence=True,
    )
    check("默认设置值 = 旧版输出逐字节一致",
          default_avatar == base, "%r vs %r" % (default_avatar, base))

    full = build_persona_instruction(
        "军师", "gentle", address_name="宝宝", detail_level="brief",
        proactivity="passive", show_evidence=False,
    )
    check("address_name 注入", "「宝宝」" in full, full)
    check("brief 注入", "简短" in full, full)
    check("passive 注入", "克制" in full, full)
    check("show_evidence=False 注入", "判断依据" in full, full)

    long_addr = build_persona_instruction("军师", "gentle",
                                          address_name="超长的称呼超长的称呼超长")
    check("称呼截断 12 字", "超长的称呼超长的称呼超长的" not in long_addr, long_addr)


def main() -> int:
    print("[W4 端点] 契约 §3.1 / §3.3 / §3.4 回归")
    db = seed()
    try:
        t_feedback_write(db)
        t_feedback_pending(db)
        t_feedback_post_api(db)
        t_advisor_settings(db)
        t_home_task_cards(db)
        t_single_home(db)
        t_persona_injection()
    finally:
        db.close()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
