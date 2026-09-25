"""P-C3 §6：记忆列表筛选与排序（真库读写、不调模型）。

用例：
  ① source 过滤只回该源
  ② importance=2 只回标星
  ③ 排序：180 天前 / 今天 / 无 occurred_at → 按 coalesce(occurred_at,
     created_at) 倒序 + id 稳定 tiebreak（NULL 回退 created_at）

数据隔离：临时行全部按 id 在 finally 单行删除，开头/结尾各记一次
ai_memory 基线行数，必须相等并打印「基线行数 == 收尾行数」。

运行：cd backend && python tests/test_memory_filter.py
"""
import os
import sys
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []
_CREATED_IDS = []


def ok(name: str, passed: bool, detail: str = ""):
    mark = "PASS" if passed else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


def _baseline_count() -> int:
    from sqlalchemy import text
    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        return db.execute(text("SELECT COUNT(*) FROM ai_memory")).scalar()
    finally:
        db.close()


def _pick_user_relation(db):
    """取一个现有 active 关系的 (user_id, relation_id)（FK 必须真实存在）。"""
    from app.models.couple_relation import CoupleRelation

    rel = (
        db.query(CoupleRelation)
        .filter(CoupleRelation.status == "active")
        .order_by(CoupleRelation.id.asc())
        .first()
    )
    if rel is None:
        raise RuntimeError("库里没有 active couple_relation，无法造临时行")
    return rel.user_a_id, rel.id


def _insert(db, **kw):
    from app.models.ai import AiMemory

    row = AiMemory(**kw)
    db.add(row)
    db.commit()
    _CREATED_IDS.append(row.id)
    return row.id


def _cleanup(db):
    from app.models.ai import AiMemory

    for row_id in list(_CREATED_IDS):
        row = db.query(AiMemory).filter(AiMemory.id == row_id).first()
        if row is not None:
            db.delete(row)
            db.commit()
    _CREATED_IDS.clear()


def main() -> int:
    print("=" * 72)
    print("P-C3 记忆列表筛选与排序（source / importance / occurred_at 回退）")
    print("=" * 72)

    baseline = _baseline_count()
    from app.core.database import SessionLocal
    from app.services import memory_service

    db = SessionLocal()
    try:
        user_id, relation_id = _pick_user_relation(db)
        now = datetime.now()

        # 三条临时行：180 天前 / 今天 / 无 occurred_at
        id_old = _insert(
            db, user_id=user_id, relation_id=relation_id, memory_type="关系事实",
            memory_text="P-C3过滤测试-180天前", visibility="private",
            occurred_at=now - timedelta(days=180), source="letter", importance=0,
        )
        # occurred_at 取今天 1 小时前：与无 occurred_at 行（created_at≈now）
        # 拉开整小时，避免同秒时退化到 id tiebreak 的顺序歧义
        id_today = _insert(
            db, user_id=user_id, relation_id=relation_id, memory_type="偏好",
            memory_text="P-C3过滤测试-今天", visibility="private",
            occurred_at=now - timedelta(hours=1), source="diary", importance=2,
        )
        id_null = _insert(
            db, user_id=user_id, relation_id=relation_id, memory_type="关系事实",
            memory_text="P-C3过滤测试-无发生时间", visibility="private",
            occurred_at=None, source="dual", importance=0,
        )

        # ① source 过滤
        rows = memory_service.get_memories(db, user_id, source="letter")
        ids = [r["id"] for r in rows]
        ok("① source=letter 结果全为 letter",
           all(r["source"] == "letter" for r in rows),
           str(sorted({r["source"] for r in rows})))
        ok("① 含临时 letter 行", id_old in ids, f"missing {id_old}")

        # ② importance=2 过滤
        rows = memory_service.get_memories(db, user_id, importance=2)
        ids = [r["id"] for r in rows]
        ok("② importance=2 结果全为标星",
           all(r["importance"] == 2 for r in rows),
           str(sorted({r["importance"] for r in rows})))
        ok("② 含临时标星行", id_today in ids, f"missing {id_today}")

        # ③ 排序：coalesce(occurred_at, created_at) desc + id desc
        rows = memory_service.get_memories(db, user_id)
        ids = [r["id"] for r in rows]
        ok("③ 三行都在结果里", all(i in ids for i in (id_old, id_today, id_null)),
           f"ids={ids[:8]}")
        pos = {i: ids.index(i) for i in (id_old, id_today, id_null) if i in ids}
        if len(pos) == 3:
            # coalesce desc：无 occurred_at(created_at≈now, 最新) > 今天(1h前) > 180 天前
            ok("③ 相对顺序 无occurred(created_at) > 今天 > 180天前",
               pos[id_null] < pos[id_today] < pos[id_old],
               f"pos null={pos[id_null]} today={pos[id_today]} old={pos[id_old]}")
        else:
            ok("③ 相对顺序 无occurred(created_at) > 今天 > 180天前", False, "缺行")

        # 时间窗过滤（since/until 走 occurred_at，NULL 回退 created_at）
        since = now - timedelta(days=8)
        rows = memory_service.get_memories(db, user_id, since=since)
        ids = [r["id"] for r in rows]
        ok("since=近8天 不含180天前行", id_old not in ids, f"ids={ids[:8]}")
        ok("since=近8天 含无occurred行（回退created_at）", id_null in ids,
           f"ids={ids[:8]}")
    finally:
        _cleanup(db)
        db.close()

    final = _baseline_count()
    print(f"\n基线行数({baseline}) == 收尾行数({final}) : {baseline == final}")
    if baseline != final:
        FAILURES.append("基线行数 == 收尾行数")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
