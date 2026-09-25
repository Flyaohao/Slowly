"""v3.2 附录 §1.4/§6.1 用户级状态（真库、不调模型）

用例：
  ① 成员 upsert（新建行、部分字段更新保持原值）
  ② 缺行 ≡ 全 false；get_user_state 语义
  ③ 非成员写入被拒（UserStateRejected）
  ④ 单方 mute 不影响另一方（viewer_state_filter_clause 按查看者生效）
  ⑤ clause 空表/未设置 = 无操作（断言仍可见）

数据隔离：finally 删 user_state 行 + 断言行，基线计数首尾相等。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_user_state.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []
_CREATED_MEM_IDS = []


def ok(name: str, passed: bool, detail: str = ""):
    mark = "PASS" if passed else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


def _baseline() -> tuple:
    from sqlalchemy import text
    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        m = db.execute(text("SELECT COUNT(*) FROM ai_memory")).scalar()
        s = db.execute(
            text("SELECT COUNT(*) FROM memory_assertion_user_state")
        ).scalar()
        return m, s
    finally:
        db.close()


def _cleanup(db):
    from app.models.ai import AiMemory, MemoryAssertionUserState

    for row_id in list(_CREATED_MEM_IDS):
        db.query(MemoryAssertionUserState).filter(
            MemoryAssertionUserState.assertion_id == row_id
        ).delete(synchronize_session=False)
        db.query(AiMemory).filter(AiMemory.id == row_id).delete(
            synchronize_session=False
        )
        db.commit()
    _CREATED_MEM_IDS.clear()


def _visible(db, assertion_id: int, viewer: int) -> bool:
    """viewer 维度过滤后该断言是否仍可见。"""
    from sqlalchemy import select
    from app.models.ai import AiMemory
    from app.services.memory_user_state_service import viewer_state_filter_clause

    stmt = (
        select(AiMemory.id)
        .where(AiMemory.id == assertion_id)
        .where(viewer_state_filter_clause(viewer))
    )
    return db.execute(stmt).first() is not None


def main() -> int:
    print("=" * 72)
    print("附录 §1.4/§6.1 用户级状态")
    print("=" * 72)
    base_mem, base_state = _baseline()

    from sqlalchemy import text

    from app.core.database import SessionLocal
    from app.models.ai import AiMemory
    from app.models.couple_relation import CoupleRelation
    from app.services.memory_user_state_service import (
        UserStateRejected,
        get_user_state,
        set_user_state,
    )

    db = SessionLocal()
    try:
        rel = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active")
            .order_by(CoupleRelation.id.asc())
            .first()
        )
        if rel is None:
            raise RuntimeError("库里没有 active couple_relation")
        a, b = rel.user_a_id, rel.user_b_id

        mem = AiMemory(
            user_id=a,
            relation_id=rel.id,
            memory_type="关系事实",
            memory_text="user state test row",
            visibility="couple",
            status="active",
        )
        db.add(mem)
        db.commit()
        _CREATED_MEM_IDS.append(mem.id)

        print("\n[1] 成员 upsert")
        default_state = get_user_state(db, assertion_id=mem.id, user_id=a)
        ok("缺行 ≡ 全 false",
           default_state == {"hidden": False, "muted": False, "starred": False},
           str(default_state))
        set_user_state(db, assertion_id=mem.id, user_id=a, hidden=True, commit=True)
        st = get_user_state(db, assertion_id=mem.id, user_id=a)
        ok("hidden=True 落库", st["hidden"] is True, str(st))
        set_user_state(db, assertion_id=mem.id, user_id=a, starred=True, commit=True)
        st = get_user_state(db, assertion_id=mem.id, user_id=a)
        ok("部分更新：starred=True 且 hidden 保持",
           st["starred"] is True and st["hidden"] is True, str(st))

        print("\n[2] 单方状态互不影响（红线6）")
        set_user_state(db, assertion_id=mem.id, user_id=a, muted=True, commit=True)
        ok("a 隐藏后 a 不可见", _visible(db, mem.id, a) is False)
        ok("b 不受影响仍可见", _visible(db, mem.id, b) is True)
        ok("b 可见（b 自己无状态行）",
           get_user_state(db, assertion_id=mem.id, user_id=b)
           == {"hidden": False, "muted": False, "starred": False})
        # b 对同一断言的操作独立落行
        set_user_state(db, assertion_id=mem.id, user_id=b, starred=True, commit=True)
        ok("b 的 starred 独立成行（b 仍可见）",
           _visible(db, mem.id, b) is True
           and get_user_state(db, assertion_id=mem.id, user_id=b)["starred"] is True)
        ok("a 仍被自己 mute 挡住", _visible(db, mem.id, a) is False)

        print("\n[3] 非成员拒绝")
        outsider = db.execute(
            text("SELECT id FROM user WHERE id != :a AND id != :b LIMIT 1"),
            {"a": a, "b": b},
        ).scalar()
        if outsider is None:
            ok("非成员写入被拒（库中无第三用户，跳过）", True)
        else:
            try:
                set_user_state(db, assertion_id=mem.id, user_id=int(outsider),
                               hidden=True, commit=True)
                ok("非成员写入被拒", False)
            except UserStateRejected:
                ok("非成员写入被拒", True)
        try:
            set_user_state(db, assertion_id=999999999, user_id=a, hidden=True)
            ok("不存在的断言被拒", False)
        except UserStateRejected:
            ok("不存在的断言被拒", True)
    finally:
        _cleanup(db)
        db.close()

    end_mem, end_state = _baseline()
    ok("基线行数收尾相等（ai_memory）", base_mem == end_mem, f"{base_mem} vs {end_mem}")
    ok("基线行数收尾相等（user_state）",
       base_state == end_state, f"{base_state} vs {end_state}")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
