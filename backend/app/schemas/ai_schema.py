from pydantic import BaseModel, Field
from typing import Optional, Any, List, Dict, Literal
from datetime import datetime


class ChatRequest(BaseModel):
    session_id: Optional[int] = None
    scene_key: str
    message: str = Field(..., min_length=1, max_length=2000)


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
    rating: int = Field(..., ge=1, le=5)
    feedback_tag: Optional[str] = None
    feedback_text: Optional[str] = None


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
