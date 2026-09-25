"""P-C2 §1.1 验收：「冷暴力」白名单前置，不得判为 abuse_risk。

背景（实测 4/30 的输出侧误报）：
  abuse_risk.strong 含泛化词「暴力」→ 「冷暴力」被子串命中 →
  check_output_safety 判 abuse_risk → 客户端盖风险卡
  「我没办法帮你生成这类内容」，压在正常回答上。

修复口径（prompt §1.1 明文）：**只做最小改动**——词表加白名单前置，
strong 扫描前遮蔽白名单短语；不动判定逻辑、不动阈值、不删词。
「冷暴力」本体仍在 heated_conflict.weak（共现 >=2 才触发），语义不丢。

零外部依赖：不连库、不调模型。

运行：cd backend && python tests/test_safety_whitelist.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from app.services.safety_service import (  # noqa: E402
    check_input_safety,
    check_output_safety,
)

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def main() -> int:
    print("=" * 72)
    print("P-C2 §1.1 安全词白名单（冷暴力 → 不判 abuse_risk）")
    print("=" * 72)

    print("\n[1] 「冷暴力」单独出现：不得判 abuse_risk（输入侧 + 输出侧）")
    cold = "他总是对我冷暴力，一吵架就不回消息、不理我，我真的很累"
    check("输入侧 != abuse_risk", check_input_safety(cold) != "abuse_risk",
          check_input_safety(cold))
    check("输出侧 != abuse_risk", check_output_safety(cold) != "abuse_risk",
          check_output_safety(cold))

    print("\n[2] 解释性语境：提到「冷暴力」不因子串「暴力」误判")
    # 注：句中刻意避开裸词「暴力」「家暴」——白名单只遮蔽「冷暴力」这个
    # 完整短语，裸词照旧判（这正是「不删词」的口径）。
    explain = "冷暴力只是冲突描述，把它当成风险词一律拦截会误伤正常回答"
    check("输入侧 != abuse_risk", check_input_safety(explain) != "abuse_risk",
          check_input_safety(explain))
    check("输出侧 != abuse_risk", check_output_safety(explain) != "abuse_risk",
          check_output_safety(explain))

    print("\n[3] 回归护栏：真家暴表达仍判 abuse_risk（白名单不放水）")
    real = "他上次动手打我，还掐我脖子，这是家暴"
    check("输入侧 == abuse_risk", check_input_safety(real) == "abuse_risk",
          check_input_safety(real))
    check("输出侧 == abuse_risk", check_output_safety(real) == "abuse_risk",
          check_output_safety(real))

    print("\n[4] 回归护栏：「冷暴力」仍在 weak 词表（共现语义保留）")
    from app.services import safety_words

    check("WHITELIST 含冷暴力", "冷暴力" in safety_words.WHITELIST,
          str(safety_words.WHITELIST))
    check("heated_conflict.weak 仍含冷暴力",
          "冷暴力" in safety_words.WORDS["heated_conflict"]["weak"])
    check("abuse_risk.strong 仍含暴力（词不删）",
          "暴力" in safety_words.WORDS["abuse_risk"]["strong"])
    coexist = "他每次都冷暴力我，你总是这样不理我，我受不了了"
    check("共现>=2 仍判 heated_conflict", check_input_safety(coexist) == "heated_conflict",
          check_input_safety(coexist))

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
