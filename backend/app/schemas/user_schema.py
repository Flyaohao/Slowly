from pydantic import BaseModel, Field, field_validator
from typing import Optional
from datetime import date

from app.services.astrology_service import MBTI_TYPES


class UserProfileResponse(BaseModel):
    user_id: int
    email: str
    nickname: Optional[str] = None
    avatar_url: Optional[str] = None
    gender: Optional[str] = None
    birthday: Optional[date] = None
    birth_hour: Optional[int] = None
    mbti: Optional[str] = None
    birth_place: Optional[str] = None
    city: Optional[str] = None
    signature: Optional[str] = None
    love_anniversary: Optional[date] = None
    email_notify_enabled: bool = False


class UserProfileUpdateRequest(BaseModel):
    nickname: Optional[str] = Field(None, max_length=50)
    gender: Optional[str] = Field(None, max_length=20)
    birthday: Optional[date] = None
    birth_hour: Optional[int] = Field(None, ge=0, le=23)
    mbti: Optional[str] = Field(None, max_length=8)
    birth_place: Optional[str] = Field(None, max_length=50)
    city: Optional[str] = Field(None, max_length=50)
    signature: Optional[str] = Field(None, max_length=200)
    love_anniversary: Optional[date] = None

    @field_validator("mbti")
    @classmethod
    def _validate_mbti(cls, value: Optional[str]) -> Optional[str]:
        """空串/None 都当未填（去掉空白后归一为 None，走既有「None 不覆盖」写库语义）。"""
        if value is None:
            return None
        normalized = value.strip().upper()
        if not normalized:
            return None
        if normalized not in MBTI_TYPES:
            raise ValueError("mbti 必须是 16 型之一（如 INTJ）")
        return normalized

    @field_validator("birth_place")
    @classmethod
    def _validate_birth_place(cls, value: Optional[str]) -> Optional[str]:
        """去首尾空白；空串归一为 None（同 mbti 语义，None 不覆盖已存值）。"""
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None


class NotificationPrefResponse(BaseModel):
    """通知偏好。

    email 与 email_ready 一起给前端，是为了让设置页能如实说明
    「发到哪个邮箱」以及「现在能不能发」——开关置灰而不是让用户白开一次。
    """

    email_notify_enabled: bool
    email: str
    email_ready: bool


class NotificationPrefUpdateRequest(BaseModel):
    email_notify_enabled: bool


class PrivatePasswordRequest(BaseModel):
    password: str = Field(..., min_length=6, max_length=128)


class PrivateVerifyRequest(BaseModel):
    password: str
