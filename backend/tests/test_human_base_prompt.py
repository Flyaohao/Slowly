"""P-B §6.2 验收：L0 人性化基线全量生效 + 位置 + 禁语逐字（B3）。

  1. **每个场景**组装后的 system 都含 L0 文本（聊天 7 场景 × 两种出口 +
     独立 builder：画像报告 / 回忆卡 / 练习整理 / 双视角 / 双出口流式）；
  2. L0 的出现位置**在人格之前**（system.index(L0) < system.index(persona)）；
  3. 禁语清单与 HUMAN_BASE_INSTRUCTION 常量**逐字一致**（防止后续被
     「优化」删掉几条——§2.2 明文：不许自行优化删减）。

零外部依赖：不连库、不调模型（纯组装层断言）。

运行：cd backend && python tests/test_human_base_prompt.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.prompt_builder import (  # noqa: E402
    HUMAN_BASE_INSTRUCTION,
    SYSTEM_PROMPTS,
    build_dual_summary_prompt,
    build_memory_card_prompt,
    build_practice_summary_prompt,
    build_profile_report_prompt,
    build_structured_stream_prompt,
    with_human_base,
)

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


#: 聊天链路场景（与 lc_prompt_builder.CHAT_SCENES 同源断言在这里做）
CHAT_SCENES = (
    "private_advisor",
    "partner_translate",
    "expression_rewrite",
    "cold_war",
    "mediation",
    "letter_understand",
    "relationship_review",
)

#: §2.2 禁语清单：逐字片段 → 常量里必须原样存在。
#: 这些字符串来自设计文档表格，**不许改写**；测试红了只能改常量补齐，
#: 不能改这里的期望值放水。
BAN_PHRASES = [
    # ① 内部概念
    "画像、数据、系统、字段、「未填写」",
    # ② 心理学/病理标签（含替代做法）
    "「回避型人格」",
    # ③ 咨询师仪式用语（四连）
    "「我们试着…」「记住…」「希望对你有所帮助」「如果你愿意补充更多背景」",
    # ④ 抽象名词堆叠
    "防御机制 / 沟通僵局 / 亲密感",
    # ⑤ 空洞共情开场
    "「我理解这种被冷落的失落感」",
    # 语感锚 + 诚实边界
    "用「你」称呼",
    "不确定就说不确定，不编造对方的想法",
    # 优先级规则（§2.3）
    "L0 禁语 > 长度要求（chat_mode）> 场景字段要求（scene）> 语气风格（voice_style）",
]


def _chat_system(scene_key: str, mode: str) -> str:
    from app.services.lc_prompt_builder import build_chat_messages

    return build_chat_messages(
        scene_key=scene_key,
        user_profile="【画像】X",
        partner_profile="",
        conflict_pattern="未确定",
        user_input="测试输入",
        history="user: 旧消息",
        rag_context="",
        memory_context="",
        mode=mode,
    )[0]["content"]


def case_every_scene_has_l0():
    print("\n[1] 每个场景组装后的 system 都含 L0")
    from app.services.lc_prompt_builder import CHAT_SCENES as LC_CHAT_SCENES

    check("与 lc_prompt_builder.CHAT_SCENES 同源", list(CHAT_SCENES) == list(LC_CHAT_SCENES),
          f"{CHAT_SCENES} vs {LC_CHAT_SCENES}")

    # 聊天 7 场景 × 结构化/流式两种出口
    for scene in CHAT_SCENES:
        for mode in ("structured", "stream"):
            text = _chat_system(scene, mode)
            check(f"{scene}/{mode} 含 L0", HUMAN_BASE_INSTRUCTION in text,
                  text[-200:])
            check(f"{scene}/{mode} L0 只出现一次", text.count(HUMAN_BASE_INSTRUCTION) == 1,
                  str(text.count(HUMAN_BASE_INSTRUCTION)))

    # 非聊天场景的独立 builder
    standalone = {
        "profile_report": build_profile_report_prompt("secure", 0.8, '{"d": 1}'),
        "memory_card": build_memory_card_prompt("anniversary", "在一起 3 年"),
        "practice_summary": build_practice_summary_prompt("周末复盘", "我觉得…", "我觉得…"),
        "dual_summary": build_dual_summary_prompt("谁洗碗", "我先说", "TA 说"),
        "structured_stream（双出口）": build_structured_stream_prompt(
            "你是一位信件解读师。\n\n## 分析\n请以 JSON 格式回复\n- summary: 摘要",
            _TinyOutput,
        ),
    }
    for name, text in standalone.items():
        check(f"{name} 含 L0", HUMAN_BASE_INSTRUCTION in text, text[-200:])


class _TinyOutput:
    """结构化流式 builder 只需要能生成 schema 的 Pydantic 形状。

    直接用真实模型（LetterAnalysisOut）更贴近生产，但那会把测试耦合到
    schemas 的字段增减上——这里关心的是 L0 位置，不是 schema 内容。
    """

    @classmethod
    def model_json_schema(cls):
        return {"type": "object", "properties": {"summary": {"type": "string"}}}


def case_l0_before_persona():
    print("\n[2] L0 在人格之前（场景(+L0) → 人格）")
    from app.services.ai_service import _append_persona
    from app.services.prompt_builder import build_persona_instruction

    persona = build_persona_instruction("小翻译", "direct")
    for scene in CHAT_SCENES:
        for mode in ("structured", "stream"):
            base = _chat_system(scene, mode)
            final = _append_persona(
                [{"role": "system", "content": base}], persona
            )[0]["content"]
            i_l0 = final.find(HUMAN_BASE_INSTRUCTION)
            i_persona = final.find(persona)
            check(f"{scene}/{mode} 含人格", i_persona != -1)
            check(f"{scene}/{mode} L0 在人格之前",
                  i_l0 != -1 and i_l0 < i_persona,
                  f"l0={i_l0} persona={i_persona}")
            check(f"{scene}/{mode} 人格仍在末段", final.endswith(persona),
                  final[-80:])


def case_ban_list_verbatim():
    print("\n[3] 禁语清单与常量逐字一致（§2.2 防「优化」删减）")
    for phrase in BAN_PHRASES:
        check(f"常量逐字含「{phrase[:24]}…」", phrase in HUMAN_BASE_INSTRUCTION)

    # 结构完整性：标题 + 优先级行都在，且常量非空、无占位符
    check("L0 有标题", HUMAN_BASE_INSTRUCTION.startswith("## 表达基线（一律遵守）"),
          HUMAN_BASE_INSTRUCTION[:40])
    check("L0 无占位符残留", "{" not in HUMAN_BASE_INSTRUCTION and "TODO" not in HUMAN_BASE_INSTRUCTION)


def case_with_human_base_idempotent():
    print("\n[4] with_human_base 幂等（重复拼接不叠加）")
    once = with_human_base("场景模板正文")
    twice = with_human_base(once)
    check("二次调用原样返回", once == twice)
    check("首调用追加 L0 一次", once.count(HUMAN_BASE_INSTRUCTION) == 1)
    check("原字符串不含 L0（不改原对象）",
          HUMAN_BASE_INSTRUCTION not in "场景模板正文")
    # 已含 L0 的文本原样返回（不 rstrip 改写）
    check("已含 L0 的文本逐字节不变", with_human_base(once) is once
          or with_human_base(once) == once)


def main() -> int:
    print("=" * 72)
    print("P-B §6.2 L0 人性化基线（全量生效 + 位置 + 逐字禁语）")
    print("=" * 72)
    case_every_scene_has_l0()
    case_l0_before_persona()
    case_ban_list_verbatim()
    case_with_human_base_idempotent()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
