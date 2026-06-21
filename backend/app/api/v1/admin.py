"""管理后台 API（定时任务触发等）"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.tasks.unbinding_timeout import check_unbinding_timeout

router = APIRouter(prefix="/admin", tags=["管理"])


@router.post("/tasks/check-unbinding-timeout", response_model=ApiResponse)
def trigger_unbinding_timeout_check(
    current_user=Depends(get_current_user),
):
    """手动触发冷静期超时检查（仅管理员使用）"""
    try:
        check_unbinding_timeout()
    except Exception as e:
        raise HTTPException(status_code=500, detail={"code": 10000, "message": str(e), "data": None})
    return ApiResponse(message="冷静期超时检查已完成")
