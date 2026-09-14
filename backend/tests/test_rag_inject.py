"""RAG 注入效果验证：有无理论库上下文，AI 回答有何不同

方法：
    同一个问题、同一对画像，跑两次——
      A 组：注入向量检索到的理论片段（Gottman / 依恋 / 追逃模式等）
      B 组：不注入任何理论上下文
    对比回答是否引用了具体理论、建议是否有依据。

这是「RAG 真的生效」的最终证据，也是面试可展示的材料。

运行：
    cd backend
    python tests/test_rag_inject.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal  # noqa: E402
from app.services.ai_service import _call_llm  # noqa: E402
from app.services.prompt_builder import build_prompt  # noqa: E402
from app.services.rag_service import build_rag_context, retrieve_chunks  # noqa: E402

SCENE = "private_advisor"
QUESTION = "他经常说'你想多了'，我一听到这句话就特别生气，然后就吵起来。为什么会这样？"

USER_PROFILE = (
    "依恋类型: 焦虑依恋型, 置信度: 0.85, "
    "维度分数: [attachment_anxiety=80, conflict_pursue=76, emotional_validation_need=88]"
)
PARTNER_PROFILE = (
    "依恋类型: 疏离回避型, 置信度: 0.78, "
    "维度分数: [attachment_avoidance=79, conflict_withdraw=77]"
)
PATTERN = "追逃模式（一方追问、一方回避）"

#: 用于判断回答是否真正引用了理论
THEORY_MARKERS = [
    "Gottman", "四骑士", "依恋", "追逃", "Demand", "退缩",
    "非暴力", "NVC", "I-Statement", "理论", "研究表明",
]


def run(label: str, rag_context: str) -> str:
    prompt = build_prompt(
        scene_key=SCENE,
        user_profile=USER_PROFILE,
        partner_profile=PARTNER_PROFILE,
        conflict_pattern=PATTERN,
        user_input=QUESTION,
        history="",
        rag_context=rag_context,
        memory_context="",
    )
    print("\n" + "=" * 72)
    print("[%s] Prompt 长度: %d 字符（其中理论上下文 %d 字符）"
          % (label, len(prompt), len(rag_context)))
    print("=" * 72)
    result = _call_llm(prompt, SCENE)
    text = result.get("raw_text", "")
    print(text)
    return text


def main() -> int:
    db = SessionLocal()
    try:
        chunks = retrieve_chunks(db, QUESTION, top_k=3)
    finally:
        db.close()

    print("=" * 72)
    print("检索命中（向量检索）")
    print("=" * 72)
    for i, chunk in enumerate(chunks, 1):
        print("  %d) [%s] %s" % (i, chunk.get("score"), chunk.get("doc_title")))

    rag_context = build_rag_context(chunks)

    text_with = run("A 组 · 注入理论库", rag_context)
    text_without = run("B 组 · 无理论库", "")

    print("\n" + "=" * 72)
    print("对比结论")
    print("=" * 72)
    hit_with = [m for m in THEORY_MARKERS if m in text_with]
    hit_without = [m for m in THEORY_MARKERS if m in text_without]
    print("A 组引用理论标记: %s" % ("、".join(hit_with) if hit_with else "无"))
    print("B 组引用理论标记: %s" % ("、".join(hit_without) if hit_without else "无"))
    print("A 组长度: %d / B 组长度: %d" % (len(text_with), len(text_without)))
    print("两次输出是否不同: %s" % ("是 ✅" if text_with != text_without else "否 ❌"))
    print("=" * 72)

    return 0 if len(hit_with) >= len(hit_without) and text_with != text_without else 1


if __name__ == "__main__":
    raise SystemExit(main())
