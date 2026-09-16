"""邮件通知通道（主线 C 方案②）测试（SQLite 内存库，不需要 MySQL / SMTP / API Key）

## 覆盖什么

1. **默认关闭**：开关没打开时一个字节都不发、也不记账；
2. **开关生效**：打开后发信并写台账；
3. **同类冷却**：30 分钟内第 2 封同类事件被拦；
4. **每日上限**：24 小时内累计到 10 封后，任何类型都被拦；
5. **白名单**：`partner_moment` 这类高频低价值事件永远不发；
6. **隐私红线**：邮件模板里不存在任何业务内容占位符，
   且 `send_push_notification` 的入参只有事件类型——结构上就不可能把信件内容带出去；
7. **源码级**：三个业务调用点不再把 title / 昵称塞进通知。

## 运行

    cd backend && python tests/test_email_notify.py
"""
import os
import re
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

import app.core.database as database  # noqa: E402
from app.services import email_service, notification_service  # noqa: E402
from app.repositories import notification_repo, user_repo  # noqa: E402
from app.models.notification_email_log import NotificationEmailLog  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

# 让被测代码里的 `from app.core.database import SessionLocal` 拿到测试库
database.SessionLocal = Session


def case(name: str, fn):
    try:
        fn()
        print(f"  PASS  {name}")
    except AssertionError as e:
        print(f"  FAIL  {name}: {e}")
        raise


# ---------- 测试替身 ----------

SENT: list[tuple[str, str]] = []


def _fake_send(to_email: str, event_type: str) -> bool:
    SENT.append((to_email, event_type))
    return True


def _setup():
    """清库 + 装替身，每个用例都从干净状态开始。"""
    SENT.clear()
    db = Session()
    try:
        db.query(NotificationEmailLog).delete()
        db.commit()
    finally:
        db.close()

    email_service.smtp_configured = lambda: True
    email_service.send_notification_mail = _fake_send


def _make_user(enabled: bool = False) -> tuple[int, str]:
    """每个用例一个全新邮箱：user.email 唯一，复用一个地址会撞唯一索引。"""
    _seq[0] += 1
    email = f"u{_seq[0]}@test.com"
    db = Session()
    try:
        user = user_repo.create_user(db, email, "x")
        user_repo.update_email_notify(db, user.id, enabled)
        return user.id, email
    finally:
        db.close()


_seq = [0]


def _log_count(user_id: int, event_type: str | None = None) -> int:
    db = Session()
    try:
        return notification_repo.count_logs(
            db, user_id, since=datetime.utcnow() - timedelta(hours=48), event_type=event_type
        )
    finally:
        db.close()


# ---------- 用例 ----------

def test_default_off_sends_nothing():
    uid, _ = _make_user(enabled=False)
    notification_service._deliver_email_notification(uid, "letter_received")
    assert SENT == [], f"开关关闭时不应发信，实际发了 {SENT}"
    assert _log_count(uid) == 0, "开关关闭时不应记账"


def test_switch_on_sends_and_logs():
    uid, email = _make_user(enabled=True)
    notification_service._deliver_email_notification(uid, "letter_received")
    assert SENT == [(email, "letter_received")], f"应发一封，实际 {SENT}"
    assert _log_count(uid) == 1, "应记一条台账"


def test_cooldown_blocks_duplicate():
    uid, _ = _make_user(enabled=True)
    notification_service._deliver_email_notification(uid, "letter_received")
    notification_service._deliver_email_notification(uid, "letter_received")
    notification_service._deliver_email_notification(uid, "letter_received")
    assert len(SENT) == 1, f"30 分钟内同类事件只该发一封，实际 {len(SENT)}"


def test_daily_limit_blocks_all_kinds():
    uid, _ = _make_user(enabled=True)
    # 预置 9 条「已过同类冷却窗口、但仍在 24 小时内」的台账，
    # 这样只剩每日总量上限在起作用，冷并不会抢答。
    db = Session()
    try:
        for _ in range(notification_service.EMAIL_NOTIFY_DAILY_LIMIT - 1):
            notification_repo.create_log(db, uid, "letter_received")
        db.query(NotificationEmailLog).filter(
            NotificationEmailLog.user_id == uid
        ).update({"created_at": datetime.utcnow() - timedelta(hours=1)})
        db.commit()
    finally:
        db.close()

    # 第 10 封：刚好到上限，放行
    notification_service._deliver_email_notification(uid, "letter_received")
    assert len(SENT) == 1, f"未到上限时第 10 封应放行，实际 {SENT}"

    # 第 11 封：不同事件类型（绕过同类冷却），仍应被每日上限拦下
    notification_service._deliver_email_notification(uid, "mediation_invite")
    assert len(SENT) == 1, f"触及每日上限后任何类型都不应再发，实际 {SENT}"


def test_whitelist_blocks_low_value_events():
    uid, _ = _make_user(enabled=True)
    for evt in ["partner_moment", "companion_request", "letter_read", "system_notice"]:
        notification_service._deliver_email_notification(uid, evt)
    assert SENT == [], f"白名单外的事件不应发信，实际 {SENT}"


def test_smtp_unconfigured_is_silent():
    uid, _ = _make_user(enabled=True)
    email_service.smtp_configured = lambda: False
    notification_service._deliver_email_notification(uid, "letter_received")
    assert SENT == [], "SMTP 未配置时不该尝试发信"
    assert _log_count(uid) == 0, "没发出去不该记账"


def test_templates_carry_no_payload():
    """隐私红线：模板正文里不能出现任何业务内容占位符。"""
    for event_type, (subject, body) in email_service.NOTIFICATION_MAILS.items():
        text = subject + body
        for leak in ["{title}", "{name}", "{content}", "{nickname}", "{letter"]:
            assert leak not in text, f"{event_type} 模板含内容占位符 {leak}"
            assert "{" not in text, f"{event_type} 模板含花括号，可能被当成格式化占位符"
    assert set(email_service.NOTIFICATION_MAILS) == set(
        notification_service.EMAIL_NOTIFY_WHITELIST
    ), "模板表与白名单必须一一对应"


def test_push_signature_takes_only_event_type():
    """结构上就无法泄露内容：入参只有 user_id 与 event_type。"""
    import inspect

    params = list(inspect.signature(notification_service.send_push_notification).parameters)
    assert params == ["user_id", "event_type"], f"签名被改了：{params}"


def test_callers_pass_no_content():
    """三个业务调用点不得再把 title / 昵称传进通知。"""
    src = open(
        os.path.join(BACKEND_DIR, "app", "services", "notification_service.py"),
        encoding="utf-8",
    ).read()
    calls = re.findall(r"(?<!def )send_push_notification\((.*?)\)", src, re.S)
    assert len(calls) == 3, f"应恰好 3 个调用点，实际 {len(calls)}"
    for call in calls:
        arg = call.strip()
        assert re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*,\s*NotificationType\.[A-Z_]+\.value", arg), (
            f"调用点参数不合法（可能带上了内容）：{arg}"
        )


def test_thread_is_fire_and_forget():
    """send_push_notification 必须立即返回，不能把 SMTP 阻塞在调用线程上。"""
    import threading
    import time

    uid, _ = _make_user(enabled=True)
    before = threading.active_count()
    t0 = time.monotonic()
    notification_service.send_push_notification(uid, "letter_received")
    elapsed = time.monotonic() - t0
    assert elapsed < 0.5, f"调用被阻塞了 {elapsed:.2f}s"
    # 等后台线程把活干完
    for _ in range(100):
        if _log_count(uid) >= 1:
            break
        time.sleep(0.05)
    assert _log_count(uid) == 1, "后台线程应完成发信与记账"
    assert threading.active_count() == before, "后台线程应收尾（daemon，不常驻）"


def main():
    print("邮件通知通道测试")
    print("=" * 60)
    case("默认关闭：不发信不记账", test_default_off_sends_nothing)
    _setup()
    case("开关打开：发信并记账", test_switch_on_sends_and_logs)
    _setup()
    case("同类冷却：30 分钟内只发一封", test_cooldown_blocks_duplicate)
    _setup()
    case("每日上限：跨类型总量封顶", test_daily_limit_blocks_all_kinds)
    _setup()
    case("白名单：高频低价值事件不发", test_whitelist_blocks_low_value_events)
    _setup()
    case("SMTP 未配置：静默跳过", test_smtp_unconfigured_is_silent)
    _setup()
    case("隐私：模板不含内容占位符", test_templates_carry_no_payload)
    _setup()
    case("隐私：推送签名只收事件类型", test_push_signature_takes_only_event_type)
    _setup()
    case("隐私：业务调用点不传内容", test_callers_pass_no_content)
    _setup()
    case("非阻塞：fire-and-forget 语义", test_thread_is_fire_and_forget)
    print("=" * 60)
    print("✅ 全部通过")


if __name__ == "__main__":
    main()
