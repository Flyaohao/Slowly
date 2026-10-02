# -*- coding: utf-8 -*-
"""存量未来信解锁门回归（契约 §2.5-2，SQLite 内存库，不需要 MySQL / API Key）。

## 覆盖什么

P0-5「私密信和未来信假完成」的存量防泄密验收点——已存在且未解锁的
`letter_type="future"` 信在**四处**不得泄漏标题与内容：

1. **detail**：接收方 `get_letter` 在 unlock_time 前拒绝 60002（只看
   letter_type + unlock_time，不依赖 status 等巧合字段）；发件方不受限；
2. **list**：接收方列表整条隐藏（标题也不给）；`unlock_time IS NULL`
   的历史行（客户端从未传过 unlock_time）同样锁定；
3. **AI 理解/回信**：同步与流式入口全部过同一道门；
4. **通知**：send 通知标题经 `_notification_title` 替换成占位
   （future 类型发信本身已被 §2.5-1 冻结为 10006，此处双保险）；
5. **首页卡**：`future_letter.title` 恒为占位；`pending_letters` 排除
   锁定行；`pending_letter_count` 是真实总数而非 limit(5) 截断长度；
6. **favorite / delete / batch-delete**（审查 C1）：favorite 响应体是完整
   LetterOut，三处都过同一道门，与 detail 同错误码 60002；批删**剔除**
   锁定行（与 list 语义一致），不整批拒绝；
7. **list 的 total**（审查 MEDIUM-1）：`total` 在 SQL 层剔除锁定行，
   与实际返回行数口径一致；
8. **类型冻结**（审查 MEDIUM-2 / §2.5-1）：`POST /couple/letters`、
   `PUT /{id}`、`POST /{id}/send` 传 `letter_type ∈ {future, private}` → 10006；
9. **生成回读门**（审查 MEDIUM-3）：`GET /ai/generations/{kind}` 回读的
   信件解读/改写/回信派生内容，目标是未解锁 future 信时 60002。

范围声明：门只针对 `letter_type == "future"`——calm 冷静期、is_private
遮蔽维持现状（W6 清理）。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_future_letter_gate.py
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

from app.models.letter import Letter  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.services import letter_service  # noqa: E402
from app.services import letter_ai_service  # noqa: E402
from app.services import home_service  # noqa: E402
from app.services import ai_generation_service  # noqa: E402
from app.services.letter_service import LOCKED_LETTER_TITLE  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101  # 发件方 / 发起绑定
B_ID = 202  # 收件方

SECRET_TITLE = "惊喜派对安排"
SECRET_CONTENT = "周六晚上七点老地方，我已经订好了靠窗的位置"

NOW = datetime.utcnow()


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def _mk_letter(db, **kw) -> Letter:
    row = Letter(
        relation_id=kw.pop("relation_id"),
        sender_id=kw.pop("sender_id", A_ID),
        receiver_id=kw.pop("receiver_id", B_ID),
        title=kw.pop("title", SECRET_TITLE),
        content=kw.pop("content", SECRET_CONTENT),
        letter_type=kw.pop("letter_type", "normal"),
        status=kw.pop("status", "sent"),
        send_time=kw.pop("send_time", NOW - timedelta(days=1)),
        unlock_time=kw.pop("unlock_time", None),
        **kw,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return row


def _fresh():
    db = Session()
    db.query(Letter).delete()
    db.query(CoupleRelation).delete()
    db.commit()
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)

    letters = {
        "future_locked": _mk_letter(
            db, relation_id=rel.id, letter_type="future",
            unlock_time=NOW + timedelta(days=3),
        ),
        "future_null": _mk_letter(
            db, relation_id=rel.id, letter_type="future",
            title="无排期的未来信", unlock_time=None,
        ),
        "future_unlocked": _mk_letter(
            db, relation_id=rel.id, letter_type="future",
            title="已解锁的未来信", unlock_time=NOW - timedelta(hours=1),
        ),
        "normal": _mk_letter(
            db, relation_id=rel.id, title="普通的信",
        ),
        "calm_locked": _mk_letter(
            db, relation_id=rel.id, letter_type="calm",
            title="冷静期的信", unlock_time=NOW + timedelta(hours=2),
        ),
        "normal_draft": _mk_letter(
            db, relation_id=rel.id, letter_type="normal", status="draft",
            title="待发送的草稿", send_time=None,
        ),
        # 冻结前遗留的 future 草稿：send 应被 §2.5-1 拒 10006
        "future_draft": _mk_letter(
            db, relation_id=rel.id, letter_type="future", status="draft",
            title="遗留的未来信草稿", send_time=None,
            unlock_time=NOW + timedelta(days=5),
        ),
    }
    # 首页计数用：7 封普通信（超过 list limit=5，验证 count 不再被截断）
    for i in range(7):
        letters["pending_%d" % i] = _mk_letter(
            db, relation_id=rel.id, title="第%d封待回信" % i,
        )
    return db, rel, letters


def _expect_code(fn, code, name):
    try:
        fn()
    except ValueError as e:
        check(name, str(e) == code, "实际 %s" % e)
        return
    check(name, False, "未抛 ValueError(%s)" % code)


# --------------------------------------------------------------------- #
# 1. detail 门
# --------------------------------------------------------------------- #
def t_detail_gate():
    db, rel, L = _fresh()
    try:
        _expect_code(
            lambda: letter_service.get_letter(db, B_ID, L["future_locked"].id),
            "60002", "detail：接收方在 unlock 前被拒（60002）",
        )
        _expect_code(
            lambda: letter_service.get_letter(db, B_ID, L["future_null"].id),
            "60002",
            "detail：unlock_time NULL 同样锁定（历史行）",
        )
        got = letter_service.get_letter(db, A_ID, L["future_locked"].id)
        check("detail：发件方仍可查看自己的未解锁信", got.title == SECRET_TITLE, got.title)
        got = letter_service.get_letter(db, B_ID, L["future_unlocked"].id)
        check("detail：已解锁信可查看", got.title == "已解锁的未来信", got.title)
        got = letter_service.get_letter(db, B_ID, L["normal"].id)
        check("detail：普通信不受影响", got.title == "普通的信", got.title)
        got = letter_service.get_letter(db, B_ID, L["calm_locked"].id)
        check("detail：calm 冷静期不在本次范围（维持现状）", got.title == "冷静期的信", got.title)
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 2. list 门
# --------------------------------------------------------------------- #
def t_list_gate():
    db, rel, L = _fresh()
    try:
        items_b, total_b = letter_service.list_letters(db, B_ID)["items"], None
        titles_b = [l.title for l in items_b]
        check(
            "list：接收方看不到未解锁 future（标题不出现）",
            SECRET_TITLE not in titles_b and "无排期的未来信" not in titles_b,
            str(titles_b),
        )
        ids_b = [l.id for l in items_b]
        check(
            "list：已解锁 future / 普通 / calm 仍在",
            L["future_unlocked"].id in ids_b
            and L["normal"].id in ids_b
            and L["calm_locked"].id in ids_b,
            str(ids_b),
        )
        items_a = letter_service.list_letters(db, A_ID)["items"]
        ids_a = [l.id for l in items_a]
        check(
            "list：发件方仍看到自己发出的未解锁信",
            L["future_locked"].id in ids_a,
            str(ids_a),
        )

        # 审查 MEDIUM-1：total 必须剔除被解锁门隐藏的行，与实际返回行数一致
        result_b = letter_service.list_letters(db, B_ID, page_size=100)
        # B 视角可见：future_unlocked + normal + calm_locked + normal_draft
        # + 7 封 pending = 11；隐藏 future_locked / future_null / future_draft
        check(
            "list：total 与实际返回行数一致（SQL 层剔除锁定行）",
            result_b["total"] == len(result_b["items"]) == 11,
            "total=%s len=%s" % (result_b["total"], len(result_b["items"])),
        )
        result_a = letter_service.list_letters(db, A_ID, page_size=100)
        check(
            "list：发件方 total 不受锁影响（自己看得到全部行）",
            result_a["total"] == len(result_a["items"]),
            "total=%s len=%s" % (result_a["total"], len(result_a["items"])),
        )
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 3. AI 理解 / 回信门（同步 + 流式）
# --------------------------------------------------------------------- #
def t_ai_gates():
    db, rel, L = _fresh()
    orig_llm = letter_ai_service._call_llm

    # `client=None`：生产侧 `_call_llm` v5.0 起会收到调用方传入的 `client=`。
    # 桩不接该参数会抛 `TypeError: unexpected keyword argument 'client'`（2026-10-02 修复）。
    def fake_llm(prompt, scene_key, client=None):
        return {"summary": "（stub）", "key_concerns": [], "emotion": "平静",
                "expected_response": "", "misunderstandable": [],
                "reply_suggestions": [], "replies": [],
                "do_not_say": "", "risk_level": "normal",
                "rewritten_title": "", "rewritten_content": "", "changes": ""}

    letter_ai_service._call_llm = fake_llm
    try:
        _expect_code(
            lambda: letter_ai_service.understand_letter(db, B_ID, L["future_locked"].id),
            "60002", "AI 理解（同步）：未解锁被拒",
        )
        _expect_code(
            lambda: letter_ai_service.prepare_understand_letter(
                db, B_ID, rel.id, L["future_locked"].id),
            "60002", "AI 理解（流式）：未解锁被拒",
        )
        _expect_code(
            lambda: letter_ai_service.generate_reply(db, B_ID, L["future_null"].id),
            "60002", "AI 回信（同步）：NULL unlock 同样被拒",
        )
        _expect_code(
            lambda: letter_ai_service.prepare_generate_reply(
                db, B_ID, rel.id, L["future_null"].id),
            "60002", "AI 回信（流式）：NULL unlock 同样被拒",
        )

        result = letter_ai_service.understand_letter(db, B_ID, L["normal"].id)
        check(
            "AI 理解：普通信照常工作（门不误伤）",
            result.get("letter_id") == L["normal"].id
            and result["analysis"]["summary"] == "（stub）",
            str(result)[:120],
        )
        prepared = letter_ai_service.prepare_understand_letter(
            db, B_ID, rel.id, L["normal"].id)
        check(
            "AI 理解（流式）：普通信可占位生成记录",
            prepared.get("generation_id") is not None,
            str(prepared)[:160],
        )
        unlocked = letter_ai_service.understand_letter(
            db, B_ID, L["future_unlocked"].id)
        check(
            "AI 理解：已解锁 future 照常解读",
            unlocked.get("letter_id") == L["future_unlocked"].id,
            str(unlocked)[:120],
        )
        # 发件方解读自己未解锁的信不受限
        sender_view = letter_ai_service.understand_letter(
            db, A_ID, L["future_locked"].id)
        check(
            "AI 理解：发件方不受解锁门限制",
            sender_view.get("letter_id") == L["future_locked"].id,
            str(sender_view)[:120],
        )
    finally:
        letter_ai_service._call_llm = orig_llm
        db.close()


# --------------------------------------------------------------------- #
# 4. 通知标题占位 + 发信冻结
# --------------------------------------------------------------------- #
def t_notification_title():
    db, rel, L = _fresh()
    check(
        "通知标题：未解锁 future → 占位",
        letter_service._notification_title(L["future_locked"]) == LOCKED_LETTER_TITLE,
        letter_service._notification_title(L["future_locked"]),
    )
    check(
        "通知标题：NULL unlock future → 占位",
        letter_service._notification_title(L["future_null"]) == LOCKED_LETTER_TITLE,
        letter_service._notification_title(L["future_null"]),
    )
    check(
        "通知标题：已解锁 future → 真标题",
        letter_service._notification_title(L["future_unlocked"]) == "已解锁的未来信",
        letter_service._notification_title(L["future_unlocked"]),
    )
    check(
        "通知标题：普通信 → 真标题（不误伤）",
        letter_service._notification_title(L["normal"]) == "普通的信",
        letter_service._notification_title(L["normal"]),
    )

    # send_letter：future 草稿被 §2.5-1 冻结（10006）；普通信通知带真标题
    import app.services.notification_service as ns
    import app.services.memory_events as mem_events

    captured = []

    async def fake_notify(receiver_id, sender_id, letter_id, title):
        captured.append((receiver_id, sender_id, letter_id, title))

    orig_notify = ns.notify_letter_received
    orig_distill = mem_events.distill_event_in_background
    ns.notify_letter_received = fake_notify
    mem_events.distill_event_in_background = lambda event: None
    try:
        _expect_code(
            lambda: letter_service.send_letter(db, A_ID, L["future_draft"].id),
            "10006", "send：legacy future 草稿发送被冻结（10006）",
        )
        check(
            "send：冻结发生在通知之前（未产生通知）",
            captured == [],
            str(captured),
        )
        sent = letter_service.send_letter(db, A_ID, L["normal_draft"].id)
        check("send：普通草稿发送成功", sent.status == "sent", sent.status)
        check(
            "send：通知经 schedule_notify 投递且带真标题",
            len(captured) == 1
            and captured[0] == (B_ID, A_ID, sent.id, "待发送的草稿"),
            str(captured),
        )
    finally:
        ns.notify_letter_received = orig_notify
        mem_events.distill_event_in_background = orig_distill
        db.close()

    from pathlib import Path
    src = Path(letter_service.__file__).read_text(encoding="utf-8")
    check(
        "send：旧 get_event_loop 静默失败路径已移除",
        "get_event_loop" not in src,
        "",
    )


# --------------------------------------------------------------------- #
# 5. 首页卡 + pending 计数
# --------------------------------------------------------------------- #
def t_home_card():
    db, rel, L = _fresh()
    try:
        home = home_service._get_couple_home_data(db, B_ID, rel)

        card = home.get("future_letter")
        check(
            "首页卡：存在未解锁 future 卡",
            card is not None and card.get("id") == L["future_locked"].id,
            str(card),
        )
        check(
            "首页卡：title 为占位、不带真实标题",
            card is not None and card.get("title") == LOCKED_LETTER_TITLE,
            str(card),
        )
        check(
            "首页卡：unlock_time 仍给出（倒计时可用）",
            card is not None and bool(card.get("unlock_time")),
            str(card),
        )

        titles = [l.get("title") for l in home.get("pending_letters", [])]
        check(
            "首页 pending：未解锁 future 不进列表",
            SECRET_TITLE not in titles and "无排期的未来信" not in titles,
            str(titles),
        )
        # 7 封计数专用 + 「普通的信」+ 已解锁 future + calm = 10 封真实待回；
        # 两封锁定 future 与两封草稿不计；列表仍只取 5
        count = home.get("pending_letter_count")
        items = home.get("pending_letters", [])
        check(
            "首页 pending_letter_count 是真实总数（非 limit 截断）",
            count == 10,
            "count=%s" % count,
        )
        check(
            "首页 pending 列表仍按 limit=5 截断（展示不变）",
            len(items) == 5,
            "len=%s" % len(items),
        )
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 6. mutation 门：favorite / delete / batch-delete（审查 C1）
# --------------------------------------------------------------------- #
def t_mutation_gates():
    db, rel, L = _fresh()
    try:
        _expect_code(
            lambda: letter_service.toggle_favorite(db, B_ID, L["future_locked"].id),
            "60002", "favorite：接收方未解锁 future 被拒（60002，同 detail）",
        )
        _expect_code(
            lambda: letter_service.toggle_favorite(db, B_ID, L["future_null"].id),
            "60002", "favorite：unlock_time NULL 同样锁定",
        )
        _expect_code(
            lambda: letter_service.delete_letter(db, B_ID, L["future_locked"].id),
            "60002", "delete：接收方未解锁 future 被拒（60002）",
        )
        _expect_code(
            lambda: letter_service.delete_letter(db, B_ID, L["future_null"].id),
            "60002", "delete：unlock_time NULL 同样锁定",
        )

        # 批删：剔除锁定行（与 list 语义一致），只返回/删除非锁定行
        n = letter_service.batch_delete_letters(
            db, B_ID,
            [L["future_locked"].id, L["future_null"].id, L["normal"].id],
        )
        db.commit()
        check("batch-delete：剔除锁定行，只计入非锁定行", n == 1, "n=%s" % n)
        rows = {
            r.id: r
            for r in db.query(Letter)
            .filter(
                Letter.id.in_(
                    [L["future_locked"].id, L["future_null"].id, L["normal"].id]
                )
            )
            .all()
        }
        check(
            "batch-delete：两行锁定 future 未被软删",
            rows[L["future_locked"].id].deleted_at is None
            and rows[L["future_null"].id].deleted_at is None,
            str({k: str(v.deleted_at) for k, v in rows.items()}),
        )
        check(
            "batch-delete：普通信已软删（门不误伤）",
            rows[L["normal"].id].deleted_at is not None,
            str(rows[L["normal"].id].deleted_at),
        )

        # 对照组：发件方与已解锁信不受门限制（同 detail 语义）
        fav_a = letter_service.toggle_favorite(db, A_ID, L["future_locked"].id)
        check("favorite：发件方自己的未解锁信可收藏", fav_a.is_favorite is True,
              str(fav_a.is_favorite))
        fav_b = letter_service.toggle_favorite(db, B_ID, L["future_unlocked"].id)
        check("favorite：已解锁 future 可收藏", fav_b.is_favorite is True,
              str(fav_b.is_favorite))
        letter_service.delete_letter(db, A_ID, L["future_locked"].id)
        db.refresh(L["future_locked"])
        check("delete：发件方可删除自己的未解锁信",
              L["future_locked"].deleted_at is not None,
              str(L["future_locked"].deleted_at))
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 7. 类型冻结 §2.5-1：create / update / send（审查 MEDIUM-2）
# --------------------------------------------------------------------- #
def t_letter_type_freeze():
    db, rel, L = _fresh()
    try:
        for lt in ("future", "private"):
            _expect_code(
                lambda lt=lt: letter_service.create_letter(
                    db, A_ID, {"content": "内容", "letter_type": lt}
                ),
                "10006", "create：letter_type=%s → 10006" % lt,
            )
            _expect_code(
                lambda lt=lt: letter_service.update_letter(
                    db, A_ID, L["normal_draft"].id, {"letter_type": lt}
                ),
                "10006", "update：请求体 letter_type=%s → 10006" % lt,
            )

        private_draft = _mk_letter(
            db, relation_id=rel.id, letter_type="private", status="draft",
            title="遗留的私密信草稿", send_time=None, unlock_time=None,
        )
        _expect_code(
            lambda: letter_service.send_letter(db, A_ID, private_draft.id),
            "10006", "send：private 草稿 → 10006",
        )
        _expect_code(
            lambda: letter_service.send_letter(db, A_ID, L["future_draft"].id),
            "10006", "send：future 草稿 → 10006",
        )

        # 对照组：正常类型三处全部照常
        created = letter_service.create_letter(
            db, A_ID, {"content": "内容", "letter_type": "normal"}
        )
        check("create：normal 照常创建（冻结不误伤）", created.id is not None,
              str(created.id))
        updated = letter_service.update_letter(
            db, A_ID, L["normal_draft"].id, {"title": "改过标题"}
        )
        check("update：仅改标题照常", updated.title == "改过标题", updated.title)
        sent = letter_service.send_letter(db, A_ID, L["normal_draft"].id)
        check("send：normal 草稿照常发送", sent.status == "sent", sent.status)
    finally:
        db.close()


def t_api_letter_type_freeze():
    """MEDIUM-2 端到端：三个端点传 future/private → HTTP 200 + 10006。"""
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.database import get_db
    from app.core.dependencies import get_current_user

    db, rel, L = _fresh()
    private_draft = _mk_letter(
        db, relation_id=rel.id, letter_type="private", status="draft",
        title="遗留的私密信草稿", send_time=None, unlock_time=None,
    )
    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_current_user] = lambda: type("U", (), {"id": A_ID})()
    client = TestClient(app, raise_server_exceptions=False)
    try:
        cases = [
            ("POST", "/api/v1/couple/letters",
             {"content": "内容", "letter_type": "future"}, "create future"),
            ("POST", "/api/v1/couple/letters",
             {"content": "内容", "letter_type": "private"}, "create private"),
            ("PUT", "/api/v1/couple/letters/%d" % L["normal_draft"].id,
             {"letter_type": "future"}, "update future"),
            ("PUT", "/api/v1/couple/letters/%d" % L["normal_draft"].id,
             {"letter_type": "private"}, "update private"),
            ("POST", "/api/v1/couple/letters/%d/send" % L["future_draft"].id,
             None, "send future draft"),
            ("POST", "/api/v1/couple/letters/%d/send" % private_draft.id,
             None, "send private draft"),
        ]
        for method, url, body, label in cases:
            r = (client.post(url, json=body) if method == "POST"
                 else client.put(url, json=body))
            data = (r.json()
                    if r.headers.get("content-type", "").startswith("application/json")
                    else {})
            check(
                "%s %s → 200 + 10006（%s）" % (method, url, label),
                r.status_code == 200 and data.get("code") == 10006
                and data.get("message") == "该功能已停用",
                "%s %s" % (r.status_code, str(data)[:160]),
            )
    finally:
        app.dependency_overrides.pop(get_db, None)
        app.dependency_overrides.pop(get_current_user, None)
        db.close()


# --------------------------------------------------------------------- #
# 8. 生成回读门：GET /ai/generations/{kind} 的 service 层（审查 MEDIUM-3）
# --------------------------------------------------------------------- #
def t_generation_read_gate():
    from app.repositories import ai_generation_repo
    from app.models.ai_generation import AiGeneration

    db, rel, L = _fresh()
    db.query(AiGeneration).delete()
    db.commit()
    try:
        # 本批发布前落库的「未解锁未来信解读」——收件方回读必须被拒
        ai_generation_repo.upsert_generation(
            db,
            user_id=B_ID,
            relation_id=rel.id,
            generation_kind="letter_analysis",
            target_type="letter",
            target_id=L["future_locked"].id,
            scene_key="letter_understand",
            content="解读：TA 想给对方一个惊喜……",
            status="done",
            structured_output={"summary": "TA 想给对方一个惊喜"},
        )
        db.commit()
        _expect_code(
            lambda: ai_generation_service.get_saved(
                db, B_ID, "letter_analysis", "letter", L["future_locked"].id
            ),
            "60002", "生成回读：收件方未解锁 future → 60002",
        )
        # 发件方 / 普通信件 / 无记录 不受影响
        sender_payload = ai_generation_service.get_saved(
            db, A_ID, "letter_analysis", "letter", L["future_locked"].id
        )
        check(
            "生成回读：发件方仍可读自己的解读（门只拦接收方）",
            sender_payload is None or sender_payload.get("generation_kind") == "letter_analysis",
            str(sender_payload)[:120],
        )
        ai_generation_repo.upsert_generation(
            db,
            user_id=B_ID,
            relation_id=rel.id,
            generation_kind="letter_analysis",
            target_type="letter",
            target_id=L["normal"].id,
            scene_key="letter_understand",
            content="普通信解读",
            status="done",
        )
        db.commit()
        normal_payload = ai_generation_service.get_saved(
            db, B_ID, "letter_analysis", "letter", L["normal"].id
        )
        check(
            "生成回读：普通信照常回读（门不误伤）",
            normal_payload is not None and normal_payload.get("content") == "普通信解读",
            str(normal_payload)[:120],
        )
    finally:
        db.close()


def main() -> int:
    print("[存量未来信解锁门] 契约 §2.5-2 回归")
    t_detail_gate()
    t_list_gate()
    t_ai_gates()
    t_notification_title()
    t_home_card()
    t_mutation_gates()
    t_letter_type_freeze()
    t_api_letter_type_freeze()
    t_generation_read_gate()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
