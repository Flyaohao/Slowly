"""
P0-7 验收：voice_style 真正生效（军师人格注入）。

断言：
  a) voice_style 5 个取值 → 注入的 system 分别包含对应语气指令
  b) 未知值（含库里历史脏数据）→ 回退 gentle
  c) 无 avatar（name/voice_style 均 None）→ 默认「翻译官」+ gentle
  d) 主链路接线：_preprocess 读 avatar 并把 persona 追加到两条出口
  e) 人格指令 ≤60 字（token 预算）

运行：cd backend && python tests/test_avatar_persona.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.prompt_builder import (  # noqa: E402
    DEFAULT_AVATAR_NAME,
    VOICE_STYLE_INSTRUCTIONS,
    build_persona_instruction,
)

BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


EXPECTED_KEYWORDS = {
    "gentle": "我理解",
    "calm": "先分析再建议",
    "direct": "不绕弯",
    "cute": "轻快",
    "mature": "长辈",
}


def case_five_styles():
    print("\n[1] voice_style 5 个取值 → 对应语气指令")
    for style, keyword in EXPECTED_KEYWORDS.items():
        text = build_persona_instruction("小翻译", style)
        check(f"{style} 含「{keyword}」", keyword in text, text)
        check(f"{style} 含名字", "小翻译" in text, text)
        check(
            f"{style} 指令来自 VOICE_STYLE_INSTRUCTIONS",
            VOICE_STYLE_INSTRUCTIONS[style] in text,
            text,
        )


def case_unknown_fallback():
    print("\n[2] 未知值回退 gentle")
    for bad in ("banana", "", None, "温柔", "测试内容", "GENTLE"):
        text = build_persona_instruction("小翻译", bad)
        check(
            f"voice_style={bad!r} → gentle 指令",
            EXPECTED_KEYWORDS["gentle"] in text
            and EXPECTED_KEYWORDS["direct"] not in text,
            text,
        )


def case_no_avatar_default():
    print("\n[3] 无 avatar → 默认人格")
    text = build_persona_instruction(None, None)
    check(f"默认名「{DEFAULT_AVATAR_NAME}」", DEFAULT_AVATAR_NAME in text, text)
    check("默认语气 gentle", EXPECTED_KEYWORDS["gentle"] in text, text)
    # 空白名也走默认
    text2 = build_persona_instruction("   ", "  ")
    check("空白名同样回默认", DEFAULT_AVATAR_NAME in text2, text2)


def case_length_budget():
    print("\n[4] 人格指令 ≤60 字")
    for style in EXPECTED_KEYWORDS:
        text = build_persona_instruction("超长的名字超长的名字超长的名字", style)
        # 手册要求 60 字以内；名字已截到 12 字
        check(f"{style} 长度 {len(text)} ≤60", len(text) <= 60, text)


def case_wired_into_main_chain():
    print("\n[5] 主链路接线（源码级）")
    path = os.path.join(BACKEND_DIR, "app", "services", "ai_service.py")
    with open(path, encoding="utf-8") as f:
        src = f.read()
    check(
        "_preprocess 调 build_persona_instruction",
        "build_persona_instruction(" in src,
    )
    check(
        "读 avatar_repo.get_avatar_by_relation_id",
        "get_avatar_by_relation_id" in src,
    )
    check("两条出口都 _append_persona", src.count("_append_persona(") >= 3)  # 定义 1 + 调用 2
    check("import avatar_repo", "avatar_repo" in src)


def case_append_persona_pure():
    print("\n[6] _append_persona 只改 system、返回新列表")
    from app.services.ai_service import _append_persona

    original = [
        {"role": "system", "content": "BASE"},
        {"role": "user", "content": "HI"},
    ]
    out = _append_persona(original, "PERSONA")
    check("system 追加人格", out[0]["content"] == "BASE\n\nPERSONA", out[0])
    check("user 不动", out[1]["content"] == "HI")
    check("原列表未被改（不可变）", original[0]["content"] == "BASE")
    check("persona 为空 → 原样返回", _append_persona(original, "") is original)


def case_two_outlets_share_persona():
    print("\n[7] 结构化与流式共用同一段人格")
    from app.services.ai_service import _append_persona
    from app.services.lc_prompt_builder import build_chat_messages

    persona = build_persona_instruction("小翻译", "direct")
    kwargs = dict(
        scene_key="private_advisor",
        user_profile="【画像】X",
        partner_profile="",
        conflict_pattern="未确定",
        user_input="test",
        history="",
        rag_context="",
        memory_context="",
    )
    structured = _append_persona(
        build_chat_messages(**kwargs, mode="structured"), persona
    )
    stream = _append_persona(build_chat_messages(**kwargs, mode="stream"), persona)
    check(
        "structured system 含人格",
        persona in structured[0]["content"],
        structured[0]["content"][-80:],
    )
    check("stream system 含人格", persona in stream[0]["content"])
    check("两出口人格段一致", structured[0]["content"].endswith(persona)
          and stream[0]["content"].endswith(persona))


def main() -> int:
    print("=" * 72)
    print("P0-7 voice_style 真正生效（军师人格注入）")
    print("=" * 72)
    case_five_styles()
    case_unknown_fallback()
    case_no_avatar_default()
    case_length_budget()
    case_wired_into_main_chain()
    case_append_persona_pure()
    case_two_outlets_share_persona()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
