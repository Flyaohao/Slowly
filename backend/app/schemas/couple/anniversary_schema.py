from pydantic import BaseModel, Field
from typing import Optional
from datetime import date, datetime


class AnniversaryCreate(BaseModel):
    title: str = Field(..., min_length=1, max_length=200)
    anniversary_date: date
    description: Optional[str] = None


class AnniversaryUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=200)
    anniversary_date: Optional[date] = None
    description: Optional[str] = None


class AnniversaryOut(BaseModel):
    id: int
    relation_id: int
    title: str
    anniversary_date: date
    description: Optional[str] = None
    created_at: datetime

    model_config = {"from_attributes": True}


class WishlistCreate(BaseModel):
    title: str = Field(..., min_length=1, max_length=200)
    description: Optional[str] = None


class WishlistUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=200)
    description: Optional[str] = None


class WishlistOut(BaseModel):
    id: int
    relation_id: int
    title: str
    description: Optional[str] = None
    status: str
    completed_at: Optional[datetime] = None
    created_at: datetime

    model_config = {"from_attributes": True}
