"""军师观察卡 API（V1 聚合版，《军师主动观察》设计文档 2026-09-28 §七）。

挂在 /api/v1/couple 下（实际路径 /api/v1/couple/observation）。
业务错误走 HTTP 200 信封（code 非 0）；未登录仍走 401。
"""
from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.services import observation_service

router = APIRouter(prefix="/observation", tags=["军师观察卡"])


class ObservationAckRequest(BaseModel):
    signature: str


def _error(e: ValueError) -> ApiResponse:
    """统一的业务错误映射（与其它 couple 模块同形）。"""
    code = str(e)
    if code == observation_service.ERR_NO_RELATION:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    if code == observation_service.ERR_BAD_SIGNATURE:
        return ApiResponse(code=100001, message="观察签名不合法", data=None)
    return ApiResponse(code=100001, message="操作失败", data=None)


@router.get("", response_model=ApiResponse)
def get_observation(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """最新观察 + has_new（对比服务端已读位）。无素材时 content=None（冷启动）。"""
    try:
        data = observation_service.get_observation(db, current_user.id)
    except ValueError as e:
        return _error(e)
    return ApiResponse(data=data)


@router.post("/ack", response_model=ApiResponse)
def ack_observation(
    req: ObservationAckRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """已读上报（决策⑥）：写 last read 签名，角标跨设备清零。"""
    try:
        observation_service.ack_observation(db, current_user.id, req.signature)
    except ValueError as e:
        return _error(e)
    return ApiResponse()
