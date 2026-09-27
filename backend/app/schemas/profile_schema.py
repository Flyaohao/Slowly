"""画像版本管理与观点补充的请求模型（用户需求 #5）。"""
from typing import Dict, List, Optional

from pydantic import BaseModel, Field


class EnrichDimensionSpec(BaseModel):
    """某一维度的调整方向。**刻意不收分数**——AI 只回答方向与强度。

    让模型直接给分数，等于把「移多少」交给一次生成；方向 + 强度则把「移多少」
    留给服务端的幅度约束（单次 ≤12、累计 ≤20），AI 无法通过一次输出掀桌。
    """

    direction: str = Field(..., description="up（该项更强）/ down（该项更弱）")
    strength: Optional[str] = Field("mild", description="mild / moderate")


class EnrichProfileRequest(BaseModel):
    viewpoint_id: int = Field(..., description="来源观点（diary_entry.id）")
    dimensions: List[str] = Field(default_factory=list, description="要调整的维度 key")
    summary: str = Field("", description="观点摘要，写入 explanation 作为依据")
    directions: Dict[str, EnrichDimensionSpec] = Field(default_factory=dict)
    confidence: float = Field(0.0, ge=0.0, le=1.0, description="AI 分析的置信度")


class ManualVersionRequest(BaseModel):
    note: Optional[str] = Field(None, max_length=200, description="版本说明")
