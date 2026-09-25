"""
P-A §1.4 验收：关系复盘链路不再 NameError（A2）。

断言：
  1. hasattr(ai_service, "build_prompt") 为真（源码级钉住 🔴A）
  2. 真调 prepare_relationship_review(...) 不抛 NameError，
     返回 dict 含 stream_generation_events 所需键（逐键打印）
  3. prompt 文本含 MARKDOWN_OUTPUT_RULES 三条约束（跨验证 §1.2 生效）

不真调大模型（不消费 SSE）。generation begin() 会写 ai_generation 一行，
finally 按 generation_id 显式删除；报基线==收尾行数。

运行：cd backend && python tests/test_review_stream.py
"""
import os
import sys

# 测试隔离（与其它真实链路脚本一致）
os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def _generation_count() -> int:
    from app.core.database import SessionLocal
    from sqlalchemy import text

    db = SessionLocal()
    try:
        return db.execute(text("SELECT COUNT(*) FROM ai_generation")).scalar()
    finally:
        db.close()


def main() -> int:
    print("=" * 72)
    print("P-A 验收：关系复盘 prepare 链路（A1/A2/§1.2 交叉）")
    print("=" * 72)

    n0 = _generation_count()
    print(f"基线 ai_generation 行数 = {n0}")
    gen_id = None

    # ---- 1. 源码级钉住 build_prompt 已 import ----
    print("\n[1] hasattr(ai_service, 'build_prompt')")
    from app.services import ai_service

    check("build_prompt 已在 ai_service 命名空间", hasattr(ai_service, "build_prompt"))

    # ---- 2. 真调 prepare_relationship_review ----
    print("\n[2] 真调 prepare_relationship_review（不调模型）")
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation

    db = SessionLocal()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        if rel is None:
            check("存在 active relation", False, "无 active 关系且本轮不自建（已有测试数据）")
            return 1
        uid, rid = rel.user_a_id, rel.id
        desc = "他今天一整天都没回我消息，晚上说在忙"

        try:
            prepared = ai_service.prepare_relationship_review(db, uid, rid, desc)
            check("不抛 NameError / 其它异常", True)
        except Exception as exc:
            check("不抛 NameError / 其它异常", False, repr(exc))
            raise

        gen_id = prepared.get("generation_id")
        # 逐键打印（回归对比用）
        required = [
            "generation_id", "cancel_event", "generation_kind", "scene_key",
            "user_id", "relation_id", "prompt", "output_model",
        ]
        print("  Prepared keys:", sorted(prepared.keys()))
        for k in required:
            check(f"含必备键 {k}", k in prepared)
        check("scene_key=relationship_review",
              prepared.get("scene_key") == "relationship_review",
              str(prepared.get("scene_key")))
        check("generation_kind=relationship_review",
              prepared.get("generation_kind") == "relationship_review")
        check("prompt 非空字符串",
              isinstance(prepared.get("prompt"), str) and len(prepared.get("prompt", "")) > 100,
              str(len(prepared.get("prompt") or "")))

        # ---- 3. §1.2 Markdown 三条约束进 prompt ----
        print("\n[3] prompt 含 MARKDOWN_OUTPUT_RULES")
        prompt = prepared.get("prompt") or ""
        check("含四级标题约束", "`#### `" in prompt or "四级标题" in prompt)
        check("含列表约束", "列举一律用 `- `" in prompt or "一律用 `- `" in prompt)
        check("含加粗约束", "禁止整段整句加粗" in prompt)
        check("无 # 一级标题示范（约束本身禁止 # 开头标题）",
              "禁止使用 `#` / `##` / `###`" in prompt)
    finally:
        # begin() 写入的 ai_generation 行：按 id 显式删除
        if gen_id is not None:
            from sqlalchemy import text
            try:
                db.execute(text("DELETE FROM ai_generation WHERE id = :i"), {"i": gen_id})
                db.commit()
                print(f"  已清理 ai_generation id={gen_id}")
            except Exception as exc:
                db.rollback()
                print(f"  清理 generation 失败: {exc}")
        db.close()

    n1 = _generation_count()
    print(f"收尾 ai_generation 行数 = {n1}")
    check("行数与基线一致（无残留）", n0 == n1, f"{n0} vs {n1}")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
