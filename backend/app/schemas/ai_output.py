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

from pydantic import BaseModel, Field, field_validator

try:  # Python 3.8+
    from typing import Literal
except ImportError:  # pragma: no cover
    from typing_extensions import Literal  # type: ignore


class RiskLevel(str, Enum):
    """风险等级。

    前五个取值必须与 `app/services/safety_service.py` 的 RISK_LEVEL_ORDER
    完全一致，不可随意增删，否则安全链路会失配。

    `unknown` 是**第六个、也是唯一不参与危险度排序**的取值（整改 B4.3 P0-4）。
    它代表「无从判定」，不是「某一档危险」：

    - 模型返回 `null` / 空串 / 大小写混杂 / 自造值时，服务端一律归到 `unknown`，
      并且**禁止**产出双人调解产物（`mediation_service.BLOCKING_RISK_LEVELS`）。
      旧实现把这类值降级成 `normal` 是 fail open——一次字段缺失就能换来
      「正常」通行证。
    - 客户端把 `unknown` 当作「无法解析」处理：不渲染本地警示卡，但**阻断**
      所有双人动作（`AiRiskLevel.fromWire` + `mediationBlockedByRisk`）。

    把 `unknown` 放进枚举而不是留成 Python 侧的内部字符串，是为了让
    `RiskLevel` 与客户端 `AiRiskLevel` 的取值集合**逐值对齐**——
    契约测试 `test_action_routing_contract.py` 会比对两侧的每一个字面量。
    """

    NORMAL = "normal"
    HEATED_CONFLICT = "heated_conflict"
    MANIPULATION_RISK = "manipulation_risk"
    ABUSE_RISK = "abuse_risk"
    SELF_HARM_RISK = "self_harm_risk"
    UNKNOWN = "unknown"


class RewriteItem(BaseModel):
    """单个改写版本"""

    style: str = Field(..., description="改写风格，如：温柔 / 直接 / 道歉 / 解释 / 想和解")
    content: str = Field(..., description="该风格下的改写内容，直接可复制发送")


class ActionIntent(str, Enum):
    """行动行的**意图**（整改 B4.3 P1-7）。

    为什么必须由模型显式给出、而不是客户端按 `scene_key` 推断：同一个
    `scene_key` 下意图可以不同。`private_advisor` 既可能在做**冲突分析**
    （该给「发起双人调解」），也可能只是**情绪倾诉**（只该给「继续对话」）。
    按 scene_key 一刀切会把这两种完全不同的处置混成一件事。

    客户端拿到的是一张**确定性路由表**（`AiChatViewModel.orderedActionsFor`），
    表的输入就是这个字段——**禁止**从正文里做关键词猜测：正文是模型自由生成的，
    拿它当路由依据等于把「该不该把两个人关进同一场会话」交给一次字符串匹配。

    `unknown` 是 fail closed 的落点：模型没给、给了非法值、或响应结构缺失时，
    客户端只保留「复制原回答」这一条最无害的出路。
    """

    EXPRESSION_REWRITE = "expression_rewrite"
    PARTNER_TRANSLATE = "partner_translate"
    PRIVATE_ADVISOR = "private_advisor"
    EMOTION_SUPPORT = "emotion_support"
    COLD_WAR = "cold_war"
    RELATIONSHIP_REVIEW = "relationship_review"
    UNKNOWN = "unknown"


class ActionRoutingFields(BaseModel):
    """行动行路由的三个**结构化**输入（整改 B4.3 P1-7）。

    单独抽成一个基类而不是逐个模型复制：行动行对**每个**聊天场景都要裁决，
    漏一个场景就等于那个场景永远只能给「复制」。字段全带默认值，
    旧构造逐字节兼容。

    **禁止**客户端从正文里做关键词猜测来决定给哪些动作：正文是模型自由生成的
    文本，拿它当路由依据等于把「该不该把两个人关进同一场会话」交给一次字符串
    匹配。所以这三个判断必须由模型显式产出、经 Pydantic 校验后下发。
    """

    intent: ActionIntent = Field(
        ActionIntent.UNKNOWN,
        description=(
            "本次回答的意图，决定客户端给出哪些行动："
            "expression_rewrite=帮我改写表达；partner_translate=解释对方的话；"
            "private_advisor=对双方矛盾做分析（可能建议调解）；"
            "emotion_support=用户只是在倾诉情绪（**不要**给调解/复盘）；"
            "cold_war=冷战破冰；relationship_review=关系复盘；"
            "无法归类时填 unknown。"
        ),
    )
    suggest_dual_perspective: bool = Field(
        False,
        description=(
            "是否明确建议「邀请伴侣补充双视角」。只有这件事确实需要双方各自写下"
            "版本才有价值时才 true（如双方说法明显不一致）；单纯安慰、解释对方一句话"
            "一律 false——把伴侣拉进一次不必要的双人作业是打扰。"
        ),
    )
    review_worthy: bool = Field(
        False,
        description=(
            "这次对话是否**值得存档为一次关系复盘**：出现了可复用的模式、"
            "明确的触发点或下次可用的表达时才 true。只是一句安慰、"
            "或用户没有在讲具体事件时一律 false。"
        ),
    )


class TranslateOutput(ActionRoutingFields):
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


class AdvisorOutput(TranslateOutput):
    """私人军师 / 对方翻译的输出 + 「可以发起双人调解」这一条**建议**。

    整改 B4.1-6（正式入口）：调解的正式入口是「军师识别到冲突语境后，给出一个
    可点的发起动作」，不是侧边栏里的一级入口。军师要能识别语境，就得有一个
    模型可控的开关字段——就是 `suggest_mediation`。

    为什么**只给开关、不给 id**：

    - 会话必须由后端在用户**真实点击之后**创建（`POST /ai/mediation/start`），
      模型凭空产出的 `session_id` 是假的，客户端照着跳只会 404；
    - 仅仅「AI 觉得你们在吵」不该让伴侣收到一条邀请通知——那是产品动作，
      不是模型可以代劳的动作（隐私红线：不得让 AI 直接执行外部动作）。

    字段带默认值 False：旧调用方（以及绝大多数非冲突语境）逐字节兼容，
    只有明确判定为「双方矛盾」时模型才置 true。
    """

    suggest_mediation: bool = Field(
        False,
        description=(
            "本次对话是否属于需要双方坐下来谈的矛盾（正在争执、冷战、反复为同一件事吵、"
            "一句话说不好就要吵起来）。只有确实是双方之间的矛盾才置 true；"
            "单人情绪倾诉、与伴侣无关的困扰、单纯想理解对方某句话，一律 false。"
        ),
    )


class RewriteOutput(ActionRoutingFields):
    """表达改写：一次给出多个风格的版本

    整改 B4.3 P1-7：继承 [ActionRoutingFields]。改写场景的行动行是
    「复制 + 分享 + 写信（收进更多）」，**不默认**给双视角和复盘——
    只有模型明确判定 `review_worthy` 时才多一个「记录为复盘」。
    这些判断必须来自结构化字段，客户端不得从改写正文里猜。
    """

    summary: str = Field(..., description="一句话结论")
    rewrites: List[RewriteItem] = Field(..., description="改写版本列表，建议 3-5 个不同风格")
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


class ColdWarOutput(ActionRoutingFields):
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
    # 整改 B4.3 P1-7：冷战的行动行是「优先复制破冰表达；安全且**明确建议**时才
    # 提供调解」。所以冷战场景也必须有这个开关——此前它只挂在 AdvisorOutput 上，
    # 冷战的行动行因此永远给不出调解入口（能力在，入口不在）。
    suggest_mediation: bool = Field(
        False,
        description=(
            "这次冷战是否已经发展到需要双方坐下来谈（而不是靠一句破冰就能缓过来）。"
            "只是暂时不想说话、需要一点空间时一律 false。"
        ),
    )


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

    字段口径随信件方向变化（2026-10-01 修复）：收信人视角下讲的是写信的 TA，
    写信人视角下讲的是**读完这封信的** TA。本模型的 description 会随 Function
    Calling 的 Schema / 内联 Schema 一起进 prompt，所以这里不能写死「对方」——
    写死了就会和提示词里的方向说明打架，把模型拽回收信人口吻。
    """

    summary: str = Field(..., description="一句话摘要")
    key_concerns: List[str] = Field(
        default_factory=list, description="这封信里最被看重的点"
    )
    emotion: str = Field("", description="情绪描述，口径见提示词「这封信的来向」")
    expected_response: str = Field(
        "", description="期待或可能给出的回应，口径见提示词「这封信的来向」"
    )
    misunderstandable: List[LetterMisunderstandableItem] = Field(
        default_factory=list, description="可能被误解的句子及说明"
    )
    reply_suggestions: List[str] = Field(
        default_factory=list, description="建议的回信方向，写信人视角留空"
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
    """信件回信建议：给出多个风格的回信版本。

    方向口径同 `LetterAnalysisOutput`：收信人视角 = 用户可发的回信；
    写信人视角 = TA 可能会怎么回。
    """

    summary: str = Field("", description="一句话摘要")
    replies: List[RewriteItem] = Field(
        default_factory=list,
        description="回信版本列表，每项含 style 与 content，写信人视角填 TA 可能给出的回应",
    )
    do_not_say: str = Field(
        "", description="回信时应避免说的话，写信人视角填「想让 TA 这样回，自己别说什么」"
    )
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")


def flatten_text(value: Any) -> Any:
    """把模型「用数组表达的一段文字」压回单个字符串。

    实测踩过（qwen3.7-flash，2026-09-16）：prompt 里写「给出 2-3 条建议」，
    模型就把 `communication_guide` 输出成 `["建议一", "建议二"]`，与 schema 的
    `str` 冲突——一处类型不符会让**整份结构化结果**被判无效，字段全丢，
    用户看到的正文还在，但卡片、维度、建议全没了。

    文案类字段统一在这里兜住：数组用换行拼接、对象按「键：值」拼接，
    其余原样交给 Pydantic 校验。
    """
    if isinstance(value, list):
        return "\n".join(str(v).strip() for v in value if str(v).strip())
    if isinstance(value, dict):
        return "\n".join("%s：%s" % (k, v) for k, v in value.items())
    return value


class QuestionnaireDimensionAnalysis(BaseModel):
    """量表分析里单个维度的解读。

    字段名与客户端 `QuestionnaireDto.DimensionAnalysis` 逐字一致，
    改名会同时打断后端落库与客户端渲染两侧。
    """

    key: str = Field(..., description="维度英文 key，如 attachment_anxiety")
    label: str = Field("", description="维度中文名，如「依恋焦虑」")
    score: float = Field(0.0, description="该维度得分 0-100，由系统给定，照抄不要改")
    level: str = Field("", description="高/中/低，由系统按分数给定")
    analysis: str = Field("", description="1-2 句通俗解读，一段文字（不要数组），像朋友聊天")

    @field_validator("analysis", mode="before")
    @classmethod
    def _flatten_analysis(cls, value: Any) -> Any:
        return flatten_text(value)


class QuestionnaireAnalysisOutput(BaseModel):
    """量表分析报告（刚交完问卷时的逐维度解读）。

    与 `ProfileReportOutput` 的分工，二者不要合并：
    - 本模型服务的是一次**测评结果的解读**，逐维度给短评，用户看完就走；
    - `ProfileReportOutput` 服务的是**个人画像长文**，用户平时回来翻阅。
    """

    profile_analysis: str = Field(
        "", description="2-3 段整体解读：依恋类型的核心特点、在关系中的典型表现、可能的成因（一整段文字，不要数组）"
    )
    dimension_analyses: List[QuestionnaireDimensionAnalysis] = Field(
        default_factory=list, description="每个维度一条，按得分从高到低排序"
    )
    strengths: str = Field(
        "", description="2-3 句，用户在关系中的优势与积极特质（一整段文字，不要数组）"
    )
    growth_tips: List[str] = Field(
        default_factory=list, description="3 条具体可执行的成长建议"
    )
    communication_guide: str = Field(
        "", description="2-3 条与伴侣沟通的实用建议，写成一段文字、建议之间换行（不要输出数组）"
    )
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")

    @field_validator("profile_analysis", "strengths", "communication_guide", mode="before")
    @classmethod
    def _flatten_text_fields(cls, value: Any) -> Any:
        return flatten_text(value)


class ReviewOutput(ActionRoutingFields):
    """关系复盘：针对一次争吵、和好或重要事件做结构化回顾。

    字段对应关系（功能设计 六.9 的六项输出）：
      触发点              → trigger
      双方真实需求        → own_need / partner_need（拆成两条，比合成一段更好渲染）
      误解发生处          → misunderstanding
      升级冲突的话语      → escalation_phrases
      降低冲突的有效表达  → deescalation_phrases
      下次可提前使用的表达 → next_time_scripts
    """

    summary: str = Field("", description="一句话复盘结论，不超过 40 字")
    trigger: str = Field(
        "", description="这次冲突真正的触发点（不是表面的那件事，而是它被点着的原因）"
    )
    own_need: str = Field("", description="你在这件事里真正想要的是什么")
    partner_need: str = Field("", description="对方在这件事里真正想要的是什么")
    misunderstanding: str = Field("", description="误解是从哪一句话、哪一个动作开始发生的")
    escalation_phrases: List[str] = Field(
        default_factory=list, description="把冲突推高的话或做法，通常 2-3 条"
    )
    deescalation_phrases: List[str] = Field(
        default_factory=list, description="当时如果能这样说，冲突会降温的话术，通常 2-3 条"
    )
    next_time_scripts: List[str] = Field(
        default_factory=list, description="下次遇到同类苗头时可以提前说的话，通常 2-3 条"
    )
    risk_level: RiskLevel = Field(RiskLevel.NORMAL, description="风险等级")

    @field_validator(
        "summary",
        "trigger",
        "own_need",
        "partner_need",
        "misunderstanding",
        mode="before",
    )
    @classmethod
    def _flatten_text_fields(cls, value: Any) -> Any:
        return flatten_text(value)


class MemoryDistillOutput(BaseModel):
    """对话记忆沉淀：从一轮对话里抽取值得长期记住的信息。

    这是一个**内部辅助场景**（scene_key = `memory_distill`），不出现在客户端的
    场景选择列表里，也不由用户直接触发：由对话链路在助手消息落库后调用，
    用来填充「AI 记忆」页与后续轮次的 `get_memory_context()`。

    为什么要有 `should_remember` 这道闸：
    如果每轮对话都无条件写一条记忆，记忆页会被噪音淹没，
    更糟的是这些噪音会通过 `get_memory_context()` 进入后续每一轮的 prompt，
    反而污染模型。所以宁可漏记，不要滥记。
    """

    should_remember: bool = Field(
        ..., description="本轮对话是否包含值得长期记住的信息（偏好、关系事实、雷区、诉求）"
    )
    memory_type: Literal["偏好", "关系事实", "沟通雷区", "核心诉求"] = Field(
        "关系事实", description="记忆类别"
    )
    memory_text: str = Field(
        "",
        description="用一句话陈述该信息，20-40 字，第三人称，不要包含姓名、联系方式等隐私信息",
    )

    # ---- v3.2 §2 谓词注册表 / §1 身份提示（全部带默认，旧构造逐字节兼容）----
    predicate: Literal[
        "preference", "behavior", "goal", "constraint",
        "trait", "event", "pattern", "state", "other",
    ] = Field(
        "other",
        description="封闭谓词 enum（与 memory_registry.PREDICATES 同值）；"
                    "LLM 只能从中选，不得自由生成 key",
    )
    object_hint: str = Field(
        "",
        description="object_key 词典的中文提示词（如「辣」「回复速度」）；"
                    "服务端归一，未命中落 other+registry_miss",
    )
    subject_role: Literal["self", "partner", "relationship", "event"] = Field(
        "self", description="陈述主体：我/伴侣/关系/事件"
    )
    epistemic_hint: str = Field(
        "unknown",
        description="认识论提示（仅参考——reported≠attributed 时强制 attributed_report，"
                    "hint 永不覆盖身份铁则）",
    )


class ViewpointDimensionSuggestion(BaseModel):
    """建议调整的单个维度。

    **刻意不含分数**（用户需求 #5）：让模型给出「改成多少分」，等于把画像的
    最终形态交给一次生成；这里只让它回答方向与强度，具体移动多少分由
    `profile_service.compute_enriched_score` 按幅度规则换算（单次 ≤12、
    相对问卷基线累计 ≤20）。
    """

    dimension_key: str = Field(..., description="维度 key，必须来自 prompt 里给出的清单")
    direction: Literal["up", "down"] = Field(..., description="up=这一项更强 / down=更弱")
    strength: Literal["mild", "moderate"] = Field("mild", description="变化强度，拿不准用 mild")


class ViewpointAnalysisOutput(BaseModel):
    """观点分析：把用户主动写下的一段观点翻译成「它说明了什么」+「要不要写进画像」。

    `dimensions` 里的 key 由服务端按白名单二次过滤——模型自造的 key 会被丢弃，
    因为「有哪些维度」是产品配置，不该在模型输出里再有一个真相源。
    """

    summary: str = Field(..., description="一句话概括，40 字以内，尽量沿用用户自己的说法")
    values: List[str] = Field(
        default_factory=list, description="体现的价值取向，2-4 条，每条不超过 12 字"
    )
    stance: str = Field("", description="对这段关系的态度倾向，一句话")
    confidence: float = Field(0.0, ge=0.0, le=1.0, description="判断的把握程度（0-1）")
    basis: List[str] = Field(
        default_factory=list, description="依据：引用观点原文里的句子，1-3 条"
    )
    suggest_enrich: bool = Field(False, description="是否建议写进画像")
    dimensions: List[ViewpointDimensionSuggestion] = Field(
        default_factory=list, description="建议调整的维度（仅在 suggest_enrich 为真时有意义）"
    )
    memory_type: Optional[str] = Field(
        None,
        description=(
            "如果这条观点值得长期记住，建议归入的记忆类型："
            "偏好 / 关系事实 / 沟通雷区 / 核心诉求；不值得记或拿不准时留空"
        ),
    )


#: 场景 → 输出模型。未登记的 scene_key 统一回退到 TranslateOutput。
SCENE_OUTPUT_MODELS: Dict[str, Any] = {
    # 私人军师 / 对方翻译用 AdvisorOutput（= TranslateOutput + suggest_mediation）：
    # 调解的正式入口挂在这两个场景上，模型判定「这是双方矛盾」时给出建议动作。
    # `cold_war` 有自己的模型（它本来就在讲怎么破冰），不叠这个字段。
    "private_advisor": AdvisorOutput,
    "partner_translate": AdvisorOutput,
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
    "relationship_review": ReviewOutput,
    # 内部辅助场景：不属于用户可选场景，由对话链路后台调用
    "memory_distill": MemoryDistillOutput,
    # 观点分析（用户需求 #5）：产出「要不要写进画像 + 改哪些维度」的建议，
    # 不直接写入画像——写入必须由用户确认后走 profile_service。
    "viewpoint_analysis": ViewpointAnalysisOutput,
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

    # Pydantic v2 会把这个模型类的 docstring 自动放进顶层 `description`，
    # 而我们的 docstring 是写给开发者看的（里面会提到别的模型类名、
    # 以及「不要合并」这类内部约定）。一旦随 Schema 发给模型，模型很可能
    # 把它当成一个真实字段照抄进输出 JSON —— 2026-09-16 实测发生过。
    # 顶层 `title` 同理，纯噪音。字段级的 description 要保留，那是有效约束。
    schema.pop("description", None)
    schema.pop("title", None)

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
