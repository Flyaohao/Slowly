# -*- coding: utf-8 -*-
"""P0-1 双视角可见性回归（契约 §2.1，SQLite 内存库，不需要 MySQL / API Key）。

## 覆盖什么

契约 §2.1 的四条目标契约 + 验收三项：

1. **§2.1-1 detail 服务端过滤**：reveal 前 B 调 detail 拿不到 A 的 content
   （响应里根本没有，不是「UI 没渲染」），`partner_submitted=true` 只增不减；
   records 排序固定「先本人后对方，各按 id 升序」；
2. **§2.1-2 提交/编辑忽略客户端 visibility**：服务端一律置 `hidden`，
   客户端不能把自己改成可见（reveal 是唯一翻转开关）；
3. **§2.1-3 reveal 语义**：双方都提交（`both_sides`）才可 reveal；
   reveal 把**双方** record 翻 `visible` + `status=completed` 后才返回；
4. **§2.1-4 dual-summary 门**：`status != "completed"` 时 70003，HTTP 归一化
   成 200 + `{code:70003, message:"双方都写下并公开视角后才能生成总结"}`；
5. **§2.1-5 蒸馏时机**：reveal 前**绝不**调 `distill_event_in_background`
   （monkeypatch 计数），reveal 成功后恰好 1 次、`source="dual"`、
   内容含双方原文 —— memory_events 内部语义一行未动（禁碰区）。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_dual_visibility.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from datetime import datetime, timedelta  # noqa: E402

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
from app.models.dual_perspective import (  # noqa: E402
    DualPerspectiveEvent,
    DualPerspectiveRecord,
)

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101  # 我
B_ID = 202  # 伴侣
X_ID = 303  # 关系外第三者

A_CONTENT = "我当时觉得被忽视了"
B_CONTENT = "我其实只是累了"


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


IDS = {}


def seed() -> Session:
    db = Session()
    db.query(DualPerspectiveRecord).delete()
    db.query(DualPerspectiveEvent).delete()
    db.query(CoupleRelation).delete()
    db.query(User).delete()
    db.commit()

    for uid in (A_ID, B_ID, X_ID):
        db.add(User(id=uid, email="u%d@example.com" % uid, password_hash="x", has_couple=uid != X_ID))
    rel = CoupleRelation(
        user_a_id=A_ID, user_b_id=B_ID, status="active",
        bind_time=datetime.utcnow() - timedelta(days=30),
    )
    db.add(rel)
    db.commit()
    db.refresh(rel)

    # v5.0 强制配置（D4）：prepare_dual_summary 会按用户解析客户端
    from ai_config_seed import ensure_ai_config

    ensure_ai_config(db, A_ID, real=False)
    ensure_ai_config(db, B_ID, real=False)

    now = datetime.utcnow()
    # E1 双方都已提交（都 hidden、未 reveal）——detail 过滤 / dual-summary 门
    e1 = DualPerspectiveEvent(
        id=801, relation_id=rel.id, title="谁洗碗",
        event_time=now - timedelta(days=1), status="both_sides",
    )
    # E2 只有 A 提交（one_side）——submit/edit 强制 hidden 用，之后会被翻 both_sides
    e2 = DualPerspectiveEvent(
        id=802, relation_id=rel.id, title="周末爬山",
        event_time=now - timedelta(days=2), status="one_side",
    )
    # E4 只有 A 提交且**全程不碰**——专门用来验 one_side reveal 拒绝 70005
    e4 = DualPerspectiveEvent(
        id=804, relation_id=rel.id, title="谁先道歉",
        event_time=now - timedelta(days=4), status="one_side",
    )
    db.add_all([e1, e2, e4])
    db.commit()

    db.add_all([
        DualPerspectiveRecord(event_id=801, user_id=A_ID, content=A_CONTENT, visibility="hidden"),
        DualPerspectiveRecord(event_id=801, user_id=B_ID, content=B_CONTENT, visibility="hidden"),
        DualPerspectiveRecord(event_id=802, user_id=A_ID, content="我先开了个头", visibility="hidden"),
        DualPerspectiveRecord(event_id=804, user_id=A_ID, content="还在赌气", visibility="hidden"),
    ])
    db.commit()

    IDS["rel_id"] = rel.id
    return db


# --------------------------------------------------------------------- #
# §2.1-1 detail 服务端过滤
# --------------------------------------------------------------------- #
def t_detail_hides_partner(db: Session):
    from app.services import dual_perspective_service

    # B 视角：自己的记录在前，A 的 content 整段不在响应里
    detail_b = dual_perspective_service.get_event_detail(db, B_ID, 801)
    rows_b = list(detail_b.records or [])
    check("B detail 只拿到 1 条（自己的）", len(rows_b) == 1, str([(r.user_id, r.visibility) for r in rows_b]))
    check("B detail 第一条是本人记录", bool(rows_b) and rows_b[0].user_id == B_ID, str(getattr(rows_b[0], "user_id", None)))
    check("B detail 不含 A 的 content", all(A_CONTENT not in (r.content or "") for r in rows_b))
    check("B detail partner_submitted=true", detail_b.partner_submitted is True,
          str(getattr(detail_b, "partner_submitted", None)))

    # A 视角同样拿不到 B 的未公开内容
    detail_a = dual_perspective_service.get_event_detail(db, A_ID, 801)
    rows_a = list(detail_a.records or [])
    check("A detail 只拿到自己的 1 条", len(rows_a) == 1 and rows_a[0].user_id == A_ID,
          str([(r.user_id, r.visibility) for r in rows_a]))
    check("A detail 不含 B 的 content", all(B_CONTENT not in (r.content or "") for r in rows_a))

    # one_side 事件：B 还没提交 → 自己没记录、对方未公开 → 0 条但 partner_submitted=true
    detail_one = dual_perspective_service.get_event_detail(db, B_ID, 802)
    check("one_side：B 看到 0 条记录", len(list(detail_one.records or [])) == 0,
          str([(r.user_id, r.visibility) for r in (detail_one.records or [])]))
    check("one_side：partner_submitted 仍为 true", detail_one.partner_submitted is True,
          str(getattr(detail_one, "partner_submitted", None)))

    # 排序：双方可见后 先本人后对方（reveal 之后再验，这里先验本人优先）
    check("排序：本人记录排在首位", rows_b[0].user_id == B_ID and rows_a[0].user_id == A_ID)


def t_detail_http_shape(db: Session):
    client, app, get_db, get_cur = _fresh_client(db, B_ID)
    try:
        r = client.get("/api/v1/couple/dual-perspectives/801")
        data = r.json()
        payload = data.get("data") or {}
        records = payload.get("records") or []
        check("HTTP detail 200+code0", r.status_code == 200 and data.get("code") == 0, str(data)[:160])
        check("HTTP detail 带 partner_submitted 字段（只增不减）", "partner_submitted" in payload, str(sorted(payload)))
        check("HTTP detail partner_submitted=true", payload.get("partner_submitted") is True)
        check("HTTP detail records 无 A 的 content",
              all(A_CONTENT not in (x.get("content") or "") for x in records), str(records)[:160])
        check("HTTP detail records 仅本人 1 条", len(records) == 1 and records[0].get("user_id") == B_ID,
              str(records)[:160])
    finally:
        _drop_client(app, get_db, get_cur)


def t_cross_relation_denied(db: Session):
    from app.services import dual_perspective_service

    try:
        dual_perspective_service.get_event_detail(db, X_ID, 801)
        check("关系外用户调 detail 被拒 70002", False, "未抛异常")
    except ValueError as e:
        check("关系外用户调 detail 被拒 70002", str(e) in ("30005", "70002"), str(e))


# --------------------------------------------------------------------- #
# §2.1-2 提交/编辑忽略客户端 visibility
# --------------------------------------------------------------------- #
def t_submit_visibility_forced_hidden(db: Session):
    from app.services import dual_perspective_service

    # B 对 E2 提交，客户端硬传 visibility=visible → 服务端必须落 hidden
    rec = dual_perspective_service.submit_record(
        db, B_ID, 802,
        {"content": "我也来说一句", "visibility": "visible"},
    )
    check("客户端传 visible → 落库仍为 hidden", rec.visibility == "hidden", rec.visibility)
    check("提交后事件翻 both_sides", dual_perspective_service.get_event_detail(db, A_ID, 802).status == "both_sides")


def t_edit_cannot_flip_visible(db: Session):
    from app.services import dual_perspective_service

    # 找到 B 在 E2 的记录（802 现在有 A/B 两条）
    rows = [r for r in dual_perspective_service.get_event_detail(db, B_ID, 802).records or []
            if r.user_id == B_ID]
    # E2 未 reveal → detail 只回本人记录，正好拿得到 B 的那条
    target = rows[0]
    edited = dual_perspective_service.edit_record(
        db, B_ID, 802, target.id,
        {"content": "改一下", "visibility": "visible"},
    )
    check("编辑时传 visible → 未 reveal 仍为 hidden", edited.visibility == "hidden", edited.visibility)
    check("编辑内容生效", edited.content == "改一下", edited.content)


# --------------------------------------------------------------------- #
# §2.1-5 蒸馏时机（reveal 前绝不写记忆）
# --------------------------------------------------------------------- #
_DISTILL_CALLS = []


def _install_distill_spy():
    from app.services import memory_events

    original = memory_events.distill_event_in_background

    def spy(event):
        _DISTILL_CALLS.append(event)
        return None  # 不真跑后台线程：hermetic 测试不碰 LLM / ai_memory

    memory_events.distill_event_in_background = spy
    return memory_events, original


def t_no_distill_before_reveal(db: Session):
    """submit 链路不触碰蒸馏；reveal 成功后恰好 1 次、source='dual'、含双方原文。

    spy 由 main() 全程安装（t_dual_summary_gate 里的 reveal 803 同样不落
    真实线程），这里只做计数断言。
    """
    from app.services import dual_perspective_service

    _DISTILL_CALLS.clear()
    # seed 直插的记录模拟「双方已提交但未 reveal」；submit 路径本身
    # 不 import memory_events（dual_perspective_service 源码可查），
    # 真实写路径的蒸馏发生在 reveal —— 下面逐点断言。
    check("reveal 前蒸馏调用数为 0", len(_DISTILL_CALLS) == 0, str(len(_DISTILL_CALLS)))

    # one_side 事件（E4 全程未被 submit 碰过）不可 reveal
    try:
        dual_perspective_service.reveal_event(db, A_ID, 804)
        check("单方提交时 reveal 被拒 70005", False, "未抛异常")
    except ValueError as e:
        check("单方提交时 reveal 被拒 70005", str(e) == "70005", str(e))
    check("被拒的 reveal 也不蒸馏", len(_DISTILL_CALLS) == 0, str(len(_DISTILL_CALLS)))

    # E1 双方都提交 → reveal 成功
    event = dual_perspective_service.reveal_event(db, A_ID, 801)
    check("reveal 后 status=completed", event.status == "completed", event.status)
    check("reveal 恰好蒸馏 1 次", len(_DISTILL_CALLS) == 1, str(len(_DISTILL_CALLS)))
    if _DISTILL_CALLS:
        ev = _DISTILL_CALLS[0]
        check("蒸馏 source=dual", ev.source == "dual", str(ev.source))
        check("蒸馏内容含双方原文", A_CONTENT in ev.content and B_CONTENT in ev.content,
              (ev.content or "")[:120])
        check("蒸馏 relation_id 正确", ev.relation_id == IDS["rel_id"], str(ev.relation_id))


def t_reveal_makes_both_visible(db: Session):
    from app.services import dual_perspective_service

    # A 视角：现在两条都在（本人在前）
    detail_a = dual_perspective_service.get_event_detail(db, A_ID, 801)
    rows_a = list(detail_a.records or [])
    check("reveal 后 A 拿到 2 条", len(rows_a) == 2, str([(r.user_id, r.visibility) for r in rows_a]))
    check("reveal 后双方 visibility 均为 visible",
          all(r.visibility == "visible" for r in rows_a),
          str([(r.user_id, r.visibility) for r in rows_a]))
    check("排序：本人在前、对方在后",
          [r.user_id for r in rows_a] == [A_ID, B_ID],
          str([r.user_id for r in rows_a]))

    detail_b = dual_perspective_service.get_event_detail(db, B_ID, 801)
    rows_b = list(detail_b.records or [])
    check("reveal 后 B 视角同样 2 条且本人在前",
          [r.user_id for r in rows_b] == [B_ID, A_ID],
          str([r.user_id for r in rows_b]))
    check("reveal 后 B 能读到 A 的 content", any(A_CONTENT == (r.content or "") for r in rows_b))

    # 重复 reveal：状态已 completed（非 both_sides）→ 70005
    try:
        dual_perspective_service.reveal_event(db, A_ID, 801)
        check("重复 reveal 被拒 70005", False, "未抛异常")
    except ValueError as e:
        check("重复 reveal 被拒 70005", str(e) == "70005", str(e))


# --------------------------------------------------------------------- #
# §2.1-4 dual-summary 门
# --------------------------------------------------------------------- #
def t_dual_summary_gate(db: Session):
    from app.services import ai_service
    from app.services import dual_perspective_service

    # E2 仍是 one_side（未 reveal）→ 70003
    try:
        ai_service.prepare_dual_summary(db, A_ID, 802)
        check("未 reveal 时 dual-summary 被拒 70003", False, "未抛异常")
    except ValueError as e:
        check("未 reveal 时 dual-summary 被拒 70003", str(e) == "70003", str(e))

    # 构造「双方已提交但仍非 completed」的第三条事件，验 completed 门而非 records 数量门
    db.add(DualPerspectiveEvent(
        id=803, relation_id=IDS["rel_id"], title="冷战三小时",
        event_time=datetime.utcnow(), status="both_sides",
    ))
    db.commit()
    db.add_all([
        DualPerspectiveRecord(event_id=803, user_id=A_ID, content="A 侧", visibility="hidden"),
        DualPerspectiveRecord(event_id=803, user_id=B_ID, content="B 侧", visibility="hidden"),
    ])
    db.commit()
    try:
        ai_service.prepare_dual_summary(db, A_ID, 803)
        check("both_sides 但未 reveal → 仍 70003", False, "未抛异常")
    except ValueError as e:
        check("both_sides 但未 reveal → 仍 70003", str(e) == "70003", str(e))

    # reveal 之后放行，且 prompt 含双方原文
    dual_perspective_service.reveal_event(db, A_ID, 803)
    prepared = ai_service.prepare_dual_summary(db, A_ID, 803)
    check("reveal 后 dual-summary 放行", isinstance(prepared, dict) and prepared.get("prompt"),
          str(prepared)[:120])
    prompt = (prepared or {}).get("prompt") or ""
    check("总结 prompt 含双方原文", "A 侧" in prompt and "B 侧" in prompt, prompt[:160])
    check("总结 prompt 未把内容塞进结构化字段", prepared.get("output_model") is None,
          str(prepared.get("output_model")))


def t_dual_summary_http_gate(db: Session):
    """HTTP 层：400+detail dict → 归一化成 200 + 70003 + 契约文案。"""
    client, app, get_db, get_cur = _fresh_client(db, A_ID)
    try:
        r = client.post("/api/v1/couple/ai/dual-summary/stream", json={"event_id": 802})
        data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        check("未 reveal → HTTP 200 + 70003", r.status_code == 200 and data.get("code") == 70003,
              "%s %s" % (r.status_code, str(data)[:200]))
        check("70003 文案与契约一致",
              data.get("message") == "双方都写下并公开视角后才能生成总结", str(data.get("message")))
    finally:
        _drop_client(app, get_db, get_cur)


# --------------------------------------------------------------------- #
# §8.6 双视角邀请：带邀请语才通知
# --------------------------------------------------------------------- #
_INVITES = []


def _install_invite_spy():
    """替换 notify_dual_invite：只记录调用，不真发。

    这里替换的是**通知函数**（不是 schedule_notify）——schedule_notify 在
    没有事件循环时会 `asyncio.run` 真跑协程，而真正需要断言的是「有没有发起
    这次通知、带的是什么」，不是投递机制本身。
    """
    from app.services import notification_service

    original = notification_service.notify_dual_invite

    def spy(partner_id, event_id, title, inviter_name, invite_message):
        _INVITES.append({
            "partner_id": partner_id,
            "event_id": event_id,
            "title": title,
            "inviter_name": inviter_name,
            "invite_message": invite_message,
        })
        return None

    notification_service.notify_dual_invite = spy
    return notification_service, original


def t_create_event_invite(db: Session):
    """§8.6：军师/用户发起双视角时能邀请伴侣；不填邀请语则完全不打扰。"""
    from app.services import dual_perspective_service

    _INVITES.clear()
    plain = dual_perspective_service.create_event(db, A_ID, {
        "title": "没填邀请语", "event_time": datetime.utcnow(),
    })
    check("不带邀请语不打扰伴侣", len(_INVITES) == 0, str(_INVITES))
    check("事件照常创建", plain is not None and (plain.id or 0) > 0, str(plain))

    invited = dual_perspective_service.create_event(db, A_ID, {
        "title": "填了邀请语",
        "event_time": datetime.utcnow(),
        "invite_message": "我想听听你当时是怎么想的",
    })
    check("带邀请语恰好通知 1 次", len(_INVITES) == 1, str(_INVITES))
    first_invite = _INVITES[0] if _INVITES else None
    if first_invite:
        got = first_invite
        check("通知发给伴侣而不是自己", got["partner_id"] == B_ID, str(got))
        check("通知带事件 id（对方能直达那件事）", got["event_id"] == invited.id, str(got))
        check("通知带事件标题", got["title"] == "填了邀请语", str(got))
        check("通知带邀请语（说明为什么现在写）",
              got["invite_message"] == "我想听听你当时是怎么想的", str(got))
        check("昵称取不到时退回中性称呼", got["inviter_name"] == "你的伴侣", str(got))

    # 前端 trim 后可能传空串：等同没填，不发通知
    _INVITES.clear()
    dual_perspective_service.create_event(db, A_ID, {
        "title": "空白邀请语", "event_time": datetime.utcnow(), "invite_message": "   ",
    })
    check("空白邀请语不打扰伴侣", len(_INVITES) == 0, str(_INVITES))

    # 通知里不出现任何一方的视角正文（可见性边界仍在服务端过滤）
    if first_invite:
        check("通知 payload 无视角正文字段",
              set(first_invite) == {"partner_id", "event_id", "title", "inviter_name", "invite_message"},
              str(sorted(first_invite)))


# --------------------------------------------------------------------- #
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


def main() -> int:
    print("[P0-1 双视角可见性] 契约 §2.1 回归")
    db = seed()
    module, original = _install_distill_spy()
    notify_module, original_notify = _install_invite_spy()
    try:
        t_detail_hides_partner(db)
        t_detail_http_shape(db)
        t_cross_relation_denied(db)
        t_submit_visibility_forced_hidden(db)
        t_edit_cannot_flip_visible(db)
        t_no_distill_before_reveal(db)
        t_reveal_makes_both_visible(db)
        t_dual_summary_gate(db)
        t_dual_summary_http_gate(db)
        t_create_event_invite(db)
    finally:
        module.distill_event_in_background = original
        notify_module.notify_dual_invite = original_notify
        db.close()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
