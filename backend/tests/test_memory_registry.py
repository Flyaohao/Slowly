"""v3.2 §2 谓词注册表与 fact_key（纯函数，hermetic）

断言：
  1. PREDICATES 封闭九值；
  2. normalize_object_key：词典命中 / 同义归一 / 规范点路径直采 /
     未命中落 ("other", True) 不阻塞；
  3. build_fact_key 格式 `subject_type:id / predicate / object_key`；
  4. cardinality_for：single/multi 分布，未知谓词按 other(multi)。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_registry.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def main() -> int:
    print("=" * 72)
    print("v3.2 §2 谓词注册表 / fact_key")
    print("=" * 72)

    from app.services.memory_registry import (
        CARDINALITY_BY_PREDICATE,
        PREDICATES,
        build_fact_key,
        cardinality_for,
        normalize_object_key,
    )

    print("\n[1] 封闭谓词 enum")
    expected = ("preference", "behavior", "goal", "constraint",
                "trait", "event", "pattern", "state", "other")
    check("PREDICATES = 九值", PREDICATES == expected, str(PREDICATES))
    check("cardinality 覆盖全部谓词",
          set(CARDINALITY_BY_PREDICATE) == set(PREDICATES),
          str(set(PREDICATES) - set(CARDINALITY_BY_PREDICATE)))

    print("\n[2] normalize_object_key")
    key, miss = normalize_object_key("辣")
    check("词典命中：辣 → food.spicy", (key, miss) == ("food.spicy", False), f"{key},{miss}")
    key, miss = normalize_object_key("吃不了辣")  # 同义归一
    check("同义归一：吃不了辣 → food.spicy", (key, miss) == ("food.spicy", False), f"{key},{miss}")
    key, miss = normalize_object_key("Food.Salty")
    check("规范点路径直采（小写化）", (key, miss) == ("food.salty", False), f"{key},{miss}")
    key, miss = normalize_object_key("量子色动力学")
    check("未命中 → (other, True) 不阻塞", (key, miss) == ("other", True), f"{key},{miss}")
    key, miss = normalize_object_key(None)
    check("空输入 → (other, True)", (key, miss) == ("other", True), f"{key},{miss}")

    print("\n[3] build_fact_key 格式（§2.1）")
    fk = build_fact_key("user", 12, "preference", "food.spicy")
    check("user 主体：user:12 / preference / food.spicy",
          fk == "user:12 / preference / food.spicy", fk)
    fk = build_fact_key("relationship", None, "behavior", "comm.silent_treatment")
    check("关系主体省略 id：relationship / behavior / comm.silent_treatment",
          fk == "relationship / behavior / comm.silent_treatment", fk)
    fk = build_fact_key(None, None, None, None)
    check("缺省归位：relationship / other / other", fk == "relationship / other / other", fk)

    print("\n[4] cardinality")
    check("preference=single（同 key 覆盖走 supersedes）",
          cardinality_for("preference") == "single")
    check("event=multi（同 key 并存）", cardinality_for("event") == "multi")
    check("未知谓词按 other → multi", cardinality_for("nonsense") == "multi")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
