from pydantic import BaseModel
from typing import Optional, List
from datetime import datetime


class DimensionScoreOut(BaseModel):
    dimension_key: str
    score: float
    explanation: Optional[str] = None

    model_config = {"from_attributes": True}


class ProfileOut(BaseModel):
    id: int
    user_id: int
    profile_type: str
    confidence: float
    summary: Optional[str] = None
    version: int
    created_at: datetime

    model_config = {"from_attributes": True}


class ProfileDetailOut(ProfileOut):
    dimension_scores: List[DimensionScoreOut] = []


class CoupleProfileOut(BaseModel):
    id: int
    relation_id: int
    conflict_pattern: Optional[str] = None
    summary: Optional[str] = None
    created_at: datetime
    user_a_profile: Optional[ProfileDetailOut] = None
    user_b_profile: Optional[ProfileDetailOut] = None

    model_config = {"from_attributes": True}
