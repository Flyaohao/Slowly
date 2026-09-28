from pydantic import BaseModel, Field, field_validator
from typing import List, Optional
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


# --------------------------------------------------------------------------- #
# v5.0 用户级 AI 服务配置（设置页「AI 服务配置」分组）
# --------------------------------------------------------------------------- #
class AiConfigSaveRequest(BaseModel):
    """保存（含修改）AI 服务配置。保存前服务端强制连通性测试（D10）。"""

    provider_type: str = Field(..., description="openai / anthropic")
    base_url: str = Field(..., min_length=1)
    model_name: str = Field(..., min_length=1)
    embedding_base_url: str = ""
    embedding_model: str = ""
    embedding_api_key: str = Field("", description="留空则与聊天共用同一组 key")
    enable_rate_limit: bool = Field(True, description="D6：用户自选是否参与限流")
    new_keys: List[str] = Field(default_factory=list, description="本次新增的明文 key")


class AiConfigTestRequest(BaseModel):
    """只测不存（设置页「测试连接」按钮）。"""

    provider_type: str
    base_url: str
    model_name: str
    embedding_base_url: str = ""
    embedding_model: str = ""
    embedding_api_key: str = ""
    keys: List[str] = Field(default_factory=list, description="临时测试用明文 key")


class AiKeyAddRequest(BaseModel):
    key: str = Field(..., min_length=1)
    label: str = Field("", max_length=50)


class AiKeyToggleRequest(BaseModel):
    enabled: bool
