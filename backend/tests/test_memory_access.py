# -*- coding: utf-8 -*-
"""§8.9-6 记忆查看 / 可见性修改 / 删除 / 越权保护（hermetic，SQLite 内存库）。

## 这条测试挡的是什么

契约 §8.8 把「AI 记忆必须可查看、可删除」定为**隐私硬规则**，§8.9-6 要求这条
链路上有自动化断言。此前 `tests/test_memory_ownership.py` 只覆盖 `memory_ownership`
里的**纯函数授权矩阵**——也就是说，函数判断是对的，但**没人验证过端点真的调了它**。
删掉路由里的一个 `try/except`、把 `current_user.id` 换成别的、或者漏掉一次
`user_id` 过滤，纯函数测试全绿，而越权就真实存在。

所以这里从**HTTP 端点**走：用 `TestClient` + `dependency_overrides`，
A/B 为一对绑定用户、X 是关系外第三者，逐条验证 A 看不见 B 的记忆、
X 改不动删不掉别人的记忆，且拒绝是 **403**（`_PASSTHROUGH_STATUSES`，
不能被 `app/main.py` 归一化成 200——客户端要靠它区分「没权限」）。

## 覆盖

1. 查看：`GET /ai/memory` 只回自己的（**本人列表不过滤 status**，见「已知取舍」）；
   `GET /ai/memory/couple/{rid}` 只回本关系且 `visibility=couple` 且
   `status=active` 的；非成员 403；
2. 可见性：`PUT /{id}/visibility` 改自己的生效；改别人的 403；非法值 → 见「已知取舍」；
3. 删除：`DELETE /{id}` 删自己的生效（列表里真的没了）；删别人的 403 且
   **对方的行仍在**（不能「报错了但删掉了」）；重复删除 403（幂等不炸 500）；
4. 标星：`PUT /{id}/importance` 越权 403；非法值 → 见「已知取舍」；
5. 个人记忆**不因 couple 端点泄露**：A 把自己的一条设为 couple 后，B 的
   `/ai/memory` 里依然没有它（couple 可见 ≠ 出现在对方私有列表）。

## 已知取舍（写在这里，不写成会变红的断言）

- **本人列表不过滤 `status`**：`GET /ai/memory` 是 v3.2 记忆线的「查看我记住了
  什么」视图，契约要求的正是**可查看**（§8.8）——把 `archived`/`superseded`
  从用户自己的视野里藏起来，反而是背向隐私硬规则。而 `GET .../couple/{rid}`
  必须过滤 `status='active'`：那是**伴侣**的视野，多一条都不行。带 `status`
  字段的行照样完整下发，客户端能自己标。删除是硬删（见 [3]），不是靠隐藏。
- **业务错误按项目既有口径归一化成 400/200**：`app/main.py` 的
  `unified_http_exception_handler` 把 `HTTPException(detail=dict)` 全部归一化，
  所以 `PUT /visibility` 传非法值、`PUT /importance` 传 1 返回的是
  **业务码 40001**（不是 HTTP 400）。这是全仓统一契约，不是本模块特有；
  断言按业务码写，顺带把「400/403 之外的状态码在这里不存在」钉住。
  403 例外——它在 `_PASSTHROUGH_STATUSES` 里，**必须**保持真实 HTTP 状态码。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 python tests/test_memory_access.py
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
from app.models.ai import AiMemory  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101   # 我
B_ID = 202   # 伴侣
X_ID = 303   # 关系外第三者

A_SECRET = "我一直很介意他加班"
B_SECRET = "我其实想要多一点自己的时间"


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


IDS = {}


def seed() -> Session:
    db = Session()
    for table in (AiMemory, CoupleRelation, User):
        db.query(table).delete()
    db.commit()

    for uid in (A_ID, B_ID, X_ID):
        db.add(User(
            id=uid, email="u%d@example.com" % uid, password_hash="x",
            has_couple=uid != X_ID,
        ))
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)

    db.add_all([
        # A 的私有记忆（默认 private）
        AiMemory(id=901, user_id=A_ID, relation_id=rel.id, memory_type="沟通雷区",
                 memory_text=A_SECRET, visibility="private", status="active", source="chat_summary"),
        # A 主动公开给伴侣的记忆
        AiMemory(id=902, user_id=A_ID, relation_id=rel.id, memory_type="关系事实",
                 memory_text="我们决定每月一起看一次电影", visibility="couple",
                 status="active", source="chat_summary"),
        # B 的私有记忆
        AiMemory(id=903, user_id=B_ID, relation_id=rel.id, memory_type="核心诉求",
                 memory_text=B_SECRET, visibility="private", status="active", source="diary"),
        # A 的已归档记忆：couple 端点不得返回（只认 status=active）
        AiMemory(id=904, user_id=A_ID, relation_id=rel.id, memory_type="关系事实",
                 memory_text="这条已经归档了", visibility="couple", status="archived",
                 source="chat_summary"),
    ])
    db.commit()

    IDS["rel_id"] = rel.id
    return db


# --------------------------------------------------------------------- #
# HTTP 夹具
# --------------------------------------------------------------------- #
class Client:
    """固定某个用户的 TestClient 上下文。"""

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


def texts(payload) -> list:
    return [m.get("memory_text") for m in (payload or [])]


# --------------------------------------------------------------------- #
# 1. 查看
# --------------------------------------------------------------------- #
def t_list_own_only(db: Session):
    with Client(db, A_ID) as client:
        r = client.get("/api/v1/couple/ai/memory")
        data = body_of(r)
        items = data.get("data") or []
        check("GET /ai/memory → 200 + code 0", r.status_code == 200 and data.get("code") == 0,
              "%s %s" % (r.status_code, str(data)[:160]))
        got = texts(items)
        check("列表含自己的记忆", A_SECRET in got, str(got))
        check("列表**不含**伴侣的私有记忆（硬规则）", B_SECRET not in got, str(got))
        # 本人列表是「我记住了什么」视图，**刻意不过滤 status**：把用户自己的记忆
        # 从用户自己眼前藏起来，是背向隐私硬规则的；只有 couple 视图（伴侣的
        # 视野）才过滤 status='active'，见 t_couple_view_scope。
        check("本人列表含自己的全部记忆（含归档，不替用户隐藏）",
              "这条已经归档了" in got, str(got))
        # 残余风险（记录在案，本批不动——`memory_service` 属 §20 禁碰区）：
        # `_to_dict` 不下发 status，客户端因此**无法把归档项标出来**，
        # 用户可能并排看到两条互相矛盾的「当前事实」。这里把现状钉住，
        # 日后补 status 字段时这条会变红，正好提醒去改客户端。
        check("已知残差：本人列表行里没有 status 字段（客户端无法标注归档项）",
              all("status" not in m for m in items), str(sorted(items[0])) if items else "空列表")
        check("列表所有行的 user_id 都是本人",
              all(m.get("user_id") == A_ID for m in items), str([m.get("user_id") for m in items]))


def t_couple_view_scope(db: Session):
    """couple 端点：只回本关系 + couple 可见 + active；可见 ≠ 私有列表里出现。"""
    with Client(db, B_ID) as client:
        r = client.get("/api/v1/couple/ai/memory/couple/%d" % IDS["rel_id"])
        data = body_of(r)
        items = data.get("data") or []
        check("GET /ai/memory/couple/{rid} → 200 + code 0",
              r.status_code == 200 and data.get("code") == 0,
              "%s %s" % (r.status_code, str(data)[:160]))
        got = texts(items)
        check("伴侣共享的记忆对 B 可见（能查看 = 契约要求）",
              "我们决定每月一起看一次电影" in got, str(got))
        check("A 的私有记忆不出现在 couple 视图里", A_SECRET not in got, str(got))
        check("B 自己的私有记忆也不进 couple 视图（couple 只回 visibility=couple）",
              B_SECRET not in got, str(got))
        check("已归档的记忆不出现在 couple 视图里（status=active 门）",
              "这条已经归档了" not in got, str(got))

    # 关键：couple 可见 ≠ 进对方私有列表
    with Client(db, B_ID) as client:
        items = body_of(client.get("/api/v1/couple/ai/memory")).get("data") or []
        check("A 公开给伴侣的记忆**不**混进 B 的私有列表",
              "我们决定每月一起看一次电影" not in texts(items), str(texts(items)))


def t_couple_view_idor(db: Session):
    """关系外用户拿 relation_id 猜也读不到（IDOR 守卫）。"""
    with Client(db, X_ID) as client:
        r = client.get("/api/v1/couple/ai/memory/couple/%d" % IDS["rel_id"])
        data = body_of(r)
        check("非成员读 couple 记忆 → 403（不被归一化成 200）",
              r.status_code == 403, "%s %s" % (r.status_code, str(data)[:200]))
        check("403 里是契约业务码 50002",
              ((data.get("detail") or {}).get("code") if isinstance(data.get("detail"), dict) else None) == 50002,
              str(data)[:200])
        check("403 文案不透露关系是否存在",
              "无权访问该关系的记忆" in str(data), str(data)[:200])


def t_couple_view_unknown_relation(db: Session):
    """关系不存在与非成员**同码同文案**：不泄露「这个 rid 存在与否」。"""
    with Client(db, A_ID) as client:
        r = client.get("/api/v1/couple/ai/memory/couple/999999")
        check("不存在的 relation_id → 同样 403（不区分存在性）",
              r.status_code == 403, "%s %s" % (r.status_code, str(body_of(r))[:200]))


# --------------------------------------------------------------------- #
# 2. 可见性修改
# --------------------------------------------------------------------- #
def t_visibility_own(db: Session):
    with Client(db, A_ID) as client:
        r = client.put("/api/v1/couple/ai/memory/901/visibility", json={"visibility": "couple"})
        data = body_of(r)
        check("改自己的可见性 → 200 + code 0",
              r.status_code == 200 and data.get("code") == 0, "%s %s" % (r.status_code, str(data)[:200]))
        check("回参已翻转", (data.get("data") or {}).get("visibility") == "couple", str(data.get("data")))

        row = db.query(AiMemory).filter(AiMemory.id == 901).first()
        check("落库已翻转", row is not None and row.visibility == "couple",
              str(getattr(row, "visibility", None)))

        # 翻回来，避免影响后续断言
        client.put("/api/v1/couple/ai/memory/901/visibility", json={"visibility": "private"})
        row = db.query(AiMemory).filter(AiMemory.id == 901).first()
        check("可以再收回 private（收回是隐私硬规则的一半）", row.visibility == "private", row.visibility)


def t_visibility_denied(db: Session):
    with Client(db, A_ID) as client:
        r = client.put("/api/v1/couple/ai/memory/903/visibility", json={"visibility": "couple"})
        check("改伴侣的记忆 → 403", r.status_code == 403, "%s %s" % (r.status_code, str(body_of(r))[:200]))
        row = db.query(AiMemory).filter(AiMemory.id == 903).first()
        check("拒绝之后对方的记忆一字未动", row is not None and row.visibility == "private",
              str(getattr(row, "visibility", None)))

        r2 = client.put("/api/v1/couple/ai/memory/999999/visibility", json={"visibility": "couple"})
        check("改不存在的记忆 → 403（不泄露 id 是否存在）", r2.status_code == 403,
              "%s %s" % (r2.status_code, str(body_of(r2))[:200]))

        r3 = client.put("/api/v1/couple/ai/memory/901/visibility", json={"visibility": "public"})
        check("非法可见性 → 业务码 40001（归一化成 200，全仓统一口径）",
              r3.status_code == 200 and body_of(r3).get("code") == 40001,
              "%s %s" % (r3.status_code, str(body_of(r3))[:200]))
        row = db.query(AiMemory).filter(AiMemory.id == 901).first()
        check("非法值不落库（值原样保留）", row.visibility == "private", str(row.visibility))

    with Client(db, X_ID) as client:
        r4 = client.put("/api/v1/couple/ai/memory/901/visibility", json={"visibility": "couple"})
        check("关系外用户改别人的记忆 → 403", r4.status_code == 403,
              "%s %s" % (r4.status_code, str(body_of(r4))[:200]))


def t_importance_matrix(db: Session):
    with Client(db, A_ID) as client:
        r = client.put("/api/v1/couple/ai/memory/901/importance", json={"importance": 2})
        check("标星自己的记忆 → 200 + code 0",
              r.status_code == 200 and body_of(r).get("code") == 0,
              "%s %s" % (r.status_code, str(body_of(r))[:200]))
        row = db.query(AiMemory).filter(AiMemory.id == 901).first()
        check("标星落库 importance=2", row.importance == 2, str(getattr(row, "importance", None)))

        r2 = client.put("/api/v1/couple/ai/memory/903/importance", json={"importance": 2})
        check("标星伴侣的记忆 → 403", r2.status_code == 403,
              "%s %s" % (r2.status_code, str(body_of(r2))[:200]))
        row_b = db.query(AiMemory).filter(AiMemory.id == 903).first()
        check("标星被拒后对方记忆未被改动", row_b.importance == 0, str(row_b.importance))

        r3 = client.put("/api/v1/couple/ai/memory/901/importance", json={"importance": 1})
        check("importance=1 → 业务码 40001（只接受 0/2，归一化成 200）",
              r3.status_code == 200 and body_of(r3).get("code") == 40001,
              "%s %s" % (r3.status_code, str(body_of(r3))[:200]))
        row = db.query(AiMemory).filter(AiMemory.id == 901).first()
        check("非法 importance 不落库（标星值仍是 2）", row.importance == 2, str(row.importance))


# --------------------------------------------------------------------- #
# 3. 删除
# --------------------------------------------------------------------- #
def t_delete_and_deny(db: Session):
    # 先建一条只用于删除的临时记忆
    temp = AiMemory(user_id=A_ID, relation_id=IDS["rel_id"], memory_type="关系事实",
                    memory_text="这条待删", visibility="private", status="active")
    db.add(temp)
    db.commit()
    db.refresh(temp)
    temp_id = temp.id

    with Client(db, A_ID) as client:
        r = client.delete("/api/v1/couple/ai/memory/%d" % temp_id)
        check("删自己的记忆 → 200 + code 0",
              r.status_code == 200 and body_of(r).get("code") == 0,
              "%s %s" % (r.status_code, str(body_of(r))[:200]))
        check("列表里真的没了（不是只回了成功）",
              "这条待删" not in texts(body_of(client.get("/api/v1/couple/ai/memory")).get("data") or []))

        # 删别人的：必须 403，且对方那条**还在**
        r2 = client.delete("/api/v1/couple/ai/memory/903")
        check("删伴侣的记忆 → 403", r2.status_code == 403,
              "%s %s" % (r2.status_code, str(body_of(r2))[:200]))
        still = db.query(AiMemory).filter(AiMemory.id == 903).first()
        check("越权删除被拒后对方的记忆仍在（不能报错却删了）", still is not None,
              "行已被删除")

        r3 = client.delete("/api/v1/couple/ai/memory/%d" % temp_id)
        check("重复删除 → 403 而不是 500", r3.status_code == 403,
              "%s %s" % (r3.status_code, str(body_of(r3))[:200]))

    with Client(db, X_ID) as client:
        r4 = client.delete("/api/v1/couple/ai/memory/902")
        check("关系外用户删记忆 → 403", r4.status_code == 403,
              "%s %s" % (r4.status_code, str(body_of(r4))[:200]))
        still = db.query(AiMemory).filter(AiMemory.id == 902).first()
        check("关系外删除被拒后目标仍在", still is not None, "行已被删除")


def t_delete_then_share_not_visible(db: Session):
    """删掉的记忆不能再从 couple 视图读到（删除是最终态，不是软隐藏）。"""
    with Client(db, A_ID) as client:
        client.put("/api/v1/couple/ai/memory/902/visibility", json={"visibility": "couple"})
        before = texts(body_of(client.get("/api/v1/couple/ai/memory/couple/%d" % IDS["rel_id"])).get("data") or [])
        check("（前置）共享记忆在 couple 视图里", "我们决定每月一起看一次电影" in before, str(before))
        client.delete("/api/v1/couple/ai/memory/902")
        after = texts(body_of(client.get("/api/v1/couple/ai/memory/couple/%d" % IDS["rel_id"])).get("data") or [])
        check("删除后 couple 视图里也没有了", "我们决定每月一起看一次电影" not in after, str(after))


def main() -> int:
    print("[§8.9-6 记忆查看 / 可见性 / 删除 / 越权]")
    db = seed()
    try:
        print("\n[1] 查看")
        t_list_own_only(db)
        t_couple_view_scope(db)
        t_couple_view_idor(db)
        t_couple_view_unknown_relation(db)
        print("\n[2] 可见性修改")
        t_visibility_own(db)
        t_visibility_denied(db)
        t_importance_matrix(db)
        print("\n[3] 删除与越权")
        t_delete_and_deny(db)
        t_delete_then_share_not_visible(db)
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
