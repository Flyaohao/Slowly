from pydantic import BaseModel, Field
from typing import Optional, List


class CreateDiaryRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=200)
    content: str = Field(..., min_length=1)
    mood: Optional[str] = Field(None, max_length=50)
    weather: Optional[str] = Field(None, max_length=50)


class UpdateDiaryRequest(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=200)
    content: Optional[str] = Field(None, min_length=1)
    mood: Optional[str] = Field(None, max_length=50)
    weather: Optional[str] = Field(None, max_length=50)


class DiaryResponse(BaseModel):
    id: int
    title: str
    content: str
    mood: Optional[str] = None
    weather: Optional[str] = None
    is_favorite: bool = False
    created_at: Optional[str] = None
    updated_at: Optional[str] = None


class DiaryListResponse(BaseModel):
    items: List[DiaryResponse]
    total: int


class BatchDeleteRequest(BaseModel):
    ids: List[int]
