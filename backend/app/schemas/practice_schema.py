from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime


class PracticeOut(BaseModel):
    id: int
    title: str
    practice_type: str
    description: str

    model_config = {"from_attributes": True}


class PracticeRecordCreate(BaseModel):
    pass


class PracticeRecordSubmit(BaseModel):
    content: str = Field(..., min_length=1)


class PracticeRecordOut(BaseModel):
    id: int
    practice_id: int
    relation_id: int
    initiator_id: int
    status: str
    summary: Optional[str] = None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}
