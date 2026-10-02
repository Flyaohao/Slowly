# -*- coding: utf-8 -*-
"""整改契约 §8.3 反馈闭环 + §8.2 流式结构化回归（SQLite 内存库，无需 MySQL / API Key）。

## 覆盖什么

**§8.3 反馈闭环（北极星数据源）**
1. **同用户同消息 upsert**：adopted 先提交、outcome 后补 → 同一行更新，
   不产生第二行；旧行永久 ``outcome IS NULL`` 的问题消失；
2. **message_id 定向**：可对历史 AI 消息回填；非法目标（user 消息/他人会话）50002；
3. **rating 可选**：只补 outcome 不带评分照常（向后兼容旧客户端仍传 rating）；
4. **pending 去重 + 语义**：历史重复行按消息去重；``adopted=False`` 不进
   pending（未采用无需回访）；outcome 已填不进 pending；
5. **消息回看**：``GET /sessions/{id}/messages`` 每条 AI 消息带我的 feedback；
6. **北极星可验证查询**：``count_closed_feedback_loops`` = adopted=True 且
   outcome 非空的去重消息数。

**§8.2 流式结构化**
7. ``merge_stream_structured``：场景字段并入、raw_text/risk_level 不被覆盖；
8. ``stream_chat_events`` 端到端帧序列：done 帧携带 ``structured``
   （含 suggested_reply 等行动字段），落库 structured 同步带上
   （monkeypatch 提取与 LLM 流，不发真请求）。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_feedback_loop.py
"""
import os
import sys
import threading
import time
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from sqlalchemy import BigInteger, Integer, create_engine  # noqa: E402
from live_llm_guard import live_llm_enabled, skip_reason  # noqa: E402
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
from app.models.ai import AiChatSession, AiChatMessage, AiOutputFeedback  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)

# --------------------------------------------------------------------- #
# 测试库建表形态 = 「迁移之后」还是「迁移之前」
# --------------------------------------------------------------------- #
# 本套件覆盖的两个集合语义相反，必须分别验证，不能折中：
#
#   * 重复行**去重/归并**（迁移必须修好的历史脏数据）——建表时不能有
#     uq_ai_output_feedback_msg_user，否则第二行插不进去，这条断言变成
#     在测 SQLite 的约束而不是在测 list_pending_feedback 的归并逻辑；
#   * 唯一约束**确实存在**（并发双插的兜底）——必须按带约束的表再验一次。
#
# 所以：先用「迁移前形态」建表跑完全部既有用例，再单独建一张带约束的表
# 验证约束生效（见 t_unique_constraint_enforced）。这样既不削弱原有覆盖，
# 也把 review 指出的「测试库静默缺少生产约束」补上。
_FB_TABLE = Base.metadata.tables["ai_output_feedback"]
_LEGACY_CONSTRAINT = next(
    c for c in list(_FB_TABLE.constraints)
    if c.__class__.__name__ == "UniqueConstraint"
    and c.name == "uq_ai_output_feedback_msg_user"
)
_FB_TABLE.constraints.discard(_LEGACY_CONSTRAINT)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101
B_ID = 202


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


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


def seed() -> Session:
    db = Session()
    db.query(AiOutputFeedback).delete()
    db.query(AiChatMessage).delete()
    db.query(AiChatSession).delete()
    db.query(CoupleRelation).delete()
    db.query(User).delete()

    db.add(User(id=A_ID, email="a@example.com", password_hash="x", has_couple=True))
    db.add(User(id=B_ID, email="b@example.com", password_hash="x", has_couple=True))
    db.add(CoupleRelation(id=9, user_a_id=A_ID, user_b_id=B_ID))

    # 会话 501：A 的会话，两条 AI 消息 + 一条 user 消息
    db.add(AiChatSession(id=501, user_id=A_ID, relation_id=9, scene_key="partner_translate"))
    db.add(AiChatMessage(id=601, session_id=501, role="assistant", content="建议这样回"))
    db.add(AiChatMessage(id=602, session_id=501, role="user", content="她说不回我消息"))
    db.add(AiChatMessage(id=603, session_id=501, role="assistant", content="第二轮建议"))

    # 会话 502：B 的会话（越权素材）
    db.add(AiChatSession(id=502, user_id=B_ID, relation_id=9, scene_key="partner_translate"))
    db.add(AiChatMessage(id=610, session_id=502, role="assistant", content="B 的 AI 回答"))

    db.commit()
    return db


# --------------------------------------------------------------------- #
# 1. upsert：同一行补全，不堆重复
# --------------------------------------------------------------------- #
def t_upsert(db: Session):
    from app.services import ai_service

    # 第一次：表态采用
    fb1 = ai_service.submit_feedback(
        db, A_ID, 501, 601,
        {"rating": 5, "feedback_tag": "有用", "feedback_text": None,
         "adopted": True, "outcome": None},
    )
    rows_after_first = (
        db.query(AiOutputFeedback).filter(AiOutputFeedback.message_id == 601).all()
    )
    check("首次提交产生 1 行", len(rows_after_first) == 1, str(len(rows_after_first)))
    check("返回 adopted=True", fb1.get("adopted") is True, str(fb1))

    # 第二次：补结果（不带 rating / tag）
    fb2 = ai_service.submit_feedback(
        db, A_ID, 501, 601,
        {"rating": None, "feedback_tag": None, "feedback_text": None,
         "adopted": None, "outcome": "对方主动道歉了"},
    )
    rows_after_second = (
        db.query(AiOutputFeedback).filter(AiOutputFeedback.message_id == 601).all()
    )
    check("补 outcome 后仍 1 行（upsert 不堆行）",
          len(rows_after_second) == 1, str(len(rows_after_second)))
    row = rows_after_second[0]
    check("outcome 更新到同一行", row.outcome == "对方主动道歉了", str(row.outcome))
    check("adopted 保留（None 不清空）", row.adopted is True, str(row.adopted))
    check("rating 保留（None 不清空）", row.rating == 5, str(row.rating))
    check("返回体带回 outcome", fb2.get("outcome") == "对方主动道歉了", str(fb2))

    # 第三次：改表态 → 原地翻转
    ai_service.submit_feedback(
        db, A_ID, 501, 603,
        {"rating": 2, "feedback_tag": None, "feedback_text": None,
         "adopted": True, "outcome": None},
    )
    ai_service.submit_feedback(
        db, A_ID, 501, 603,
        {"rating": None, "feedback_tag": None, "feedback_text": None,
         "adopted": False, "outcome": None},
    )
    row603 = (
        db.query(AiOutputFeedback).filter(AiOutputFeedback.message_id == 603).one()
    )
    check("adopted 可原地翻转为 False", row603.adopted is False, str(row603.adopted))


# --------------------------------------------------------------------- #
# 2. 历史重复行去重 + pending 语义
# --------------------------------------------------------------------- #
def t_pending_dedup_and_semantics(db: Session):
    from app.services import ai_service

    # 历史脏数据：唯一约束（uq_ai_output_feedback_msg_user）已由模型声明，
    # 测试库按「迁移前形态」建表（见模块顶部建表说明）才能复现
    # 「第一轮纯 insert 留下的重复行」；真实生产路径是迁移合并完才有约束。
    # 601 此时已有一条 outcome 非空的行（t_upsert 落的），再插一条 outcome 为空的
    # 旧行 —— 这正是第一轮实现的典型残留：**已回访**的建议被旧行重新顶回待办。
    db.add(AiOutputFeedback(
        message_id=601, user_id=A_ID, rating=3,
        feedback_tag=None, feedback_text=None, adopted=None, outcome=None,
    ))
    # 602 造两条**都还没回访**的行，用来验证「同消息只出现一次」的去重语义
    db.add(AiOutputFeedback(
        message_id=602, user_id=A_ID, rating=2,
        feedback_tag=None, feedback_text=None, adopted=None, outcome=None,
    ))
    db.add(AiOutputFeedback(
        message_id=602, user_id=A_ID, rating=4,
        feedback_tag=None, feedback_text=None, adopted=True, outcome=None,
    ))
    db.commit()

    items = ai_service.list_pending_feedback(db, A_ID, days=7)["items"]
    msg_ids = [i["message_id"] for i in items]
    check("同消息多条待回访行只出现一次（去重）",
          msg_ids.count(602) == 1, str(msg_ids))
    check("已被回访的消息不因残留旧行重新冒出来（§8.3 核心）",
          601 not in msg_ids, str(msg_ids))

    # adopted=False（603）不进 pending；outcome 已填的不进 pending
    check("adopted=False 不进 pending（未采用无需回访）",
          603 not in msg_ids, str(msg_ids))
    check("outcome 已填的消息不进 pending",
          all(i.get("message_id") != 601 for i in items), str(msg_ids))

    # 显式 adopted=True 无 outcome → 进 pending（另开一条干净的）
    db.query(AiOutputFeedback).filter(AiOutputFeedback.message_id == 602).delete()
    db.commit()
    ai_service.submit_feedback(
        db, A_ID, 501, 602,
        {"rating": 4, "feedback_tag": None, "feedback_text": None,
         "adopted": True, "outcome": None},
    )
    items2 = ai_service.list_pending_feedback(db, A_ID, days=7)["items"]
    msg_ids2 = [i["message_id"] for i in items2]
    check("adopted=True 无 outcome 进 pending", 602 in msg_ids2, str(msg_ids2))


# --------------------------------------------------------------------- #
# 3. API：message_id 定向 / rating 可选 / 越权
# --------------------------------------------------------------------- #
def t_api_targeting(db: Session):
    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        # 3.1 带 message_id → 精确落在 603
        r = client.post(
            "/api/v1/couple/ai/sessions/501/feedback",
            json={"message_id": 603, "adopted": True},
        )
        body = r.json()
        check("POST 带 message_id + 无 rating → 200（rating 可选）",
              r.status_code == 200 and body.get("code") == 0, str(body)[:160])
        check("响应 data 回显 message_id=603",
              (body.get("data") or {}).get("message_id") == 603, str(body)[:200])

        # 3.2 非法目标：user 消息（403 走 passthrough：HTTP 403 + detail.code）
        r2 = client.post(
            "/api/v1/couple/ai/sessions/501/feedback",
            json={"message_id": 602, "rating": 5},
        )
        check("message_id 指向 user 消息 → 403 + 50002",
              r2.status_code == 403
              and (r2.json().get("detail") or {}).get("code") == 50002,
              "%s %s" % (r2.status_code, str(r2.json())[:160]))

        # 3.3 越权：他人会话里的消息
        r3 = client.post(
            "/api/v1/couple/ai/sessions/501/feedback",
            json={"message_id": 610, "rating": 5},
        )
        check("message_id 属于他人会话 → 403 + 50002",
              r3.status_code == 403
              and (r3.json().get("detail") or {}).get("code") == 50002,
              "%s %s" % (r3.status_code, str(r3.json())[:160]))

        # 3.4 不带 message_id → 回落最后一条 AI 消息（603）
        r4 = client.post(
            "/api/v1/couple/ai/sessions/501/feedback",
            json={"rating": 5, "adopted": True, "outcome": "试了，有效"},
        )
        check("不带 message_id 回落最后一条 AI 消息",
              (r4.json().get("data") or {}).get("message_id") == 603,
              str(r4.json())[:200])

        # 3.5 会话无 AI 消息 → 50003（旧行为保持）
        db.add(AiChatSession(id=509, user_id=A_ID, relation_id=9, scene_key="chat"))
        db.add(AiChatMessage(id=609, session_id=509, role="user", content="只有用户"))
        db.commit()
        r5 = client.post(
            "/api/v1/couple/ai/sessions/509/feedback", json={"rating": 5}
        )
        check("会话无 AI 消息 → 50003",
              r5.json().get("code") == 50003, str(r5.json())[:160])
    finally:
        _drop_client(app, g_db, g_user)


# --------------------------------------------------------------------- #
# 4. 消息回看：GET messages 带我的 feedback
# --------------------------------------------------------------------- #
def t_messages_carry_feedback(db: Session):
    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.get("/api/v1/couple/ai/sessions/501/messages")
        msgs = r.json().get("data") or []
        by_id = {m["id"]: m for m in msgs}
        check("messages → 200 且含反馈字段",
              r.status_code == 200 and all("feedback" in m for m in msgs),
              str(r.json())[:160])
        fb601 = (by_id.get(601) or {}).get("feedback")
        fb602 = (by_id.get(602) or {}).get("feedback")
        fb603 = (by_id.get(603) or {}).get("feedback")
        # 601 在 t_pending 里被人为插过一条「重复行」（生产中由迁移合并、
        # 唯一索引防再生）——回看只要求该消息能取到反馈，不指定取到哪一行
        check("601 回看能取到反馈（重复脏行不致崩溃）",
              fb601 is not None and "message_id" in fb601, str(fb601))
        check("602 回看带 adopted=True",
              fb602 is not None and fb602.get("adopted") is True, str(fb602))
        check("603 回看带 outcome（3.4 落的）",
              fb603 is not None and fb603.get("outcome") == "试了，有效",
              str(fb603))
        # JSON 只增不减：旧字段仍在
        check("旧字段保留（structured_output/risk_level）",
              all("structured_output" in m and "risk_level" in m for m in msgs),
              str(list(by_id.get(601, {}).keys())))
    finally:
        _drop_client(app, g_db, g_user)


# --------------------------------------------------------------------- #
# 5. 北极星：有效沟通闭环数
# --------------------------------------------------------------------- #
def t_north_star(db: Session):
    from app.repositories import ai_repo

    before = ai_repo.count_closed_feedback_loops(db, A_ID)
    # 601：adopted=True + outcome → 闭环（1）
    # 603：t_api 3.1 adopted=True + 3.4 outcome → 闭环（2）
    # 602：adopted=True 无 outcome → 非闭环
    check("闭环数 = adopted=True 且 outcome 非空的去重消息数（当前=2）",
          before == 2, "count=%s" % before)

    # 补 602 的 outcome → 闭环 +1
    from app.services import ai_service
    ai_service.submit_feedback(
        db, A_ID, 501, 602,
        {"rating": None, "feedback_tag": None, "feedback_text": None,
         "adopted": None, "outcome": "缓和了"},
    )
    after = ai_repo.count_closed_feedback_loops(db, A_ID)
    check("回访结果落库后闭环数 +1（当前=3）", after == 3, "count=%s" % after)


# --------------------------------------------------------------------- #
# 6. §8.2 流式结构化：纯函数 + 帧序列
# --------------------------------------------------------------------- #
def t_stream_structured():
    from app.services import ai_service

    # 6.1 合并规则
    base = {"raw_text": "正文", "streamed": True, "risk_level": "normal"}
    merged = ai_service.merge_stream_structured(
        base, {"suggested_reply": "试试这样说", "risk_level": "high",
               "raw_text": "模型想覆盖", "next_step": "今晚聊聊"}
    )
    check("场景字段并入", merged.get("suggested_reply") == "试试这样说"
          and merged.get("next_step") == "今晚聊聊", str(merged))
    check("raw_text 不被提取结果覆盖", merged.get("raw_text") == "正文", str(merged))
    check("risk_level 以流式安全检查为准", merged.get("risk_level") == "normal",
          str(merged))
    check("extracted=None 时原样返回",
          ai_service.merge_stream_structured({"raw_text": "x"}, None) == {"raw_text": "x"},
          "")

    # 6.2 端到端帧序列（monkeypatch LLM 流 + 提取，不发真请求）
    import app.services.ai_service as mod

    orig_stream = mod._stream_with_heartbeat
    orig_persist = mod._persist_streamed_message
    orig_extract = mod._extract_stream_structured
    orig_token = mod._read_session_token
    try:
        mod._stream_with_heartbeat = lambda *a, **k: iter(
            [("text", "建议你先别急着回复，"), ("text", "等今晚再好好谈。")]
        )
        mod._persist_streamed_message = lambda **k: (777, 12)
        mod._extract_stream_structured = (
            lambda scene_key, user_input, full_text, cancel_event=None: {
                "summary": "一句话结论",
                "suggested_reply": "今晚我们好好谈谈好吗？",
                "do_not_say": "别说你无理取闹",
                "next_step": "今晚 8 点发起对话",
            }
        )
        mod._read_session_token = lambda sid: 0

        prepared = {
            "blocked": False,
            "session_id": 501,
            "scene_key": "partner_translate",
            "stream_messages": [],
            "chat_mode": "deep",
            "rag_hit": 0,
            "evidence": None,
            "user_id": A_ID,
            "relation_id": 9,
            "user_input": "她说不回我消息",
            "content": "",
            "risk_level": "normal",
        }
        frames = list(ai_service.stream_chat_events(prepared))
        events = [f.get("event") for f in frames if "event" in f]
        done = next(f for f in frames if f.get("event") == "done")
        structured = done["data"].get("structured") or {}

        check("帧序列 meta→delta*→done", events[0] == "meta" and events[-1] == "done",
              str(events))
        check("done 帧携带 structured",
              isinstance(structured, dict) and structured.get("suggested_reply")
              == "今晚我们好好谈谈好吗？", str(structured))
        check("done.structured 含行动字段三件套",
              all(k in structured for k in ("suggested_reply", "do_not_say", "next_step")),
              str(list(structured.keys())))
        check("done.structured 不带 raw_text/streamed",
              "raw_text" not in structured and "streamed" not in structured,
              str(list(structured.keys())))
        # 6.3 提取失败降级：卡片缺席但 done 正常
        mod._extract_stream_structured = lambda *a, **k: None
        frames2 = list(ai_service.stream_chat_events(prepared))
        done2 = next(f for f in frames2 if f.get("event") == "done")
        check("提取失败 → done 正常、structured 仅含 risk_level",
              done2["data"].get("structured", {}).get("suggested_reply") is None
              and done2["data"].get("blocked") is False, str(done2))
    finally:
        mod._stream_with_heartbeat = orig_stream
        mod._persist_streamed_message = orig_persist
        mod._extract_stream_structured = orig_extract
        mod._read_session_token = orig_token


def t_stream_disconnect_cancels_extraction():
    """断连后必须置位 cancel_event，让结构化提取线程收工（review finding 4）。

    真实动机：`/chat/stream` 在响应体阶段会再起一个后台线程做结构化提取
    （§8.2，一次完整 LLM 调用）。客户端断开后没人再读这份结果，若没有取消
    信号，提取线程会把「首轮 → 重试 → JSON 兜底」×候选模型全部烧完。

    这里不打真请求，直接验端点里那段包装逻辑：**喂到一半就关**，捕获到的
    `prepared` 上的 cancel_event 必须被置位，并且提取线程能据此提前收工。
    """
    import app.services.ai_service as mod
    from app.api.v1.couple import ai as ai_api

    orig = {
        "_stream_with_heartbeat": mod._stream_with_heartbeat,
        "_persist_streamed_message": mod._persist_streamed_message,
        "_extract_stream_structured": mod._extract_stream_structured,
        "_read_session_token": mod._read_session_token,
    }
    seen = {}

    def fake_extract(scene_key, user_input, full_text, cancel_event=None):
        seen["cancel_event"] = cancel_event
        # 模拟「一次慢 LLM 调用」：被取消后应立刻返回，不再产出结构化字段
        for _ in range(100):
            if cancel_event is not None and cancel_event.is_set():
                seen["cancelled_in_time"] = True
                return None
            time.sleep(0.02)
        return {"summary": "太慢了"}

    try:
        mod._stream_with_heartbeat = lambda *a, **k: iter(
            [("text", "第一段"), ("text", "第二段")]
        )
        mod._persist_streamed_message = lambda **k: (888, 3)
        mod._extract_stream_structured = fake_extract
        mod._read_session_token = lambda sid: 0

        # 走**端点真正的实现**（不是复刻）：喂一帧就关 = 客户端提前断开
        disconnect_event = threading.Event()
        prepared = _prepared_dict()
        prepared["cancel_event"] = disconnect_event
        stream = ai_api._guarded_sse_stream(prepared, disconnect_event)
        next(stream)
        stream.close()  # 触发 finally → 置位 cancel_event
        check("断连后 cancel_event 被置位", disconnect_event.is_set())

        # 提取线程侧：拿到同一个事件应当立刻收工
        mod._extract_stream_structured(
            "partner_translate", "问题", "回答", disconnect_event
        )
        check("提取线程在取消后立刻收工（不再等满 100 轮）",
              seen.get("cancelled_in_time") is True, str(seen))
    finally:
        mod._stream_with_heartbeat = orig["_stream_with_heartbeat"]
        mod._persist_streamed_message = orig["_persist_streamed_message"]
        mod._extract_stream_structured = orig["_extract_stream_structured"]
        mod._read_session_token = orig["_read_session_token"]


def _prepared_dict() -> dict:
    return {
        "blocked": False,
        "session_id": 501,
        "scene_key": "partner_translate",
        "stream_messages": [],
        "chat_mode": "deep",
        "rag_hit": 0,
        "evidence": None,
        "user_id": A_ID,
        "relation_id": 9,
        "user_input": "她说不回我消息",
        "content": "",
        "risk_level": "normal",
    }


def t_unique_constraint_enforced():
    """单一数据库约束才是并发双插的兜底（review finding 1）。

    `create_feedback` 是「先查后写」+`FOR UPDATE`，单机串行下已经够用；
    但两个请求同时到达、且都还没看到对方未提交的行时，唯一约束是最后一道
    防线。这个用例按**生产形态**（带约束）另建一张表验证：约束必须存在，
    且真能拒绝第二行。
    """
    from sqlalchemy import create_engine as _ce, text as _text
    from sqlalchemy.pool import StaticPool as _SP
    from sqlalchemy.orm import sessionmaker as _sm

    e = _ce("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=_SP)
    # 用带约束的完整元数据建表（上面只为 legacy 集合摘掉了那一份副本）
    _FB_TABLE.append_constraint(_LEGACY_CONSTRAINT)
    try:
        Base.metadata.create_all(e)
    finally:
        _FB_TABLE.constraints.discard(_LEGACY_CONSTRAINT)

    with e.begin() as conn:
        conn.execute(_text(
            "INSERT INTO ai_output_feedback (id, message_id, user_id, rating) "
            "VALUES (1, 601, 101, 5)"
        ))
    rejected = False
    try:
        with e.begin() as conn:
            conn.execute(_text(
                "INSERT INTO ai_output_feedback (id, message_id, user_id, rating) "
                "VALUES (2, 601, 101, 1)"
            ))
    except Exception:
        rejected = True
    check("同 (message_id,user_id) 第二行被唯一约束拒绝（并发兜底）", rejected)

    # 不同用户对同一消息可以各有一行（约束是两列联合，不是 message_id 单列）
    second_user_ok = True
    try:
        with e.begin() as conn:
            conn.execute(_text(
                "INSERT INTO ai_output_feedback (id, message_id, user_id, rating) "
                "VALUES (3, 601, 202, 4)"
            ))
    except Exception as ex:  # noqa: BLE001 —— 断言的是「没抛」，任何异常都算失败
        second_user_ok = False
        detail = type(ex).__name__
    else:
        detail = ""
    check("不同用户同消息互不冲突（联合唯一而非单列）", second_user_ok, detail)
    e.dispose()


def t_submit_feedback_survives_duplicate_insert(db: Session):
    """并发双插被约束拒绝时，submit_feedback 必须接住并重试（review finding 2）。

    主库是「迁移前形态」（刻意摘掉约束，见文件头），而这条用例验的正是约束
    触发后的重试路径，所以另建一张**带约束**的表——与生产形态一致。

    为什么假仓储是「不查直接 INSERT」而不是打桩抛异常：真实并发下 Second 的
    SELECT 看不到 First 未提交的行，于是走插入分支、被约束拒绝。直接 INSERT
    就是复现这一步，而不是模拟它。不接 IntegrityError 的话，异常会一路冒到
    main.py 的兜底处理器，变成 HTTP 200 + code 10000，用户刚写的反馈丢失。

    断言重点不是「没报错」而是**补全语义**：重试若只吞掉冲突、返回空行，
    用户先前填的「采用」就没了，所以后到的 outcome 必须落在同一行上。
    """
    from sqlalchemy import create_engine as _ce
    from sqlalchemy.orm import sessionmaker as _sm
    from sqlalchemy.pool import StaticPool as _SP

    from app.repositories import ai_repo
    from app.services import ai_service

    target = 601
    engine_prod = _ce(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=_SP,
    )
    # 注意：模块顶部已把约束从模型上摘掉（为了建 legacy 主库），所以这里必须
    # 在 create_all 之前**临时装回**，否则建出来的是一张同样没有约束的表，
    # 撞约束的路径根本走不到（上面 t_unique_constraint_enforced 同理）。
    _FB_TABLE.append_constraint(_LEGACY_CONSTRAINT)
    try:
        Base.metadata.create_all(engine_prod)
    finally:
        _FB_TABLE.constraints.discard(_LEGACY_CONSTRAINT)
    ProdSession = _sm(bind=engine_prod)
    s = ProdSession()
    s.add(User(id=A_ID, email="prod@example.com", password_hash="x", has_couple=True))
    s.add(User(id=B_ID, email="prodb@example.com", password_hash="x", has_couple=True))
    s.add(CoupleRelation(id=9, user_a_id=A_ID, user_b_id=B_ID))
    s.add(AiChatSession(id=501, user_id=A_ID, relation_id=9, scene_key="partner_translate"))
    s.add(AiChatMessage(id=601, session_id=501, role="assistant", content="建议这样回"))
    s.commit()

    # 第一次提交正常落一行（adopted=True）
    ai_service.submit_feedback(
        s, A_ID, 501, target,
        {"rating": 5, "feedback_tag": None, "feedback_text": None,
         "adopted": True, "outcome": None},
    )

    insert_attempts = []
    real_create = ai_repo.create_feedback

    def _blind_insert(db_, **kwargs):
        """头一次走「查不到行 → 直接插入」；此后交回真实实现。

        为什么要交回：第一次插入被约束拒绝后，生产代码会**重跑整个
        ``create_feedback``**——那一次是真的 SELECT→UPDATE。如果这里继续
        拦着，用例就只证明了「异常被接住」，证明不了「走 UPDATE 而不是新建」。
        所以第二次起交回 `real_create`：它不是打桩，是被测代码本身。
        """
        insert_attempts.append(kwargs["outcome"])
        if len(insert_attempts) > 1:
            return real_create(db_, **kwargs)
        assert kwargs["user_id"] == A_ID, "重试必须带着原请求的身份，不能换人"
        assert kwargs["message_id"] == target, "重试必须打同一个消息"
        row = AiOutputFeedback(
            message_id=kwargs["message_id"],
            user_id=kwargs["user_id"],
            rating=kwargs["rating"],
            feedback_tag=kwargs["feedback_tag"],
            feedback_text=kwargs["feedback_text"],
            adopted=kwargs["adopted"],
            outcome=kwargs["outcome"],
        )
        db_.add(row)
        db_.flush()  # → IntegrityError（唯一约束拒绝第二行）
        return row

    ai_repo.create_feedback = _blind_insert
    try:
        second = ai_service.submit_feedback(
            s, A_ID, 501, target,
            {"rating": None, "feedback_tag": None, "feedback_text": None,
             "adopted": None, "outcome": "他后来主动说了抱歉"},
        )
    finally:
        ai_repo.create_feedback = real_create

    check("撞唯一约束不抛错、返回正常响应", isinstance(second, dict), str(second))
    check("插入被拒后确实重试了（两次调用仓储层）",
          len(insert_attempts) == 2, str(insert_attempts))
    check("后到的 outcome 落到同一行",
          second.get("outcome") == "他后来主动说了抱歉", str(second))
    check("先到的 adopted 未被覆盖（重试走 UPDATE 而非新建）",
          second.get("adopted") is True, str(second))
    check("先到的 rating 未被覆盖",
          second.get("rating") == 5, str(second))
    rows = (
        s.query(AiOutputFeedback)
        .filter(AiOutputFeedback.message_id == target, AiOutputFeedback.user_id == A_ID)
        .all()
    )
    check("并发提交后仍是 1 行", len(rows) == 1, str(len(rows)))
    s.close()
    engine_prod.dispose()


def t_feedback_outcome_via_api(db: Session):
    """wire 形状：反馈响应体字段名被真实 HTTP 响应断言（review finding 6）。

    review 指出只在 service 层断言 ``data["message_id"]`` 时，``FeedbackOut``
    改名会静默通过——客户端读的是 wire key。契约 §8.3 的「采用 → 回填结果」
    全靠这些 key，所以这里按响应体逐字段验。

    用**全新的消息 604**：既有用例的消息都已带上反馈（601 累积多行、603 已有
    outcome），拿它们验「回填结果后从 pending 消失」是空断言——它本来就不在
    pending 里。604 从零开始，两步都可以真验。
    """
    db.add(AiChatMessage(id=604, session_id=501, role="assistant", content="第三轮建议"))
    db.commit()
    target = 604

    client, app, get_db, get_current_user = _fresh_client(db, A_ID)
    try:
        resp = client.post(
            "/api/v1/couple/ai/sessions/501/feedback",
            json={"message_id": target, "adopted": True},
        )
        check("反馈 POST → HTTP 200", resp.status_code == 200, str(resp.status_code))
        body = resp.json()
        check("响应走统一信封（code=0）", body.get("code") == 0, str(body))
        data = body.get("data") or {}
        missing = [
            k for k in ("message_id", "rating", "adopted", "outcome",
                        "feedback_tag", "feedback_text")
            if k not in data
        ]
        check("响应体含全部 wire 字段", not missing, "缺：%s / %s" % (missing, data))
        check("adopted 按提交值回显", data.get("adopted") is True, str(data))

        # 采用但未填结果 → 必须在 pending（否则「稍后回访」这个能力就是假的）
        pending1 = client.get("/api/v1/couple/ai/feedback/pending").json().get("data") or {}
        ids1 = [i["message_id"] for i in pending1.get("items", [])]
        check("采用但未填结果 → 进 pending", target in ids1, str(ids1))

        resp2 = client.post(
            "/api/v1/couple/ai/sessions/501/feedback",
            json={"message_id": target, "outcome": "当天就和好了"},
        )
        data2 = resp2.json().get("data") or {}
        check("补结果后 adopted 仍在（同一行补全）",
              data2.get("adopted") is True, str(data2))
        check("补结果后 outcome 已填",
              data2.get("outcome") == "当天就和好了", str(data2))

        pending2 = client.get("/api/v1/couple/ai/feedback/pending").json().get("data") or {}
        ids2 = [i["message_id"] for i in pending2.get("items", [])]
        check("已回填结果的消息从 pending 消失（任务卡随之消失）",
              target not in ids2, str(ids2))
    finally:
        _drop_client(app, get_db, get_current_user)


def main() -> int:
    print("[整改 §8.3/§8.2] 反馈闭环 + 流式结构化 回归")
    if not live_llm_enabled("feedback_loop"):
        print(skip_reason("feedback_loop"))
        return 0
    db = seed()
    try:
        t_upsert(db)
        t_pending_dedup_and_semantics(db)
        t_api_targeting(db)
        t_messages_carry_feedback(db)
        t_north_star(db)
        t_stream_structured()
        t_stream_disconnect_cancels_extraction()
        t_unique_constraint_enforced()
        t_submit_feedback_survives_duplicate_insert(db)
        t_feedback_outcome_via_api(db)
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
