# -*- coding: utf-8 -*-
"""解绑冷却期守卫回归（契约 §2.6，SQLite 内存库，不需要 MySQL / API Key）。

## 覆盖什么

P0-6 的四条目标契约与验收点：

1. **§2.6-1**：`status=="unbinding"` 时重复 `POST /couples/unbind` 拒绝
   `30007`（解绑申请已在冷却期）——72h 时钟不可被重置、发起人不可被覆盖；
2. **§2.6-3**：`cancel` 成功后接线 `notify_unbind_cancelled`（此前零调用点，
   测试用 async spy 断言）；
3. **§2.6-4**：`POST /admin/tasks/check-unbinding-timeout` 加内部口令鉴权——
   未授权（无口令/口令错）一律 403，且不执行任务；
4. **§3.5**：`GET /couples/me` 只增不减地补 `unbind_requested_at` /
   `unbind_requested_by` / `love_days`；
5. 确认链 happy path：非发起方 + 满 72h → `dissolved`。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_unbind_guard.py
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

from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.services import couple_service  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101  # 发起绑定方
B_ID = 202  # 伴侣


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def _fresh(bind_days: int = 100) -> Session:
    db = Session()
    db.query(CoupleRelation).delete()
    db.commit()
    rel = CoupleRelation(
        user_a_id=A_ID,
        user_b_id=B_ID,
        status="active",
        bind_time=datetime.utcnow() - timedelta(days=bind_days),
    )
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return db


# --------------------------------------------------------------------- #
# 1. 30007 守卫（服务层）
# --------------------------------------------------------------------- #
def t_request_guard():
    db = _fresh()
    try:
        couple_service.request_unbind(db, A_ID)
        rel = db.query(CoupleRelation).first()
        check("首次申请进入 unbinding", rel.status == "unbinding", rel.status)
        first_at = rel.unbind_requested_at
        first_by = rel.unbind_requested_by
        check("首次申请记录发起人", first_by == A_ID, str(first_by))
        check("首次申请记录时间", first_at is not None, str(first_at))

        try:
            couple_service.request_unbind(db, B_ID)
            check("冷却期内重复申请被拒（30007）", False, "未抛异常")
        except ValueError as e:
            check("冷却期内重复申请被拒（30007）", str(e) == "30007", str(e))

        db.expire_all()
        rel = db.query(CoupleRelation).first()
        check(
            "重复申请后 unbind_requested_at 不变（时钟未被重置）",
            rel.unbind_requested_at == first_at,
            "%s vs %s" % (rel.unbind_requested_at, first_at),
        )
        check(
            "重复申请后发起人未被覆盖",
            rel.unbind_requested_by == first_by,
            str(rel.unbind_requested_by),
        )
        check("关系仍为 unbinding", rel.status == "unbinding", rel.status)
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 2. API 层 30007 信封（HTTP 200 + code 30007）
# --------------------------------------------------------------------- #
def t_api_unbind_envelope():
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.database import get_db
    from app.core.dependencies import get_current_user

    db = _fresh()
    current = type("U", (), {"id": A_ID})()

    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_current_user] = lambda: current
    client = TestClient(app, raise_server_exceptions=False)
    try:
        r1 = client.post("/api/v1/couples/unbind")
        body1 = r1.json()
        check(
            "API 首次 unbind → 200 + code 0",
            r1.status_code == 200 and body1.get("code") == 0,
            "%s %s" % (r1.status_code, body1),
        )

        r2 = client.post("/api/v1/couples/unbind")
        body2 = r2.json()
        check(
            "API 重复 unbind → 归一化 200 + code 30007",
            r2.status_code == 200 and body2.get("code") == 30007,
            "%s %s" % (r2.status_code, body2),
        )
        check(
            "30007 message 正确",
            body2.get("message") == "解绑申请已在冷却期",
            str(body2.get("message")),
        )
    finally:
        app.dependency_overrides.pop(get_db, None)
        app.dependency_overrides.pop(get_current_user, None)
        db.close()


# --------------------------------------------------------------------- #
# 3. cancel → notify_unbind_cancelled（§2.6-3）
# --------------------------------------------------------------------- #
def t_cancel_notify():
    db = _fresh()
    import app.services.notification_service as ns

    captured = []

    async def fake_cancelled(user_a_id, user_b_id):
        captured.append((user_a_id, user_b_id))

    orig = ns.notify_unbind_cancelled
    ns.notify_unbind_cancelled = fake_cancelled
    try:
        couple_service.request_unbind(db, A_ID)
        couple_service.cancel_unbind(db, A_ID)
        rel = db.query(CoupleRelation).first()
        check("cancel 后状态回 active", rel.status == "active", rel.status)
        check(
            "cancel 接线 notify_unbind_cancelled（双方）",
            captured == [(A_ID, B_ID)],
            str(captured),
        )
        check(
            "cancel 后冷却字段清空",
            rel.unbind_requested_at is None and rel.unbind_requested_by is None,
            "%s/%s" % (rel.unbind_requested_at, rel.unbind_requested_by),
        )

        # 取消后可以再次发起（守卫只拦 unbinding 中的）
        couple_service.request_unbind(db, B_ID)
        check("取消后可再次发起解绑", db.query(CoupleRelation).first().status == "unbinding")
    finally:
        ns.notify_unbind_cancelled = orig
        db.close()


# --------------------------------------------------------------------- #
# 4. GET /couples/me 只增不减字段（§3.5）
# --------------------------------------------------------------------- #
def t_me_additive_fields():
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.database import get_db
    from app.core.dependencies import get_current_user

    db = _fresh(bind_days=100)
    current = type("U", (), {"id": A_ID})()
    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_current_user] = lambda: current
    client = TestClient(app, raise_server_exceptions=False)
    try:
        r = client.get("/api/v1/couples/me")
        body = r.json()
        data = body.get("data") or {}
        check("GET /couples/me 成功", r.status_code == 200 and body.get("code") == 0, str(body)[:160])
        # 旧字段只增不减
        for old_key in ("id", "user_a_id", "user_b_id", "status", "bind_time", "space"):
            check("旧字段保留：%s" % old_key, old_key in data, str(list(data)))
        check(
            "新增 love_days（绑定满 100 天）",
            data.get("love_days") == 100,
            str(data.get("love_days")),
        )
        check(
            "active 时无冷却字段值",
            data.get("unbind_requested_at") is None and data.get("unbind_requested_by") is None,
            str((data.get("unbind_requested_at"), data.get("unbind_requested_by"))),
        )

        couple_service.request_unbind(db, A_ID)
        r2 = client.get("/api/v1/couples/me")
        data2 = (r2.json().get("data") or {})
        check(
            "unbinding 时冷却字段非空（FE 展示冷却状态）",
            bool(data2.get("unbind_requested_at")) and data2.get("unbind_requested_by") == A_ID,
            str((data2.get("unbind_requested_at"), data2.get("unbind_requested_by"))),
        )
        check(
            "status=unbinding 透出",
            data2.get("status") == "unbinding",
            str(data2.get("status")),
        )
    finally:
        app.dependency_overrides.pop(get_db, None)
        app.dependency_overrides.pop(get_current_user, None)
        db.close()


# --------------------------------------------------------------------- #
# 5. 确认链 happy path（非发起方 + 满 72h）
# --------------------------------------------------------------------- #
def t_confirm_flow():
    db = _fresh()
    import app.services.notification_service as ns
    import app.services.couple_service as cs_mod

    async def noop(*args, **kwargs):
        return None

    orig_confirmed = ns.notify_unbind_confirmed
    orig_disconnect = cs_mod._disconnect_user_websockets
    orig_notify_req = ns.notify_unbind_requested
    ns.notify_unbind_confirmed = noop
    ns.notify_unbind_requested = noop
    cs_mod._disconnect_user_websockets = noop
    try:
        couple_service.request_unbind(db, A_ID)
        rel = db.query(CoupleRelation).first()
        # 伪造「已过 72h」
        rel.unbind_requested_at = datetime.utcnow() - timedelta(hours=73)
        db.commit()

        try:
            couple_service.confirm_unbind(db, A_ID)
            check("发起方不能自我确认（30006）", False, "未抛异常")
        except ValueError as e:
            check("发起方不能自我确认（30006）", str(e) == "30006", str(e))

        # 未满 72h 的冷静期拦截
        rel.unbind_requested_at = datetime.utcnow() - timedelta(hours=1)
        db.commit()
        try:
            couple_service.confirm_unbind(db, B_ID)
            check("冷静期未满拒绝确认（30004）", False, "未抛异常")
        except ValueError as e:
            check("冷静期未满拒绝确认（30004）", str(e) == "30004", str(e))

        rel.unbind_requested_at = datetime.utcnow() - timedelta(hours=73)
        db.commit()
        couple_service.confirm_unbind(db, B_ID)
        db.expire_all()
        rel = db.query(CoupleRelation).first()
        check("满 72h 伴侣确认 → dissolved", rel.status == "dissolved", rel.status)
        check(
            "确认记录 unbind_confirmed_at",
            rel.unbind_confirmed_at is not None,
            str(rel.unbind_confirmed_at),
        )
    finally:
        ns.notify_unbind_confirmed = orig_confirmed
        ns.notify_unbind_requested = orig_notify_req
        cs_mod._disconnect_user_websockets = orig_disconnect
        db.close()


# --------------------------------------------------------------------- #
# 6. admin 端点内部口令（§2.6-4）
# --------------------------------------------------------------------- #
def t_admin_auth():
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.dependencies import get_current_user
    import app.api.v1.admin as admin_mod

    current = type("U", (), {"id": A_ID})()
    app.dependency_overrides[get_current_user] = lambda: current
    client = TestClient(app, raise_server_exceptions=False)

    orig_token = admin_mod.ADMIN_TASK_TOKEN
    orig_task = admin_mod.check_unbinding_timeout
    task_calls = []

    def fake_task():
        task_calls.append(1)

    admin_mod.check_unbinding_timeout = fake_task
    url = "/api/v1/admin/tasks/check-unbinding-timeout"
    try:
        # 环境未配置口令 → 即便已登录也 403
        admin_mod.ADMIN_TASK_TOKEN = ""
        r = client.post(url)
        check(
            "未配置口令 → 403（默认关闭）",
            r.status_code == 403,
            str(r.status_code),
        )

        # 配置了口令，但普通登录用户没带 → 403，任务不执行
        admin_mod.ADMIN_TASK_TOKEN = "unit-test-secret"
        r = client.post(url)
        check(
            "已登录但无口令 → 403（原「任意登录用户可触发」缺口已堵）",
            r.status_code == 403,
            str(r.status_code),
        )
        detail = r.json().get("detail") or {}
        check(
            "403 detail 带 code 10004",
            detail.get("code") == 10004,
            str(detail),
        )
        check("403 时任务未执行", task_calls == [], str(task_calls))

        r = client.post(url, headers={"X-Admin-Token": "wrong"})
        check("错误口令 → 403", r.status_code == 403, str(r.status_code))
        check("错误口令时任务未执行", task_calls == [], str(task_calls))

        r = client.post(url, headers={"X-Admin-Token": "unit-test-secret"})
        check("正确口令 → 通过鉴权（非 403）", r.status_code != 403, str(r.status_code))
        check(
            "正确口令 → 任务执行且返回 0",
            r.status_code == 200 and r.json().get("code") == 0,
            "%s %s" % (r.status_code, r.json()),
        )
        check("正确口令时任务执行一次", len(task_calls) == 1, str(task_calls))
    finally:
        admin_mod.ADMIN_TASK_TOKEN = orig_token
        admin_mod.check_unbinding_timeout = orig_task
        app.dependency_overrides.pop(get_current_user, None)


def main() -> int:
    print("[解绑冷却期守卫] 契约 §2.6 回归")
    t_request_guard()
    t_api_unbind_envelope()
    t_cancel_notify()
    t_me_additive_fields()
    t_confirm_flow()
    t_admin_auth()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
