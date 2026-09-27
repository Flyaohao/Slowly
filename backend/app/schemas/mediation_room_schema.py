"""共同调解室 · 请求/响应模型。"""

from typing import List, Optional

from pydantic import BaseModel, Field


class RoomCreateRequest(BaseModel):
    """创建事件卡（§二：四项必填，创建时一次成型）。"""

    name: str = Field(min_length=1, max_length=120)
    event_time: str = Field(min_length=1, max_length=64)
    cause_text: str = Field(min_length=1, max_length=2000)
    process_text: str = Field(min_length=1, max_length=4000)
    current_text: str = Field(min_length=1, max_length=2000)
    style_key: str = Field(min_length=1, max_length=40)


class RoomMessageRequest(BaseModel):
    """发消息；mention=True 表示同时召唤军师（@军师）。"""

    content: str = Field(min_length=1, max_length=4000)
    mention: bool = False


class SupplementRequest(BaseModel):
    """交互弹窗「补充」（§五.2，可附文字）。"""

    content: Optional[str] = Field(default=None, max_length=4000)


class EndVoteRequest(BaseModel):
    action: str = Field(pattern="^(vote|cancel)$")


class AdvisorStreamRequest(BaseModel):
    """流式端点凭 token 认领生成权（pending mutex 的所有权凭据）。"""

    token: str = Field(min_length=8, max_length=64)


class RoomStateOut(BaseModel):
    """房间状态快照（短轮询每次随消息返回）。"""

    room_id: int
    status: str
    advisor_phase: str
    round_no: int
    waiting_reply: bool
    my_role: str
    agree_me: bool
    agree_partner: bool
    end_vote_me: bool
    end_vote_partner: bool
    confirm_me: Optional[bool] = None
    confirm_partner: Optional[bool] = None
    advisor_generating: bool
    settlement: Optional[dict] = None
    result: Optional[str] = None
    settle_error: Optional[str] = None
    last_message_id: Optional[int] = None


class RoomMessageOut(BaseModel):
    id: int
    sender_type: str
    sender_user_id: Optional[int] = None
    content: str
    thinking: Optional[str] = None
    risk_level: Optional[str] = None
    round_no: Optional[int] = None
    created_at: Optional[str] = None


class RoomMessagesOut(BaseModel):
    messages: List[RoomMessageOut]
    state: RoomStateOut


class RoomSummaryOut(BaseModel):
    id: int
    name: str
    event_time: str
    style_key: str
    status: str
    advisor_phase: str
    creator_user_id: int
    last_message_at: Optional[str] = None
    created_at: Optional[str] = None


class RoomListOut(BaseModel):
    total: int
    active_count: int
    items: List[RoomSummaryOut]
