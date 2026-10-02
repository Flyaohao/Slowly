"""验证 AI 核心链路是否真正打通

验证内容：
    1. Prompt 组装（含画像注入）
    2. 真实大模型调用（非占位数据）
    3. Function Calling 结构化输出 + Pydantic 校验
    4. raw_text 人类可读文本生成

运行：
    cd backend
    python tests/test_ai_live.py
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from live_llm_guard import live_llm_enabled, skip_reason  # noqa: E402
from app.services.ai_service import _call_llm  # noqa: E402
from app.services.prompt_builder import build_prompt  # noqa: E402

# 一对典型的「焦虑型 × 回避型」伴侣画像
USER_PROFILE = (
    "依恋类型: 焦虑依恋型, 置信度: 0.82, "
    "维度分数: [attachment_anxiety=78, attachment_avoidance=32, "
    "conflict_pursue=71, emotional_validation_need=85]"
)
PARTNER_PROFILE = (
    "依恋类型: 疏离回避型, 置信度: 0.75, "
    "维度分数: [attachment_anxiety=25, attachment_avoidance=81, "
    "conflict_withdraw=76, personal_space_need=68]"
)
CONFLICT_PATTERN = "追逃模式（一方追问、一方回避）"

CASES = [
    ("partner_translate", "他刚发消息说：'你随便吧，我都行。' 这是什么意思？"),
    ("cold_war", "我们已经三天没说话了。我想打破僵局，但怕一开口又吵起来。"),
    ("expression_rewrite", "我想跟他说：'你能不能别总是玩手机？'"),
    ("letter_understand", "他写给我：'最近有点累，想自己待一会儿，你别多想。'"),
]

PLACEHOLDER_MARKERS = ("占位", "测试回复", "需接入真实 LLM API")


def main() -> int:
    # 2026-10-02：本套件**真调 LLM**（烧额度，且额度耗尽会伪装成"测试失败"）。
    # 默认拦下；显式要跑：LIVE_LLM_AI_LIVE=1 或 LIVE_LLM=1
    if not live_llm_enabled("ai_live"):
        print(skip_reason("ai_live"))
        return 0

    print("=" * 72)
    print("AI 核心链路验证")
    print("=" * 72)

    failures = []

    for scene_key, user_input in CASES:
        print("\n" + "-" * 72)
        print("[场景] %s" % scene_key)
        print("[输入] %s" % user_input)
        print("-" * 72)

        prompt = build_prompt(
            scene_key=scene_key,
            user_profile=USER_PROFILE,
            partner_profile=PARTNER_PROFILE,
            conflict_pattern=CONFLICT_PATTERN,
            user_input=user_input,
            history="",
            rag_context="",
            memory_context="",
        )
        print("[Prompt 长度] %d 字符" % len(prompt))

        result = _call_llm(prompt, scene_key)

        raw_text = result.get("raw_text", "")
        if any(marker in raw_text for marker in PLACEHOLDER_MARKERS):
            failures.append(scene_key)
            print("[结果] ❌ 仍是占位数据")
            continue

        print("[raw_text]")
        print(raw_text)

        print("\n[结构化字段]")
        print(json.dumps(result, ensure_ascii=False, indent=2))

        if result.get("risk_level"):
            print("[风险等级] %s" % result["risk_level"])

    print("\n" + "=" * 72)
    if failures:
        print("❌ 失败场景: %s" % ", ".join(failures))
        return 1
    print("✅ 全部 %d 个场景均返回真实 AI 内容" % len(CASES))
    print("=" * 72)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
