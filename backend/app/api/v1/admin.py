"""管理后台 API（定时任务触发等）"""

import hmac
import os

from fastapi import APIRouter, Depends, Header, HTTPException

from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.tasks.unbinding_timeout import check_unbinding_timeout

router = APIRouter(prefix="/admin", tags=["管理"])

#: 契约 §2.6-4：内部口令。从环境变量读取（不落代码）；
#: **未配置时端点一律 403**——宁可不可用，也不可被任意登录用户触发。
ADMIN_TASK_TOKEN = os.getenv("ADMIN_TASK_TOKEN", "")


@router.post("/tasks/check-unbinding-timeout", response_model=ApiResponse)
def trigger_unbinding_timeout_check(
    x_admin_token: str = Header(default="", alias="X-Admin-Token"),
    current_user=Depends(get_current_user),
):
    """手动触发冷静期超时检查（管理员内部口令）。

    原实现只挂 ``get_current_user``——任何登录用户都能触发（§2.6-4 缺口）。
    最简可行方案（按契约允许的「内部口令」路线）：在登录之上再要求
    ``X-Admin-Token`` 与环境变量 ``ADMIN_TASK_TOKEN`` 一致；
    口令缺失或不匹配 → 403（401/403 保持原样透传，不进业务码归一化）。
    """
    if not ADMIN_TASK_TOKEN or not hmac.compare_digest(x_admin_token, ADMIN_TASK_TOKEN):
        raise HTTPException(
            status_code=403,
            detail={"code": 10004, "message": "无管理权限", "data": None},
        )
    try:
        check_unbinding_timeout()
    except Exception as e:
        raise HTTPException(status_code=500, detail={"code": 10000, "message": str(e), "data": None})
    return ApiResponse(message="冷静期超时检查已完成")
