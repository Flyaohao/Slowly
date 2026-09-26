"""军师设置（契约 §3.3）请求模型。

GET → PUT 同构；PUT 按 ``exclude_unset`` 支持部分更新。
枚举非法值 → FastAPI 422 → 全局处理器归一化为 ``code=10002``。
"""
from typing import Literal, Optional

from pydantic import BaseModel, Field


class AdvisorSettingsRequest(BaseModel):
    #: 军师对用户的称呼；null=不修改（avatar_repo 跳过 None），""=清空
    address_name: Optional[str] = Field(default=None, max_length=50)
    #: 详细程度：brief | standard | detailed
    detail_level: Optional[Literal["brief", "standard", "detailed"]] = None
    #: 主动程度：passive | moderate | active
    proactivity: Optional[Literal["passive", "moderate", "active"]] = None
    #: 是否显示判断依据
    show_evidence: Optional[bool] = None
    #: 语气（复用 avatar.voice_style；旧值可能不在 5 选 1 内，只限长不收紧枚举）
    voice_style: Optional[str] = Field(default=None, max_length=30)
