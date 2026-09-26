from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime


class DualPerspectiveEventCreate(BaseModel):
    title: str = Field(..., min_length=1, max_length=200)
    event_time: datetime
    #: 整改 §8.6：军师发起时必须告诉伴侣「为什么现在写这个」。
    #: 为空时按普通创建处理——只有带邀请语的才通知对方（否则用户自建
    #: 事件会把伴侣吵醒，而那时列表里还什么都没有）。
    invite_message: Optional[str] = Field(None, max_length=200)


class DualPerspectiveRecordSubmit(BaseModel):
    content: str = Field(..., min_length=1)
    visibility: str = Field(default="hidden", max_length=20)


class DualPerspectiveRecordUpdate(BaseModel):
    content: Optional[str] = Field(None, min_length=1)
    visibility: Optional[str] = Field(None, max_length=20)


class DualPerspectiveRecordOut(BaseModel):
    id: int
    event_id: int
    user_id: int
    content: str
    visibility: str
    created_at: datetime

    model_config = {"from_attributes": True}


class DualPerspectiveEventOut(BaseModel):
    id: int
    relation_id: int
    title: str
    event_time: datetime
    status: str
    created_at: datetime
    updated_at: datetime
    records: List[DualPerspectiveRecordOut] = []
    #: 契约 §2.1-1（只增不减的新字段）：对方是否已提交视角。
    #: 对方未公开时其 content 不在 records 里，客户端靠这个字段渲染"对方已写"。
    partner_submitted: bool = False

    model_config = {"from_attributes": True}


class DualPerspectiveEventListOut(BaseModel):
    id: int
    relation_id: int
    title: str
    event_time: datetime
    status: str
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}
