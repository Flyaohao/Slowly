from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime


class LetterCreate(BaseModel):
    title: Optional[str] = Field(None, max_length=200)
    content: str = Field(..., min_length=1)
    letter_type: str = Field(default="normal", max_length=30)
    receiver_id: Optional[int] = None
    send_time: Optional[datetime] = None
    unlock_time: Optional[datetime] = None
    is_private: bool = False
    send_now: bool = False


class LetterUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=200)
    content: Optional[str] = Field(None, min_length=1)
    letter_type: Optional[str] = Field(None, max_length=30)
    receiver_id: Optional[int] = None
    send_time: Optional[datetime] = None
    unlock_time: Optional[datetime] = None
    is_private: Optional[bool] = None


class LetterOut(BaseModel):
    id: int
    relation_id: Optional[int] = None
    sender_id: int
    receiver_id: int
    title: str
    content: str
    letter_type: str
    status: str
    send_time: Optional[datetime] = None
    unlock_time: Optional[datetime] = None
    is_private: bool
    is_favorite: bool
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class LetterListOut(BaseModel):
    id: int
    relation_id: Optional[int] = None
    sender_id: int
    receiver_id: int
    title: Optional[str] = None
    content: Optional[str] = None
    letter_type: str
    status: str
    send_time: Optional[datetime] = None
    unlock_time: Optional[datetime] = None
    is_private: bool
    is_favorite: bool
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class LetterListResponse(BaseModel):
    items: List[LetterListOut]
    total: int
    page: int
    page_size: int


class LetterQuery(BaseModel):
    letter_type: Optional[str] = None
    status: Optional[str] = None
    is_favorite: Optional[bool] = None
    page: int = Field(default=1, ge=1)
    page_size: int = Field(default=20, ge=1, le=100)


class BatchDeleteRequest(BaseModel):
    letter_ids: List[int] = Field(..., min_length=1, max_length=100)
