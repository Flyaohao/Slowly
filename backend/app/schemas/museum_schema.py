from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime


class MuseumItemCreate(BaseModel):
    item_type: str = Field(..., max_length=30)
    title: str = Field(..., min_length=1, max_length=200)
    story: Optional[str] = None
    image_url: Optional[str] = Field(None, max_length=500)
    source_id: Optional[int] = None
    source_type: Optional[str] = Field(None, max_length=30)


class MuseumItemUpdate(BaseModel):
    item_type: Optional[str] = Field(None, max_length=30)
    title: Optional[str] = Field(None, min_length=1, max_length=200)
    story: Optional[str] = None
    image_url: Optional[str] = Field(None, max_length=500)


class MuseumItemOut(BaseModel):
    id: int
    relation_id: int
    item_type: str
    title: str
    story: Optional[str] = None
    image_url: Optional[str] = None
    source_id: Optional[int] = None
    source_type: Optional[str] = None
    pinned: bool
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}
