from pydantic import BaseModel, Field, field_validator
from typing import Optional, Any, List, Dict, Literal
from datetime import datetime

#: P-B §1.1：chat_mode 白名单。非法值回落 deep（不 422——档位是体验参数，
#: 旧版本客户端/脏请求不该打不开对话）。
_CHAT_MODES = ("quick", "deep", "expert")


class ChatRequest(BaseModel):
    session_id: Optional[int] = None
    scene_key: str
    message: str = Field(..., min_length=1, max_length=2000)
    #: 三档模式，默认 deep（与既有行为逐参数相同 → 旧客户端零回归）。
    #: ⚠️ 字段名必须是 chat_mode，**绝不能叫 mode**——lc_prompt_builder 的
    #: mode 是输出通道（structured|stream），撞名会在那一层直接错乱。
    chat_mode: str = "deep"

    @field_validator("chat_mode")
    @classmethod
    def _normalize_chat_mode(cls, v: str) -> str:
        mode = (v or "").strip().lower()
        return mode if mode in _CHAT_MODES else "deep"


class StructuredOutput(BaseModel):
    summary: Optional[str] = None
    emotion_validation: Optional[str] = None
    partner_possible_meaning: Optional[str] = None
    suggested_reply: Optional[str] = None
    do_not_say: Optional[str] = None
    next_step: Optional[str] = None
    risk_level: Optional[str] = None
    suggested_actions: Optional[List[str]] = None
    rewrites: Optional[List[Dict[str, str]]] = None


class MessageOut(BaseModel):
    id: int
    role: str
    content: str
    structured_output: Optional[dict] = None
    risk_level: Optional[str] = None
    created_at: datetime

    model_config = {"from_attributes": True}


class SessionOut(BaseModel):
    id: int
    scene_key: str
    title: Optional[str] = None
    privacy_level: str
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class ChatResponse(BaseModel):
    session_id: int
    message: MessageOut


class FeedbackRequest(BaseModel):
    #: 整改契约 §8.3：可选——不传仍回落到「会话最后一条 AI 消息」（旧 APK 兼容）；
    #: 传了则必须是该会话内的 assistant 消息（服务端校验）。
    #: rating 由必填放宽为可选（向后兼容：旧客户端仍传；补填结果时可不带评分）。
    rating: Optional[int] = Field(default=None, ge=1, le=5)
    feedback_tag: Optional[str] = None
    feedback_text: Optional[str] = None
    #: 契约 §3.4（只增不减）：建议是否被采纳 / 结果回访。
    #: 旧 APK 不传 → None（保持「有建议无 outcome」，进入 pending 回访列表）。
    adopted: Optional[bool] = None
    outcome: Optional[str] = Field(default=None, max_length=2000)
    #: 整改契约 §8.3：指定反馈落在哪条 AI 消息（历史消息可回填采用/结果）。
    message_id: Optional[int] = None


class FeedbackOut(BaseModel):
    """反馈回读（新增；会话消息与提交响应用，JSON 只增不减）。"""

    message_id: int
    rating: Optional[int] = None
    adopted: Optional[bool] = None
    outcome: Optional[str] = None
    feedback_tag: Optional[str] = None
    feedback_text: Optional[str] = None

    model_config = {"from_attributes": True}


class SceneOut(BaseModel):
    scene_key: str
    name: str
    description: Optional[str] = None

    model_config = {"from_attributes": True}


class LetterUnderstandRequest(BaseModel):
    letter_id: int


class LetterRewriteRequest(BaseModel):
    letter_id: int
    style: str = Field(..., min_length=1, max_length=200)


class LetterReplyRequest(BaseModel):
    letter_id: int


class RewriteRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=2000, description="要改写的原始文本")
    context: Optional[str] = Field(None, max_length=500, description="补充背景信息")


class ReviewRequest(BaseModel):
    description: str = Field(..., min_length=1, max_length=4000, description="这次争吵/冷战/和好的经过")
    context: Optional[str] = Field(None, max_length=500, description="补充背景")
    #: 契约 §8.7：发生时间（选填，ISO8601）。不传则按本次复盘时间记录。
    event_time: Optional[str] = Field(None, max_length=32, description="事件发生时间（选填）")


class ReviewOutcomeRequest(BaseModel):
    """回填复盘「后来怎么样了」（契约 §8.7 后续结果）。"""

    outcome: str = Field(..., min_length=1, max_length=1000, description="后来实际怎么样了")


class DualSummaryRequest(BaseModel):
    event_id: int = Field(..., description="双视角事件 id")


class MemoryCardRequest(BaseModel):
    target_type: str = Field(..., pattern="^(anniversary|wishlist)$", description="条目类型")
    target_id: int = Field(..., description="条目 id")


class LetterAnalysisOut(BaseModel):
    summary: Optional[str] = None
    key_concerns: Optional[List[str]] = None
    emotion: Optional[str] = None
    expected_response: Optional[str] = None
    misunderstandable: Optional[List[Dict[str, str]]] = None
    reply_suggestions: Optional[List[str]] = None
    risk_level: Optional[str] = None


class LetterRewriteOut(BaseModel):
    summary: Optional[str] = None
    rewritten_title: Optional[str] = None
    rewritten_content: Optional[str] = None
    changes: Optional[str] = None
    risk_level: Optional[str] = None


class LetterReplyOut(BaseModel):
    summary: Optional[str] = None
    replies: Optional[List[Dict[str, str]]] = None
    do_not_say: Optional[str] = None
    risk_level: Optional[str] = None


class AgentHistoryItem(BaseModel):
    """Agent 多轮上下文中的一条。只允许 user / assistant，避免客户端伪造 system 提示"""

    role: Literal["user", "assistant"]
    content: str = Field(..., min_length=1, max_length=4000)


class AgentRequest(BaseModel):
    """Agent 问答请求。

    与 `/chat` 的区别：`/chat` 是"单轮进、结构化出"，Agent 会自行决定
    要不要先查画像 / 检索理论，并可能连续调用多个工具，最后才给答复。
    """

    question: str = Field(..., min_length=1, max_length=2000, description="用户提问")
    history: Optional[List[AgentHistoryItem]] = Field(
        None, max_length=10, description="最近几轮对话，用于多轮追问"
    )
