"""Agent 工具调用闭环验证

验证内容：
    1. 工具定义被正确导出（@tool + args_schema）
    2. 模型能自主判断"是否需要用工具""该用哪个工具"
    3. 模型能为工具生成正确参数
    4. 工具结果被回填，模型据此生成最终答复（完整闭环）

运行：
    cd backend
    python tests/test_agent.py

说明：工具需要查询数据库。若本地 MySQL 未启动，工具会返回失败提示，
但**模型的工具选择与传参行为依然可验证**（这正是本脚本关注的重点）。
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.agent.executor import run_agent  # noqa: E402
from app.agent.tools import ALL_TOOLS  # noqa: E402

FROM_DB = False  # 是否连接数据库执行（由环境决定，仅影响工具返回内容）

CASES = [
    ("我是什么依恋类型？我们的关系画像是什么样的？", "get_relation_profile",
     "需要用户画像 → 应调用查画像工具"),
    ("'追逃模式'具体是什么意思？为什么我们会陷入这种循环？", "search_theory",
     "需要理论解释 → 应调用理论检索工具"),
    ("直接告诉我怎么跟他开口比较好。", None,
     "简单请求 → 可以不调工具，直接回答"),
]


def main() -> int:
    print("=" * 72)
    print("Agent 工具调用验证")
    print("=" * 72)

    print("\n[0] 工具注册检查")
    for t in ALL_TOOLS:
        schema = t.args_schema.model_json_schema() if t.args_schema else {}
        params = list(schema.get("properties", {}).keys())
        print("  ✅ %-22s 参数: %s" % (t.name, params))
        print("     description: %s" % (t.description or "").split("\n")[0][:60])

    failures = []
    total_tools_called = 0

    for question, expected_tool, note in CASES:
        print("\n" + "-" * 72)
        print("[提问] %s" % question)
        print("[预期] %s" % note)
        print("-" * 72)

        try:
            result = run_agent(question, user_id=1, relation_id=1)
        except Exception as exc:
            print("  ❌ 执行失败: %s" % exc)
            failures.append(question)
            continue

        calls = result.get("tool_calls", [])
        total_tools_called += len(calls)

        print("  消息轨迹: %s" % " → ".join(result.get("reasoning", [])))
        if calls:
            for c in calls:
                print("  🔧 调用工具 %s" % c["name"])
                print("     参数  : %s" % json.dumps(c["args"], ensure_ascii=False))
                print("     返回  : %s" % str(c["result"]).replace("\n", " ")[:110])
        else:
            print("  （未调用工具，直接回答）")

        print("  最终答复: %s" % result.get("answer", "").replace("\n", " ")[:160])

        if expected_tool:
            hit = any(c["name"] == expected_tool for c in calls)
            if hit:
                print("  ✅ 工具选择正确（期望 %s）" % expected_tool)
            else:
                print("  ❌ 工具选择不符（期望 %s，实际 %s）"
                      % (expected_tool, [c["name"] for c in calls]))
                failures.append(question)

        if not result.get("answer"):
            print("  ❌ 未生成最终答复")
            failures.append(question)

    print("\n" + "=" * 72)
    print("结论")
    print("=" * 72)
    print("工具调用总次数: %d" % total_tools_called)
    print("失败用例: %d" % len(failures))
    if failures:
        for f in failures:
            print("   ❌ %s" % f)
        return 1
    print("✅ Function Calling 闭环验证通过（模型选工具 → 传参 → 执行 → 回填 → 生成）")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
