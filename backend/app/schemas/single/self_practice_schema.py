from pydantic import BaseModel
from typing import Optional, List


class SelfPracticeResponse(BaseModel):
    id: int
    title: str
    practice_type: str
    description: str
    guidance: Optional[str] = None


class SelfPracticeRecordResponse(BaseModel):
    id: int
    practice_id: int
    user_id: int
    status: str
    content: Optional[str] = None
    reflection: Optional[str] = None
    created_at: Optional[str] = None
    updated_at: Optional[str] = None


class SubmitSelfPracticeRequest(BaseModel):
    content: Optional[str] = None
    reflection: Optional[str] = None


class SelfPracticeRecordListResponse(BaseModel):
    items: List[SelfPracticeRecordResponse]
    total: int
    page: int
    page_size: int
