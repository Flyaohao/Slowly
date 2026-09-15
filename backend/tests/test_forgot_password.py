"""忘记密码 + 解绑错误码拆分 测试（SQLite 内存库，不需要 MySQL / API Key）

## 覆盖什么

1. **验证码落库**：验证码不再存进程内存 dict（重启即丢、多进程不可用），
   改为 `email_verification_code` 表；本测试用 SQLite 内存库建全套表跑通。
2. **邮件服务**：SMTP 未配置时——EMAIL_DEV_MODE 下静默降级（码落库+日志），
   非 dev 模式抛 ValueError("20004")（端点归一为「邮件发送失败」）。
3. **forgot → reset 全链路**：错码 20003、正确改密成功、验证码一次性（复用 20003）。
4. **解绑 30004/30006 拆分**：自己确认自己发起 → 30006；
   对方在冷静期（72h）内确认 → 30004；期满后确认 → 成功解绑。

## 运行

    cd backend && python tests/test_forgot_password.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from datetime import datetime, timedelta  # noqa: E402

from sqlalchemy import create_engine, Integer  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app.core.database import Base  # noqa: E402
import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata
from sqlalchemy import BigInteger  # noqa: E402

# SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）。
# 测试库建表前把 BigInteger 主键类型替换为 Integer。
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()
from app.services import auth_service, email_service, couple_service  # noqa: E402
from app.repositories import email_code_repo, user_repo  # noqa: E402
from app.repositories import couple_repo  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.security.password import hash_password, verify_password  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,  # 内存库跨 session 共享同一连接
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)


def case(name: str, fn):
    try:
        fn()
        print(f"  PASS  {name}")
    except AssertionError as e:
        print(f"  FAIL  {name}: {e}")
        raise


# ---------- 邮件服务降级 ----------

def test_smoke_email_dev_mode():
    email_service.EMAIL_DEV_MODE = True
    email_service.send_verification_code("a@test.com", "123456")  # 不抛即通过


def test_smoke_email_not_configured_no_dev():
    saved = email_service.EMAIL_DEV_MODE
    email_service.EMAIL_DEV_MODE = False
    try:
        email_service.send_verification_code("a@test.com", "123456")
        assert email_service.smtp_configured(), "本机未配 SMTP，应抛 20004；若已配置请改用假 SMTP 断言"
        raise AssertionError("未配置 SMTP 且非 dev 模式应抛 ValueError(20004)")
    except ValueError as e:
        assert str(e) == "20004", f"应为 20004，实际 {e}"
    finally:
        email_service.EMAIL_DEV_MODE = saved


# ---------- forgot / reset 全链路 ----------

def _make_user(db, email):
    return user_repo.create_user(db, email, hash_password("OldPass#123"))


def test_forgot_unknown_email_silent():
    db = Session()
    try:
        assert auth_service.forgot_password(db, "nobody@test.com") is None
        assert email_code_repo.get_valid_code(db, "nobody@test.com") is None
    finally:
        db.close()


def test_forgot_dev_returns_code_and_persists():
    db = Session()
    try:
        _make_user(db, "u1@test.com")
        auth_service.EMAIL_DEV_MODE = True
        email_service.EMAIL_DEV_MODE = True
        code = auth_service.forgot_password(db, "u1@test.com")
        assert code and len(code) == 6, "dev 模式应返回 6 位验证码"
        record = email_code_repo.get_valid_code(db, "u1@test.com")
        assert record and record.code == code, "验证码应落库且可查回"
    finally:
        auth_service.EMAIL_DEV_MODE = False
        email_service.EMAIL_DEV_MODE = False
        db.close()


def test_reset_flow():
    db = Session()
    try:
        _make_user(db, "u2@test.com")
        auth_service.EMAIL_DEV_MODE = True
        email_service.EMAIL_DEV_MODE = True
        code = auth_service.forgot_password(db, "u2@test.com")

        # 错码 → 20003
        try:
            auth_service.reset_password(db, "u2@test.com", "000000", "NewPass#456")
            raise AssertionError("错码应抛 20003")
        except ValueError as e:
            assert str(e) == "20003"

        # 正确码 → 改密成功
        auth_service.reset_password(db, "u2@test.com", code, "NewPass#456")
        user = user_repo.get_user_by_email(db, "u2@test.com")
        assert verify_password("NewPass#456", user.password_hash)

        # 验证码一次性：复用 → 20003
        try:
            auth_service.reset_password(db, "u2@test.com", code, "NewPass#789")
            raise AssertionError("复用验证码应抛 20003")
        except ValueError as e:
            assert str(e) == "20003"
    finally:
        auth_service.EMAIL_DEV_MODE = False
        email_service.EMAIL_DEV_MODE = False
        db.close()


def test_reissue_invalidates_old_code():
    db = Session()
    try:
        _make_user(db, "u3@test.com")
        auth_service.EMAIL_DEV_MODE = True
        email_service.EMAIL_DEV_MODE = True
        code1 = auth_service.forgot_password(db, "u3@test.com")
        code2 = auth_service.forgot_password(db, "u3@test.com")
        assert code1 != code2
        try:
            auth_service.reset_password(db, "u3@test.com", code1, "NewPass#456")
            raise AssertionError("旧码应已作废")
        except ValueError as e:
            assert str(e) == "20003"
        auth_service.reset_password(db, "u3@test.com", code2, "NewPass#456")  # 不抛即通过
    finally:
        auth_service.EMAIL_DEV_MODE = False
        email_service.EMAIL_DEV_MODE = False
        db.close()


# ---------- 解绑 30004 / 30006 ----------

def _make_unbinding_pair(db, requested_at: datetime, requested_by: int, suffix: str) -> None:
    a = _make_user(db, f"ua+{suffix}@test.com")
    b = _make_user(db, f"ub+{suffix}@test.com")
    rel = CoupleRelation(
        user_a_id=a.id, user_b_id=b.id, status="unbinding",
        unbind_requested_by=requested_by, unbind_requested_at=requested_at,
    )
    db.add(rel)
    db.commit()
    return a.id, b.id


def test_confirm_self_request_is_30006():
    db = Session()
    try:
        a_id, _ = _make_unbinding_pair(db, datetime.utcnow() - timedelta(hours=100), requested_by=None, suffix="s1")
        # 把发起人改成 a 自己
        rel = db.query(CoupleRelation).filter(CoupleRelation.user_a_id == a_id).first()
        rel.unbind_requested_by = a_id
        db.commit()
        try:
            couple_service.confirm_unbind(db, a_id)
            raise AssertionError("自己确认自己发起应抛 30006")
        except ValueError as e:
            assert str(e) == "30006", f"应为 30006（自己确认自己），实际 {e}"
    finally:
        db.close()


def test_confirm_within_cooling_is_30004():
    db = Session()
    try:
        a_id, b_id = _make_unbinding_pair(db, datetime.utcnow() - timedelta(hours=10), requested_by=None, suffix="s2")
        rel = db.query(CoupleRelation).filter(CoupleRelation.user_a_id == a_id).first()
        rel.unbind_requested_by = a_id
        db.commit()
        try:
            couple_service.confirm_unbind(db, b_id)
            raise AssertionError("冷静期内确认应抛 30004")
        except ValueError as e:
            assert str(e) == "30004", f"应为 30004（冷静期未满），实际 {e}"
    finally:
        db.close()


def test_confirm_after_cooling_succeeds():
    db = Session()
    try:
        a_id, b_id = _make_unbinding_pair(db, datetime.utcnow() - timedelta(hours=73), requested_by=None, suffix="s3")
        rel = db.query(CoupleRelation).filter(CoupleRelation.user_a_id == a_id).first()
        rel.unbind_requested_by = a_id
        db.commit()
        couple_service.confirm_unbind(db, b_id)  # 不抛即通过
        rel = couple_repo.get_relation_by_user_including_unbinding(db, b_id)
        assert rel is None or rel.status == "dissolved", "期满确认后应解除关系"
    finally:
        db.close()


if __name__ == "__main__":
    print("== test_forgot_password ==")
    case("SMTP 未配置 + 非 dev → 20004", test_smoke_email_not_configured_no_dev)
    case("SMTP 未配置 + dev 模式 → 静默降级", test_smoke_email_dev_mode)
    case("未注册邮箱 forgot 静默不落库", test_forgot_unknown_email_silent)
    case("dev 模式 forgot 返回验证码并落库", test_forgot_dev_returns_code_and_persists)
    case("reset 全链路：错码/改密/一次性", test_reset_flow)
    case("重新下发作废旧验证码", test_reissue_invalidates_old_code)
    case("自己确认自己发起 → 30006", test_confirm_self_request_is_30006)
    case("冷静期内对方确认 → 30004", test_confirm_within_cooling_is_30004)
    case("冷静期满后确认 → 解绑成功", test_confirm_after_cooling_succeeds)
    print("全部通过")
