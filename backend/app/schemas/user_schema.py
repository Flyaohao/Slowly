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


class UserProfileUpdateRequest(BaseModel):
    nickname: Optional[str] = Field(None, max_length=50)
    gender: Optional[str] = Field(None, max_length=20)
    birthday: Optional[date] = None
    city: Optional[str] = Field(None, max_length=50)
    signature: Optional[str] = Field(None, max_length=200)
    love_anniversary: Optional[date] = None


class PrivatePasswordRequest(BaseModel):
    password: str = Field(..., min_length=6, max_length=128)


class PrivateVerifyRequest(BaseModel):
    password: str
