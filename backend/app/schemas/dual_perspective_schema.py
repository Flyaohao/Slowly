from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime


class DualPerspectiveEventCreate(BaseModel):
    title: str = Field(..., min_length=1, max_length=200)
    event_time: datetime


class DualPerspectiveRecordSubmit(BaseModel):
    content: str = Field(..., min_length=1)
    visibility: str = Field(default="hidden", max_length=20)


class DualPerspectiveRecordUpdate(BaseModel):
    content: Optional[str] = Field(None, min_length=1)
    visibility: Optional[str] = Field(None, max_length=20)


class DualPerspectiveRecordOut(BaseModel):
    id: int
    event_id: int
    user_id: int
    content: str
    visibility: str
    created_at: datetime

    model_config = {"from_attributes": True}


class DualPerspectiveEventOut(BaseModel):
    id: int
    relation_id: int
    title: str
    event_time: datetime
    status: str
    created_at: datetime
    updated_at: datetime
    records: List[DualPerspectiveRecordOut] = []

    model_config = {"from_attributes": True}


class DualPerspectiveEventListOut(BaseModel):
    id: int
    relation_id: int
    title: str
    event_time: datetime
    status: str
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}
