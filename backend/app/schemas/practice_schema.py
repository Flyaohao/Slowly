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


class PracticeRecordDetailOut(BaseModel):
    """练习记录详情：在基础字段上补充练习信息与双方各自的提交内容。"""

    id: int
    practice_id: int
    practice_title: str
    practice_type: str
    relation_id: int
    initiator_id: int
    status: str
    summary: Optional[str] = None
    my_submission: Optional[str] = None
    partner_submission: Optional[str] = None
    created_at: datetime
    updated_at: Optional[datetime] = None

    model_config = {"from_attributes": True}
