"""画像驱动 Prompt 的 A/B 对比验证

目的：
    证明「注入用户关系画像」确实能改变 AI 建议的针对性，
    而不是让模型给出一段放之四海而皆准的泛化建议。

方法：
    同一个问题、同一个场景，跑两次——
      A 组：注入完整画像（依恋类型 / 11 维分数 / 冲突循环）
      B 组：不注入画像（提示"未完成问卷"）
    对比两次输出的差异。

运行：
    cd backend
    python tests/test_profile_ab.py
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from live_llm_guard import live_llm_enabled, skip_reason  # noqa: E402

from app.services.ai_service import _call_llm  # noqa: E402
from app.services.prompt_builder import build_prompt  # noqa: E402

SCENE = "private_advisor"
QUESTION = "我每次想跟他讨论问题，他就说要冷静一下然后走开，我觉得他根本不在乎我。我该怎么办？"

# A 组：完整画像
PROFILE_A_USER = (
    "依恋类型: 焦虑依恋型, 置信度: 0.86, "
    "维度分数: [attachment_anxiety=82, conflict_pursue=79, "
    "emotional_validation_need=88, personal_space_need=21]"
)
PROFILE_A_PARTNER = (
    "依恋类型: 疏离回避型, 置信度: 0.79, "
    "维度分数: [attachment_avoidance=84, conflict_withdraw=81, "
    "personal_space_need=74, emotional_validation_need=35]"
)
PATTERN_A = "追逃模式（焦虑方追问 → 回避方撤退 → 焦虑方更焦虑）"

# B 组：无画像
PROFILE_B = "未完成问卷"
PATTERN_B = "未确定"


def run(label: str, user_profile: str, partner_profile: str, pattern: str) -> dict:
    prompt = build_prompt(
        scene_key=SCENE,
        user_profile=user_profile,
        partner_profile=partner_profile,
        conflict_pattern=pattern,
        user_input=QUESTION,
        history="",
        rag_context="",
        memory_context="",
    )
    print("\n" + "=" * 72)
    print("[%s] Prompt 长度: %d 字符" % (label, len(prompt)))
    print("=" * 72)
    result = _call_llm(prompt, SCENE)
    print(result.get("raw_text", ""))
    return result


def main() -> int:
    # 2026-10-02：本套件**真调 LLM**（烧额度，且额度耗尽会伪装成"测试失败"）。
    # 默认拦下；显式要跑：LIVE_LLM_PROFILE_AB=1 或 LIVE_LLM=1
    if not live_llm_enabled("profile_ab"):
        print(skip_reason("profile_ab"))
        return 0

    print("问题：%s" % QUESTION)

    a = run("A 组 · 注入画像", PROFILE_A_USER, PROFILE_A_PARTNER, PATTERN_A)
    b = run("B 组 · 无画像", PROFILE_B, PROFILE_B, PATTERN_B)

    text_a = a.get("raw_text", "")
    text_b = b.get("raw_text", "")

    print("\n" + "=" * 72)
    print("对比结论")
    print("=" * 72)
    print("A 组长度: %d 字符" % len(text_a))
    print("B 组长度: %d 字符" % len(text_b))

    # 关键差异点：A 组是否真的用上了画像里的信息
    profile_keywords = ["焦虑", "回避", "追逃", "追问", "空间", "依恋"]
    hit_a = [kw for kw in profile_keywords if kw in text_a]
    hit_b = [kw for kw in profile_keywords if kw in text_b]

    print("A 组命中画像相关关键词: %s" % ("、".join(hit_a) if hit_a else "无"))
    print("B 组命中画像相关关键词: %s" % ("、".join(hit_b) if hit_b else "无"))
    print("两次输出是否不同: %s" % ("是 ✅" if text_a != text_b else "否 ❌"))
    print("=" * 72)

    return 0 if text_a != text_b else 1


if __name__ == "__main__":
    raise SystemExit(main())
