"""v3.2 附录 §1.2/§2.1 断言边（真库、不调模型）

用例：
  ① 同边重复插入 no-op（uk_memory_edge）
  ② 自环拒绝 / 跨 relation 拒绝
  ③ supersedes 副作用：旧断言 superseded/superseded + 索引失效联动
  ④ contradicts 固定 min→max 且不改状态
  ⑤ 环检测：A→B→C 后 C→A 拒绝；父链深度 >32 拒绝并告警
  ⑥ 身份红线守卫（纯函数分支）

数据隔离：临时行按 id 在 finally 删除（先边后断言），基线计数首尾相等。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_edge_service.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []
_CREATED_IDS = []


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
        e = db.execute(text("SELECT COUNT(*) FROM memory_assertion_edge")).scalar()
        return m, e
    finally:
        db.close()


def _pick_relation(db):
    from app.models.couple_relation import CoupleRelation

    rel = (
        db.query(CoupleRelation)
        .filter(CoupleRelation.status == "active")
        .order_by(CoupleRelation.id.asc())
        .first()
    )
    if rel is None:
        raise RuntimeError("库里没有 active couple_relation")
    return rel


def _insert_mem(db, relation_id: int, user_id: int) -> int:
    from app.models.ai import AiMemory

    row = AiMemory(
        user_id=user_id,
        relation_id=relation_id,
        memory_type="关系事实",
        memory_text="edge test row",
        visibility="couple",
        status="active",
        index_status="indexed",
        index_generation=1,
    )
    db.add(row)
    db.commit()
    _CREATED_IDS.append(row.id)
    return row.id


def _cleanup(db):
    from app.models.ai import AiMemory, MemoryAssertionEdge

    for row_id in list(_CREATED_IDS):
        db.query(MemoryAssertionEdge).filter(
            (MemoryAssertionEdge.parent_assertion_id == row_id)
            | (MemoryAssertionEdge.child_assertion_id == row_id)
        ).delete(synchronize_session=False)
        db.query(AiMemory).filter(AiMemory.id == row_id).delete(
            synchronize_session=False
        )
        db.commit()
    _CREATED_IDS.clear()


def main() -> int:
    print("=" * 72)
    print("附录 §1.2/§2.1 断言边")
    print("=" * 72)
    base_mem, base_edge = _baseline()

    from app.core.database import SessionLocal
    from app.services.memory_edge_service import (
        EdgeRejected,
        assert_no_auto_supersede_of_partner_self_report,
        create_edge,
    )

    db = SessionLocal()
    try:
        rel = _pick_relation(db)

        print("\n[1] 幂等 / 校验")
        a = _insert_mem(db, rel.id, rel.user_a_id)
        b = _insert_mem(db, rel.id, rel.user_a_id)
        edge_id = create_edge(db, relation_id=rel.id, parent_id=a, child_id=b,
                              relation_type="supersedes")
        db.commit()
        ok("首次插边返回 id", edge_id is not None, str(edge_id))
        again = create_edge(db, relation_id=rel.id, parent_id=a, child_id=b,
                            relation_type="supersedes")
        db.commit()
        ok("同边重复 → no-op None", again is None, str(again))
        try:
            create_edge(db, relation_id=rel.id, parent_id=a, child_id=a,
                        relation_type="supersedes")
            ok("自环拒绝", False)
        except EdgeRejected:
            ok("自环拒绝", True)
        try:
            create_edge(db, relation_id=rel.id + 999999, parent_id=a, child_id=b,
                        relation_type="supersedes")
            ok("跨 relation 拒绝", False)
        except EdgeRejected:
            ok("跨 relation 拒绝", True)

        print("\n[2] supersedes 副作用（同事务 + 索引联动）")
        from app.models.ai import AiMemory

        row_a = db.query(AiMemory).filter(AiMemory.id == a).first()
        ok("旧断言 superseded/superseded",
           row_a.status == "superseded" and row_a.status_reason == "superseded",
           f"{row_a.status}/{row_a.status_reason}")
        ok("索引联动 generation+1 → pending_remove",
           row_a.index_generation == 2 and row_a.index_status == "pending_remove",
           f"{row_a.index_generation}/{row_a.index_status}")

        print("\n[3] contradicts 固定 min→max、不改状态")
        c = _insert_mem(db, rel.id, rel.user_a_id)  # id 递增，c 最大
        edge2 = create_edge(db, relation_id=rel.id, parent_id=c, child_id=b,
                            relation_type="contradicts")
        db.commit()
        ok("返回边 id", edge2 is not None)
        from app.models.ai import MemoryAssertionEdge

        e2 = db.query(MemoryAssertionEdge).filter(MemoryAssertionEdge.id == edge2).first()
        ok("parent=min, child=max",
           e2.parent_assertion_id == min(b, c) and e2.child_assertion_id == max(b, c),
           f"{e2.parent_assertion_id}->{e2.child_assertion_id}")
        row_c = db.query(AiMemory).filter(AiMemory.id == c).first()
        ok("contradicts 不改状态", row_c.status == "active", row_c.status)

        print("\n[4] 环检测与深度上限")
        # d1 -> d2 -> d3 链，再加 d3 -> d1 必须成环
        d1 = _insert_mem(db, rel.id, rel.user_a_id)
        d2 = _insert_mem(db, rel.id, rel.user_a_id)
        d3 = _insert_mem(db, rel.id, rel.user_a_id)
        create_edge(db, relation_id=rel.id, parent_id=d1, child_id=d2,
                    relation_type="supersedes")
        create_edge(db, relation_id=rel.id, parent_id=d2, child_id=d3,
                    relation_type="supersedes")
        db.commit()
        try:
            create_edge(db, relation_id=rel.id, parent_id=d3, child_id=d1,
                        relation_type="supersedes")
            ok("成环拒绝（d3→d1）", False)
        except EdgeRejected:
            ok("成环拒绝（d3→d1）", True)
        # 深度 >32：造 34 节点链（33 条边），再挂新节点必须超限拒绝
        chain = [_insert_mem(db, rel.id, rel.user_a_id) for _ in range(34)]
        for i in range(33):
            create_edge(db, relation_id=rel.id, parent_id=chain[i],
                        child_id=chain[i + 1], relation_type="supersedes")
        db.commit()
        tail = _insert_mem(db, rel.id, rel.user_a_id)
        try:
            create_edge(db, relation_id=rel.id, parent_id=chain[33], child_id=tail,
                        relation_type="supersedes")
            ok("父链深度 >32 拒绝", False)
        except EdgeRejected:
            ok("父链深度 >32 拒绝", True)
        # 边界：第 33 条边（深度 32）创建时应成功（前面 chain 循环已验证）

        print("\n[5] 身份红线守卫（§2.1 纯函数）")
        try:
            assert_no_auto_supersede_of_partner_self_report(
                new_epistemic="observation", parent_epistemic="self_report")
            ok("observation 不得 supersede self_report", False)
        except EdgeRejected:
            ok("observation 不得 supersede self_report", True)
        try:
            assert_no_auto_supersede_of_partner_self_report(
                new_epistemic="attributed_report", parent_epistemic="self_report")
            ok("attributed_report 不得 supersede self_report", False)
        except EdgeRejected:
            ok("attributed_report 不得 supersede self_report", True)
        try:
            assert_no_auto_supersede_of_partner_self_report(
                new_epistemic="self_report", parent_epistemic="self_report")
            ok("self_report→self_report（自己更新自己）放行", True)
        except EdgeRejected:
            ok("self_report→self_report（自己更新自己）放行", False)
    finally:
        _cleanup(db)
        db.close()

    end_mem, end_edge = _baseline()
    ok("基线行数收尾相等（ai_memory）", base_mem == end_mem, f"{base_mem} vs {end_mem}")
    ok("基线边数收尾相等", base_edge == end_edge, f"{base_edge} vs {end_edge}")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
