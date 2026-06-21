from pydantic import BaseModel, Field
from typing import Optional
from datetime import datetime


class CoupleInfoResponse(BaseModel):
    relation_id: int
    partner_user_id: int
    partner_nickname: Optional[str] = None
    partner_avatar_url: Optional[str] = None
    status: str
    bind_time: Optional[datetime] = None
    space_name: Optional[str] = None
    theme_color: Optional[str] = None
    background_url: Optional[str] = None


class CoupleSpaceUpdateRequest(BaseModel):
    name: Optional[str] = Field(None, max_length=100)
    theme_color: Optional[str] = Field(None, max_length=20)
    background_url: Optional[str] = Field(None, max_length=500)


class BindRequest(BaseModel):
    invite_code: str = Field(..., min_length=8, max_length=8)
