# -*- coding: utf-8 -*-
"""纪念日日期语义回归（整改契约 §8.8 — §8.9-5「纪念日年度重复与一次性日期的跨年计算」）。

## 这条测试挡的是什么

契约原文：**日期语义修正：区分一次性与每年重复；禁止「还有 55 天 · 2025-11-20」
年份冲突 → 「每年 11 月 20 日 / 下次 2026-11-20」**。

修之前，服务端只会「把月日搬到今年，过去了就搬到明年」：
- 用户登记的去年的一次性纪念日，会被算成明年的「还有 N 天」，和它旁边印着的
  年份互相打架（就是契约点名的冲突）；
- 用户填了 2 月 29 日，**平年首页直接 500**（`date.replace(year=...)` 抛
  `ValueError: day is out of range for month`）——这条连栈都是真的，不是假想。

## 覆盖

1. 纯函数边界（不需要库）：跨年滚动、当天算今天、一次性已过返回 None、闰日夹取；
2. 服务层 `serialize_anniversary` 的出参形状（下发给客户端的「下次发生日 + 天数」）；
3. 首页 `upcoming_anniversary` 的挑选规则：按**下次发生日**取最近，一次性已过
   不再冒充「下一次」，闰日不再炸首页；
4. API 出参真的带 `repeat_annually` / `next_occurrence_date` / `days_until`。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 python tests/test_anniversary_dates.py
"""
import os
import sys
from datetime import date, timedelta

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
from app.models.anniversary import Anniversary  # noqa: E402

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


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


# --------------------------------------------------------------------- #
# 1. 纯函数边界
# --------------------------------------------------------------------- #
def t_pure_functions():
    from app.services.anniversary_dates import (
        days_until,
        next_occurrence,
        safe_replace_year,
    )

    # 每年重复：今年还没到 → 就是今年
    check(
        "每年重复·今年未到 → 今年",
        next_occurrence(date(2024, 11, 20), True, date(2026, 9, 26)) == date(2026, 11, 20),
    )
    # 每年重复：今年已过 → 明年（跨年滚动）
    check(
        "每年重复·今年已过 → 明年",
        next_occurrence(date(2024, 3, 1), True, date(2026, 9, 26)) == date(2027, 3, 1),
    )
    # 当天算「就是今天」，不能跳到明年
    today = date(2026, 11, 20)
    check("每年重复·当天 → 今天（0 天）", days_until(date(2020, 11, 20), True, today) == 0)

    # 一次性：将来 → 就是它自己，原样不动
    check(
        "一次性·将来 → 原日期",
        next_occurrence(date(2027, 5, 1), False, date(2026, 9, 26)) == date(2027, 5, 1),
    )
    # 一次性：已过去 → 不再有下一次（契约禁止编造年份）
    check(
        "一次性·已过 → 没有下一次",
        next_occurrence(date(2025, 11, 20), False, date(2026, 9, 26)) is None,
    )
    check(
        "一次性·已过 → 天数为 None（不是硬凑的数字）",
        days_until(date(2025, 11, 20), False, date(2026, 9, 26)) is None,
    )

    # 闰日：平年不能炸，夹到 2 月最后一天
    check(
        "闰日 2/29 落到平年 → 夹到 2/28",
        safe_replace_year(date(2024, 2, 29), 2026) == date(2026, 2, 28),
    )
    check(
        "闰日 2/29 落到闰年 → 仍是 2/29",
        safe_replace_year(date(2024, 2, 29), 2028) == date(2028, 2, 29),
    )
    check(
        "闰日·跨年计算不抛异常",
        next_occurrence(date(2024, 2, 29), True, date(2026, 3, 1)) == date(2027, 2, 28),
    )

    # 跨年边界：12-31 的第二天视角
    check(
        "跨年边界·12-31 次日算到明年的 12-31",
        next_occurrence(date(2026, 12, 31), True, date(2027, 1, 1)) == date(2027, 12, 31),
    )


# --------------------------------------------------------------------- #
# 2. 服务层出参形状
# --------------------------------------------------------------------- #
def t_serialize():
    from app.services.anniversary_service import serialize_anniversary

    today = date(2026, 9, 26)

    yearly = Anniversary(
        id=1, relation_id=1, title="在一起",
        anniversary_date=date(2024, 11, 20), repeat_annually=True,
    )
    out = serialize_anniversary(yearly, today=today)
    check("出参带 repeat_annually", out["repeat_annually"] is True)
    check("出参带下次发生日", out["next_occurrence_date"] == date(2026, 11, 20), str(out))
    check("出参带天数", out["days_until"] == 55, str(out["days_until"]))
    # 契约要求的呈现：原始日期保留（客户端显示「每年 11 月 20 日」）+ 下次日期
    check("原始日期不回退成今天", out["anniversary_date"] == date(2024, 11, 20))

    once = Anniversary(
        id=2, relation_id=1, title="求婚那天",
        anniversary_date=date(2025, 11, 20), repeat_annually=False,
    )
    out2 = serialize_anniversary(once, today=today)
    check("一次性已过 → next_occurrence_date 为 None", out2["next_occurrence_date"] is None)
    check("一次性已过 → days_until 为 None", out2["days_until"] is None)
    check("一次性已过 → repeat_annually 为 False", out2["repeat_annually"] is False)


# --------------------------------------------------------------------- #
# 3. 首页挑选规则
# --------------------------------------------------------------------- #
def seed() -> Session:
    db = Session()
    for table in (Anniversary, CoupleRelation, User):
        db.query(table).delete()
    db.add(User(id=A_ID, email="a@example.com", password_hash="x", has_couple=True))
    db.add(User(id=B_ID, email="b@example.com", password_hash="x", has_couple=True))
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return db, rel


def t_home_pick(db, rel):
    """首页 `upcoming_anniversary`：按**下次发生日**取最近，且不让已过的一次性顶位。"""
    from app.services.home_service import get_home_data

    today = date.today()
    # 一次性、已经过去：绝不能被顶到明年冒充「下一次」
    db.add(Anniversary(
        relation_id=rel.id, title="去年婚礼",
        anniversary_date=today - timedelta(days=400), repeat_annually=False,
    ))
    # 每年重复、还有 10 天
    soon = today + timedelta(days=10)
    db.add(Anniversary(
        relation_id=rel.id, title="在一起",
        anniversary_date=soon.replace(year=2020), repeat_annually=True,
    ))
    # 每年重复、还有 200 天（不该被选中）
    far = today + timedelta(days=200)
    db.add(Anniversary(
        relation_id=rel.id, title="相识",
        anniversary_date=far.replace(year=2019), repeat_annually=True,
    ))
    db.commit()

    data = get_home_data(db, A_ID)
    ann = data.get("upcoming_anniversary")
    check("首页仍下发 upcoming_anniversary", ann is not None, str(ann))
    if ann:
        check("选中最近的那个（10 天）", ann["title"] == "在一起", str(ann))
        check("天数正确", ann["days_until"] == 10, str(ann["days_until"]))
        check("带 repeat_annually", ann["repeat_annually"] is True)
        check(
            "带下次发生日（客户端不再自己算）",
            ann["next_occurrence_date"] == soon.isoformat(),
            str(ann),
        )


def t_home_skips_past_oneoff(db, rel):
    """只有一条「一次性且已过」的纪念日时，首页应说「没有下一次」，而不是编一个。"""
    from app.services.home_service import get_home_data

    today = date.today()
    db.query(Anniversary).delete()
    db.add(Anniversary(
        relation_id=rel.id, title="毕业典礼",
        anniversary_date=today - timedelta(days=30), repeat_annually=False,
    ))
    db.commit()

    data = get_home_data(db, A_ID)
    check(
        "一次性已过 → 首页不再显示为「下一次」",
        data.get("upcoming_anniversary") is None,
        str(data.get("upcoming_anniversary")),
    )


def t_home_leap_day(db, rel):
    """闰日不该炸首页：平年 2 月 29 日的纪念日要能算出一个合法日期。"""
    from app.services.home_service import get_home_data

    db.query(Anniversary).delete()
    db.add(Anniversary(
        relation_id=rel.id, title="闰日纪念",
        anniversary_date=date(2024, 2, 29), repeat_annually=True,
    ))
    db.commit()

    try:
        data = get_home_data(db, A_ID)
    except ValueError as e:  # pragma: no cover - 修复前正是这条路径炸的
        check("闰日不抛 ValueError", False, str(e))
        return
    ann = data.get("upcoming_anniversary")
    check("闰日能算出下一次", ann is not None, str(ann))
    if ann:
        occ = date.fromisoformat(ann["next_occurrence_date"])
        check("闰日下一次是合法日期", occ.year >= date.today().year, str(occ))
        check("闰日下次在 2 月", occ.month == 2, str(occ))


# --------------------------------------------------------------------- #
# 4. API 出参
# --------------------------------------------------------------------- #
def t_api_shape(db):
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.database import get_db
    from app.core.dependencies import get_current_user

    current = type("U", (), {"id": A_ID})()
    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_current_user] = lambda: current
    try:
        client = TestClient(app, raise_server_exceptions=False)
        r = client.get("/api/v1/couple/anniversaries")
        body = r.json()
        check(
            "GET /anniversaries → 200 + code 0",
            r.status_code == 200 and body.get("code") == 0,
            "%s %s" % (r.status_code, str(body)[:200]),
        )
        items = ((body.get("data") or {}).get("items")) or []
        check("列表非空", len(items) >= 1, str(items))
        if items:
            it = items[0]
            for key in ("repeat_annually", "next_occurrence_date", "days_until"):
                check("列表项带 %s" % key, key in it, str(sorted(it)))

        # 创建：默认每年重复
        r2 = client.post("/api/v1/couple/anniversaries", json={
            "title": "默认重复", "anniversary_date": "2024-11-20",
        })
        b2 = r2.json()
        check(
            "POST 不带 repeat_annually → 默认 True（存量语义）",
            r2.status_code == 200 and b2.get("code") == 0
            and (b2.get("data") or {}).get("repeat_annually") is True,
            str(b2)[:200],
        )
        check(
            "POST 回参带下次发生日",
            (b2.get("data") or {}).get("next_occurrence_date") is not None,
            str(b2.get("data")),
        )

        # 创建：显式一次性
        r3 = client.post("/api/v1/couple/anniversaries", json={
            "title": "一次性", "anniversary_date": "2020-01-01", "repeat_annually": False,
        })
        b3 = r3.json()
        d3 = b3.get("data") or {}
        check(
            "POST repeat_annually=false → 已过的一次性不编造下次日期",
            r3.status_code == 200 and b3.get("code") == 0
            and d3.get("repeat_annually") is False
            and d3.get("next_occurrence_date") is None
            and d3.get("days_until") is None,
            str(d3),
        )
    finally:
        app.dependency_overrides.pop(get_db, None)
        app.dependency_overrides.pop(get_current_user, None)


def main() -> int:
    print("[纪念日日期语义] 契约 §8.8 / §8.9-5")
    print("\n[1] 纯函数边界（跨年 / 当天 / 一次性 / 闰日）")
    t_pure_functions()
    print("\n[2] 服务层出参")
    t_serialize()
    db, rel = seed()
    try:
        print("\n[3] 首页挑选规则")
        t_home_pick(db, rel)
        t_home_skips_past_oneoff(db, rel)
        t_home_leap_day(db, rel)
        print("\n[4] API 出参")
        t_api_shape(db)
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
