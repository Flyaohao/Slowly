"""关系事件的结构化输入输出（整改 2026-09-27）。

`reason` / `polarity` 的约束**刻意做在 schema 与 service 两层**：
schema 拦 API 入参，service 兜住脚本 / 内部调用 —— 只要有一条路径能绕过去，
「无原因事件污染军师」这个产品约束就形同虚设。
"""
from datetime import datetime
from typing import List, Optional

from pydantic import BaseModel, Field, field_validator

from app.models.relationship_event import POLARITY_VALUES

#: 写入原因的最小长度。
#: 低于这个长度基本是「无」「忘了」「不想说」这类敷衍，而事件一旦落库就会被
#: AI 当上下文引用 —— 宁可让用户多写一句话，也不要一条读不出方向的记录。
REASON_MIN_LEN = 8


class RelationshipEventCreate(BaseModel):
    title: str = Field(..., max_length=200, description="事件标题：一句话说清发生了什么")
    event_time: datetime = Field(..., description="发生时间")
    description: Optional[str] = Field(None, max_length=2000, description="经过补充")
    reason: str = Field(
        ...,
        max_length=1000,
        description="写入原因：这件事对你们的关系起到的明确作用（必填）",
    )
    polarity: str = Field(..., description="作用性质：positive（积极）/ negative（消极）")

    @field_validator("title")
    @classmethod
    def _v_title(cls, v: str) -> str:
        v = (v or "").strip()
        if not v:
            raise ValueError("标题不能为空")
        return v

    @field_validator("reason")
    @classmethod
    def _v_reason(cls, v: str) -> str:
        v = (v or "").strip()
        if len(v) < REASON_MIN_LEN:
            raise ValueError("请写清这件事对你们关系的作用（至少 %d 个字）" % REASON_MIN_LEN)
        return v

    @field_validator("polarity")
    @classmethod
    def _v_polarity(cls, v: str) -> str:
        v = (v or "").strip().lower()
        if v not in POLARITY_VALUES:
            raise ValueError("作用性质只能是 positive 或 negative")
        return v


class RelationshipEventUpdate(BaseModel):
    title: Optional[str] = Field(None, max_length=200)
    event_time: Optional[datetime] = None
    description: Optional[str] = Field(None, max_length=2000)
    reason: Optional[str] = Field(None, max_length=1000)
    polarity: Optional[str] = None

    @field_validator("title")
    @classmethod
    def _v_title(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        if not v:
            raise ValueError("标题不能为空")
        return v

    @field_validator("reason")
    @classmethod
    def _v_reason(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        if len(v) < REASON_MIN_LEN:
            raise ValueError("请写清这件事对你们关系的作用（至少 %d 个字）" % REASON_MIN_LEN)
        return v

    @field_validator("polarity")
    @classmethod
    def _v_polarity(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip().lower()
        if v not in POLARITY_VALUES:
            raise ValueError("作用性质只能是 positive 或 negative")
        return v


class RelationshipEventOut(BaseModel):
    id: int
    relation_id: int
    title: str
    event_time: datetime
    description: Optional[str] = None
    reason: str
    polarity: str
    created_by_user_id: Optional[int] = None
    created_at: Optional[datetime] = None

    class Config:
        from_attributes = True


class RelationshipEventListOut(BaseModel):
    total: int
    items: List[RelationshipEventOut]
