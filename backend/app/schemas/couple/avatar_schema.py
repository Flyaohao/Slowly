from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime


class AvatarUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=50)
    body_color: Optional[str] = Field(None, max_length=20)
    face_config: Optional[dict] = None
    outfit_config: Optional[dict] = None
    background_url: Optional[str] = Field(None, max_length=500)


class VoiceStyleUpdate(BaseModel):
    voice_style: str = Field(..., min_length=1, max_length=30)


class AvatarOut(BaseModel):
    id: int
    relation_id: int
    name: str
    body_color: str
    face_config: Optional[dict] = None
    outfit_config: Optional[dict] = None
    voice_style: str
    background_url: Optional[str] = None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class AvatarAssetOut(BaseModel):
    id: int
    asset_type: str
    asset_url: str
    unlock_condition: Optional[str] = None
    unlocked: bool = False

    model_config = {"from_attributes": True}
