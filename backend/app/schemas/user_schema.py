from pydantic import BaseModel, Field
from typing import Optional
from datetime import date


class UserProfileResponse(BaseModel):
    user_id: int
    email: str
    nickname: Optional[str] = None
    avatar_url: Optional[str] = None
    gender: Optional[str] = None
    birthday: Optional[date] = None
    city: Optional[str] = None
    signature: Optional[str] = None
    love_anniversary: Optional[date] = None
    email_notify_enabled: bool = False


class UserProfileUpdateRequest(BaseModel):
    nickname: Optional[str] = Field(None, max_length=50)
    gender: Optional[str] = Field(None, max_length=20)
    birthday: Optional[date] = None
    city: Optional[str] = Field(None, max_length=50)
    signature: Optional[str] = Field(None, max_length=200)
    love_anniversary: Optional[date] = None


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
