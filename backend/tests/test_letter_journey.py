# -*- coding: utf-8 -*-
"""§8.9-1 深度表达旅程：写 → 发 → 对方读 → 回应（hermetic，SQLite 内存库）。

## 这条测试挡的是什么

契约 §8.9 第 1 条要求把「关系页 → 深度表达 → 新建信件 → 发送 → 对方阅读 →
回复」做成自动化旅程。信件此前**只有流式 AI 理解**那一段有测试
（`test_letter_stream.py`），**主链路一条断言都没有**——也就是说，
「信根本发不出去」或「发出去对方读不到」这种最致命的问题，全量回归是绿的。

所以这里按契约 §8.0 的六步走查逐条钉住，全程走 HTTP 端点（不是直接调服务）：

    能发现 → 能发起 → 能完成 → 能返回 → 能回看 → 能反馈结果

## 覆盖

1. **能发起**：A 创建草稿（`POST /letters`）→ 列表可见（草稿箱）；
2. **能完成**：`POST /letters/{id}/send` 真的发出去，状态翻 sent，带 send_time；
3. **对方能读**：B 的收件箱（`GET /letters/inbox`）里出现，且 `GET /letters/{id}`
   能拿到正文——**发信人自己也在自己的列表里看得到**（同一封信两个视角）；
4. **能回应**：B 用同一份能力（`POST /letters` + send）回一封信，A 在收件箱读到，
   两封信正文各自独立、互不覆盖；
5. **能反馈结果**：发送即沉淀为记忆事件，归属发送者本人（草稿不沉淀）；
6. **能做不成**：A 不能替 B 发信（越权 60002）、重复发送 60003、
   空正文 422（参数校验，信封码 10002）。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 python tests/test_letter_journey.py
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

from app.models.user import User  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.models.letter import Letter  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101
B_ID = 202
X_ID = 303   # 关系外第三者

A_BODY = "那天我先转身走，不是不在乎，是我怕再说下去会说出我们都收不回的话。"
B_BODY = "我等到凌晨两点。我不需要你当场道歉，我需要你走之前说一句「我一会儿回来」。"


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


IDS = {}

#: 信件发送会触发 `distill_event_in_background`——它开守护线程 + **真 SessionLocal**，
#: 在这个 hermetic 测试里连的是本机 MySQL，而测试用户只存在于内存库，
#: 结果必然是外键 1452「事件行写入失败」刷一屏堆栈。换掉它（同 test_dual_visibility
#: 的做法）既消掉噪音，也正好把「发信 → 沉淀为记忆事件」这条链路钉住：
#: 载荷是**发送者本人的私有记忆**，绝不是对方能读到的共享记忆。
_DISTILL_CALLS = []


def _install_distill_spy():
    from app.services import memory_events

    original = memory_events.distill_event_in_background

    def spy(event):
        _DISTILL_CALLS.append(event)
        return None

    memory_events.distill_event_in_background = spy
    return memory_events, original


def seed() -> Session:
    db = Session()
    for table in (Letter, CoupleRelation, User):
        db.query(table).delete()
    db.commit()
    for uid in (A_ID, B_ID, X_ID):
        db.add(User(id=uid, email="u%d@example.com" % uid, password_hash="x",
                    has_couple=uid != X_ID))
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    IDS["rel_id"] = rel.id
    return db


class Client:
    def __init__(self, db, uid):
        from fastapi.testclient import TestClient
        from app.main import app
        from app.core.database import get_db
        from app.core.dependencies import get_current_user

        self.app = app
        self.get_db = get_db
        self.get_cur = get_current_user
        current = type("U", (), {"id": uid})()
        app.dependency_overrides[get_db] = lambda: db
        app.dependency_overrides[get_current_user] = lambda: current
        self.client = TestClient(app, raise_server_exceptions=False)

    def __enter__(self):
        return self.client

    def __exit__(self, *exc):
        self.app.dependency_overrides.pop(self.get_db, None)
        self.app.dependency_overrides.pop(self.get_cur, None)
        return False


def body_of(resp):
    return resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {}


def dig(data):
    """信件的业务数据在 data 里（单条）或 data.items（列表）。"""
    if isinstance(data, dict) and "items" in data:
        return data.get("items") or []
    return data


def ids_of(items):
    return [it.get("id") for it in (items or [])]


def titles_of(items):
    return [it.get("title") for it in (items or [])]


# --------------------------------------------------------------------- #
# 旅程主干
# --------------------------------------------------------------------- #
def t_full_journey(db: Session):
    print("  -- 能发起：A 写下草稿 --")
    with Client(db, A_ID) as a:
        r = a.post("/api/v1/couple/letters", json={
            "title": "那天我为什么走开", "content": A_BODY, "letter_type": "normal",
        })
        data = body_of(r)
        letter = data.get("data") or {}
        check("A 建草稿 → 200 + code 0",
              r.status_code == 200 and data.get("code") == 0,
              "%s %s" % (r.status_code, str(data)[:200]))
        check("草稿有 id 且状态是 draft", (letter.get("id") or 0) > 0 and letter.get("status") == "draft",
              str(letter)[:160])
        check("收件人自动落到伴侣（客户端不必自己填）", letter.get("receiver_id") == B_ID,
              str(letter.get("receiver_id")))
        lid = letter.get("id")
        IDS["letter_a"] = lid

        drafts = dig(body_of(a.get("/api/v1/couple/letters/drafts")).get("data"))
        check("草稿箱里能看到刚写的这封（能回看的第一步）", lid in ids_of(drafts), str(ids_of(drafts)))

    print("  -- 能完成：发送 --")
    with Client(db, A_ID) as a:
        r = a.post("/api/v1/couple/letters/%d/send" % lid)
        data = body_of(r)
        sent = data.get("data") or {}
        check("发送 → 200 + code 0",
              r.status_code == 200 and data.get("code") == 0,
              "%s %s" % (r.status_code, str(data)[:200]))
        check("状态翻成 sent", sent.get("status") == "sent", str(sent.get("status")))
        check("带上发送时间（后续排序/回看都靠它）", bool(sent.get("send_time")), str(sent)[:160])

    print("  -- 对方能读：B 的收件箱 --")
    with Client(db, B_ID) as b:
        inbox = dig(body_of(b.get("/api/v1/couple/letters/inbox")).get("data"))
        check("B 的收件箱里有这封信", lid in ids_of(inbox), str(ids_of(inbox)))
        unread = next((it for it in inbox if it.get("id") == lid), {})
        check("收件箱条目带状态（客户端据此标未读）", "status" in unread, str(sorted(unread)))

        r = b.get("/api/v1/couple/letters/%d" % lid)
        data = body_of(r)
        got = data.get("data") or {}
        check("B 能读全文 → 200 + code 0",
              r.status_code == 200 and data.get("code") == 0,
              "%s %s" % (r.status_code, str(data)[:200]))
        check("正文一字不差", got.get("content") == A_BODY, str(got.get("content"))[:120])
        check("标题保留", got.get("title") == "那天我为什么走开", str(got.get("title")))

    print("  -- 能回应：B 回一封，A 读得到 --")
    with Client(db, B_ID) as b:
        r = b.post("/api/v1/couple/letters", json={
            "title": "我等到凌晨两点", "content": B_BODY, "letter_type": "normal",
        })
        reply = (body_of(r).get("data") or {})
        check("B 能写信（回应的能力与发信同一套）", (reply.get("id") or 0) > 0, str(reply)[:160])
        check("回信收件人是 A", reply.get("receiver_id") == A_ID, str(reply.get("receiver_id")))
        IDS["letter_b"] = reply.get("id")
        r2 = b.post("/api/v1/couple/letters/%d/send" % IDS["letter_b"])
        check("B 能发出回信", body_of(r2).get("code") == 0, str(body_of(r2))[:200])

    with Client(db, A_ID) as a:
        inbox = dig(body_of(a.get("/api/v1/couple/letters/inbox")).get("data"))
        check("A 收件箱看到回信", IDS["letter_b"] in ids_of(inbox), str(ids_of(inbox)))
        got = body_of(a.get("/api/v1/couple/letters/%d" % IDS["letter_b"])).get("data") or {}
        check("A 读到回信正文", got.get("content") == B_BODY, str(got.get("content"))[:120])

    print("  -- 两封信各自独立，未被覆盖 --")
    with Client(db, A_ID) as a:
        all_items = dig(body_of(a.get("/api/v1/couple/letters", params={"page_size": 50})).get("data"))
        got_a = body_of(a.get("/api/v1/couple/letters/%d" % IDS["letter_a"])).get("data") or {}
        got_b = body_of(a.get("/api/v1/couple/letters/%d" % IDS["letter_b"])).get("data") or {}
        check("A 的列表里两封都在", {IDS["letter_a"], IDS["letter_b"]} <= set(ids_of(all_items)),
              str(ids_of(all_items)))
        check("去程正文未被回信覆盖", got_a.get("content") == A_BODY, str(got_a.get("content"))[:100])
        check("回程正文独立", got_b.get("content") == B_BODY, str(got_b.get("content"))[:100])

    print("  -- 能反馈结果：发送即沉淀为可查看/可删除的记忆 --")
    # 契约 §8.8 的隐私硬规则在这里的前置动作上生效：非双视角源在 reveal 之前
    # 只写事件行，不落共享记忆（见 memory_events 的 STRUCTURED_SOURCES 分支）。
    sent_events = [e for e in _DISTILL_CALLS if e.source == "letter"]
    check("两封已发出的信各沉淀一次（草稿不沉淀）",
          len(sent_events) == 2, str([(e.source, e.source_id) for e in _DISTILL_CALLS]))
    if sent_events:
        check("沉淀来源都是 letter", all(e.source == "letter" for e in sent_events))
        check("沉淀归属发送者本人（不是伴侣、也不是关系）",
              {e.user_id for e in sent_events} == {A_ID, B_ID},
              str([e.user_id for e in sent_events]))
        check("沉淀挂在同一个关系上",
              {e.relation_id for e in sent_events} == {IDS["rel_id"]},
              str([e.relation_id for e in sent_events]))
        check("正文内容包含在沉淀载荷里", any(A_BODY in (e.content or "") for e in sent_events),
              str([(e.content or "")[:40] for e in sent_events]))


# --------------------------------------------------------------------- #
# 边界：发不出去的时候
# --------------------------------------------------------------------- #
def t_cannot_send_for_others(db: Session):
    with Client(db, B_ID) as b:
        r = b.post("/api/v1/couple/letters/%d/send" % IDS["letter_a"])
        check("B 不能替 A 发 A 的信 → 403 + 60002",
              r.status_code == 403 and (body_of(r).get("detail") or {}).get("code") == 60002,
              "%s %s" % (r.status_code, str(body_of(r))[:200]))


def t_double_send_rejected(db: Session):
    with Client(db, A_ID) as a:
        r = a.post("/api/v1/couple/letters/%d/send" % IDS["letter_a"])
        code = body_of(r).get("code")
        check("重复发送 → 60003（不是 500，也不是静默成功）",
              code == 60003, "%s %s" % (r.status_code, str(body_of(r))[:200]))


def t_empty_content_rejected(db: Session):
    with Client(db, A_ID) as a:
        r = a.post("/api/v1/couple/letters", json={"title": "空信", "content": ""})
        data = body_of(r)
        check("空正文 → 参数校验失败 10002（不许发出空信）",
              data.get("code") == 10002, "%s %s" % (r.status_code, str(data)[:200]))


def t_outsider_cannot_read(db: Session):
    with Client(db, X_ID) as x:
        r = x.get("/api/v1/couple/letters/%d" % IDS["letter_a"])
        data = body_of(r)
        code = (data.get("detail") or {}).get("code") if isinstance(data.get("detail"), dict) else data.get("code")
        check("关系外用户读不到信件（60002 或关系门 30005）",
              code in (60002, 30005), "%s %s" % (r.status_code, str(data)[:200]))


def t_single_mode_no_relation(db: Session):
    """单身用户写信：只能写「未说出口」，不会伪造一个收件人。"""
    with Client(db, X_ID) as x:
        r = x.post("/api/v1/couple/letters", json={
            "title": "没寄出去的话", "content": "其实我一直想说。", "letter_type": "unsaid",
        })
        data = body_of(r)
        letter = data.get("data") or {}
        check("单身模式可写 unsaid（不经关系门）",
              r.status_code == 200 and data.get("code") == 0,
              "%s %s" % (r.status_code, str(data)[:200]))
        if data.get("code") == 0:
            check("单身信的收件人是自己（不凭空造伴侣）", letter.get("receiver_id") == X_ID,
                  str(letter.get("receiver_id")))
            check("单身信没有关系 id", letter.get("relation_id") is None, str(letter.get("relation_id")))


def main() -> int:
    print("[§8.9-1 深度表达旅程] 写 → 发 → 对方读 → 回应")
    db = seed()
    module, original_distill = _install_distill_spy()
    try:
        t_full_journey(db)
        print("\n[边界] 发不出去 / 读不到的时候")
        t_cannot_send_for_others(db)
        t_double_send_rejected(db)
        t_empty_content_rejected(db)
        t_outsider_cannot_read(db)
        t_single_mode_no_relation(db)
    finally:
        module.distill_event_in_background = original_distill
        db.close()

    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
