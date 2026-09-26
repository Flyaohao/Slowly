from pydantic import BaseModel, Field
from typing import Optional
from datetime import date, datetime


class AnniversaryCreate(BaseModel):
    title: str = Field(..., min_length=1, max_length=200)
    anniversary_date: date
    # 契约 §8.8：默认每年重复（与存量数据语义一致）；false = 一次性
    repeat_annually: bool = True
    description: Optional[str] = None


class AnniversaryUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=200)
    anniversary_date: Optional[date] = None
    repeat_annually: Optional[bool] = None
    description: Optional[str] = None


class AnniversaryOut(BaseModel):
    id: int
    relation_id: int
    title: str
    anniversary_date: date
    repeat_annually: bool = True
    description: Optional[str] = None
    created_at: datetime

    # ---- §8.8：客户端**不需要**自己算日期语义 ----
    #
    # 「还有 55 天」这种数字一旦由两端各算一遍，迟早会出现首页说 55 天、
    # 列表说 57 天。这里由服务端一次算好下发：下次发生日 + 剩余天数，
    # 不会再发生（一次性且已过）时为 null。
    next_occurrence_date: Optional[date] = None
    days_until: Optional[int] = None

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
