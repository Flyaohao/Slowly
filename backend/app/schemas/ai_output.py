"""AI 结构化输出模型（Pydantic v2）

设计说明
--------
当前主模型（qwen-plus / deepseek-v3）**不支持** `response_format=json_schema`，
但支持 Function Calling。因此采用「Function Calling 承载 Schema」方案：

    模型只被允许调用一个 submit_structured_answer 工具
      → 工具参数就是本文件定义的 Pydantic 模型
      → 从 tool_calls[0].function.arguments 取回 JSON
      → model_validate() 做类型强制与枚举校验

这样做的好处：
1. 绕开模型不支持结构化输出的限制；
2. 输出强校验，字段缺失/类型错误立刻暴露，不再靠手写 if-else 兜底；
3. 同一套机制同时覆盖「Function Calling」与「Pydantic 结构化输出」两条能力。
"""

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

try:  # Python 3.8+
    from typing import Literal
except ImportError:  # pragma: no cover
    from typing_extensions import Literal  # type: ignore


class RiskLevel(str, Enum):
    """五级风险等级。

    取值必须与 `app/services/safety_service.py` 的 RISK_LEVEL_ORDER 完全一致，
    不可随意增删，否则安全链路会失配。
    """

    NORMAL = "normal"
    HEATED_CONFLICT = "heated_conflict"
    MANIPULATION_RISK = "manipulation_risk"
    ABUSE_RISK = "abuse_risk"
    SELF_HARM_RISK = "self_harm_risk"


class RewriteItem(BaseModel):
    """单个改写版本"""

    style: str = Field(..., description="改写风格，如：温柔 / 直接 / 道歉 / 解释 / 想和解")
    content: str = Field(..., description="该风格下的改写内容，直接可复制发送")


class TranslateOutput(BaseModel):
    """对方翻译 / 私人军师 / 关系回顾 场景的结构化输出"""

    scene: str = Field("对方翻译", description="场景判定结论")
    summary: str = Field(..., description="一句话结论，不超过 40 字")
    emotion_validation: str = Field(..., description="先确认用户的情绪，让他感到被理解")
    partner_possible_meaning: str = Field(..., description="对方这句话背后可能的真实意思")
    suggested_reply: str = Field(..., description="建议用户这样回复，措辞可直接复制")
    do_not_say: str = Field(..., description="不建议说的话，并简要说明原因")
    next_step: str = Field(..., description="下一步行动建议")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="本次对话的风险等级")
    theory_refs: List[str] = Field(
        default_factory=list,
        description="本建议参考的心理学理论名称（如：Gottman 冲突四骑士、依恋理论），没有则留空数组",
    )


class RewriteOutput(BaseModel):
    """表达改写：一次给出多个风格的版本"""

    summary: str = Field(..., description="一句话结论")
    rewrites: List[RewriteItem] = Field(..., description="改写版本列表，建议 3-5 个不同风格")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class ColdWarOutput(BaseModel):
    """冷战开解"""

    goal_analysis: str = Field(..., description="双方各自的真实诉求分析")
    face_vs_need: str = Field(..., description="表面上争什么（面子）与情感上真正需要什么")
    approach: Literal["approach", "give_space"] = Field(
        ..., description="建议策略：approach=主动破冰，give_space=先给彼此空间"
    )
    approach_reason: str = Field(..., description="选择该策略的理由")
    opening_lines: List[str] = Field(..., description="3 条可直接使用的破冰开场白")
    avoid_reminders: List[str] = Field(..., description="不建议触碰的雷区")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class LetterUnderstandOutput(BaseModel):
    """信件 / 消息解读"""

    surface_meaning: str = Field(..., description="字面意思")
    underlying_need: str = Field(..., description="文字背后真正的情感需求")
    emotion_tone: str = Field(..., description="情绪基调，如：委屈、试探、失望、期待")
    suggested_reply: str = Field(..., description="建议回复")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class MediationRewriteOutput(BaseModel):
    """双人调解 —— 双方表达改写。

    字段名 `rewrite_a` / `rewrite_b` 是客户端已依赖的契约（见
    `android/.../data/model/MediationDto.kt` 的 `MediationStructuredOutput`），
    不可随意重命名。
    """

    rewrite_a: str = Field(..., description="A 方表达的温和化改写，只表达自身感受与需求，不带指责")
    rewrite_b: str = Field(..., description="B 方表达的回应式改写，先承接对方感受再表达自己")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class MediationSummaryOutput(BaseModel):
    """双人调解 —— 共识汇总。

    字段名同样是客户端已依赖的契约，不可重命名。
    """

    common_points: List[str] = Field(
        default_factory=list, description="双方真正的共同点，通常 2-4 条"
    )
    differences: List[str] = Field(
        default_factory=list, description="双方尚未达成一致的差异点，通常 2-4 条"
    )
    next_actions: List[str] = Field(
        default_factory=list, description="双方可以立刻执行的具体行动，通常 2-4 条"
    )
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class LetterMisunderstandableItem(BaseModel):
    """信件里容易被误解的一句话"""

    sentence: str = Field(..., description="原信中被引用的一句话")
    note: str = Field(..., description="它可能被怎样误解，以及更可能的真实意思")


class LetterAnalysisOutput(BaseModel):
    """信件解读（长文深度版）。

    注意与 `LetterUnderstandOutput` 的区别，二者服务于不同入口，不要合并：
    - `LetterUnderstandOutput`（scene `letter_understand`）服务于 AI 对话里的
      「信件/消息解读」场景，字段偏简；
    - 本模型（scene `letter_analysis`）服务于信箱里的「解读这封信」功能，
      字段更细，且必须与 `letter_ai_service.LETTER_UNDERSTAND_PROMPT` 的要求、
      以及客户端 `LetterDto.LetterUnderstanding` 三者逐字一致。
    """

    summary: str = Field(..., description="一句话摘要")
    key_concerns: List[str] = Field(
        default_factory=list, description="对方在这封信里最在意的点"
    )
    emotion: str = Field("", description="对方写信时的情绪状态")
    expected_response: str = Field("", description="对方期待的回应")
    misunderstandable: List[LetterMisunderstandableItem] = Field(
        default_factory=list, description="可能被误解的句子及说明"
    )
    reply_suggestions: List[str] = Field(
        default_factory=list, description="建议的回信方向"
    )
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class LetterRewriteOutput(BaseModel):
    """信件改写：把一封信改写成更合适的表达方式"""

    summary: str = Field("", description="一句话摘要")
    rewritten_title: str = Field("", description="改写后的标题")
    rewritten_content: str = Field("", description="改写后的正文，可直接复制")
    changes: str = Field("", description="相比原文做了哪些调整")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class LetterReplyOutput(BaseModel):
    """信件回信建议：给出多个风格的回信版本"""

    summary: str = Field("", description="一句话摘要")
    replies: List[RewriteItem] = Field(
        default_factory=list, description="回信版本列表，每项含 style 与 content"
    )
    do_not_say: str = Field("", description="回信时应避免说的话")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


#: 场景 → 输出模型。未登记的 scene_key 统一回退到 TranslateOutput。
SCENE_OUTPUT_MODELS: Dict[str, Any] = {
    "private_advisor": TranslateOutput,
    "partner_translate": TranslateOutput,
    "cold_war": ColdWarOutput,
    "expression_rewrite": RewriteOutput,
    "letter_understand": LetterUnderstandOutput,
    # 信箱里的三个信件功能各自独立成 scene。此前 letter_ai_service 借用
    # letter_understand 这个聊天场景，导致它读取的字段模型一个都不产；
    # letter_rewrite 则根本没登记、静默回退到 TranslateOutput。
    "letter_analysis": LetterAnalysisOutput,
    "letter_rewrite": LetterRewriteOutput,
    "letter_reply": LetterReplyOutput,
    "mediation_rewrite": MediationRewriteOutput,
    "mediation_summary": MediationSummaryOutput,
}

#: 输出纯文本、不走结构化解析的场景
TEXT_ONLY_SCENES = {"profile_report"}


def get_output_model(scene_key: str):
    """按场景取输出模型，未登记则回退到 TranslateOutput。"""
    return SCENE_OUTPUT_MODELS.get(scene_key, TranslateOutput)


def inline_json_schema(model) -> Dict[str, Any]:
    """把 Pydantic 的 JSON Schema 展开为内联形式（消除 $ref / $defs）。

    大多数 OpenAI 兼容端点（含 DashScope）在 function calling 的 parameters 中
    不接受 `$ref` 引用式 schema，必须把 `$defs` 里的定义就地展开，
    否则模型可能忽略字段约束、少填字段。
    """
    schema = model.model_json_schema()
    defs = schema.pop("$defs", {})

    def _resolve(node):
        if isinstance(node, dict):
            if "$ref" in node:
                ref_name = node["$ref"].split("/")[-1]
                resolved = defs.get(ref_name, {})
                merged = {k: v for k, v in node.items() if k != "$ref"}
                return _resolve(dict(resolved, **merged))
            return {k: _resolve(v) for k, v in node.items()}
        if isinstance(node, list):
            return [_resolve(item) for item in node]
        return node

    return _resolve(schema)


def build_tool_schema(
    model,
    tool_name: str = "submit_structured_answer",
    description: Optional[str] = None,
) -> Dict[str, Any]:
    """把 Pydantic 模型转成 OpenAI tools 定义。"""
    return {
        "type": "function",
        "function": {
            "name": tool_name,
            "description": description
            or "提交结构化的情感沟通分析结果。必须调用本工具提交最终答案，不要用普通文本回复。",
            "parameters": inline_json_schema(model),
        },
    }
