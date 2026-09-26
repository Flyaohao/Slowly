"""军师设置 API（契约 §3.3）。

放在 common 前缀下（单双模式通用）——couple 前缀对单人模式语义不符；
存储逻辑与单/双模式的取舍见 ``advisor_service`` 模块 docstring。
"""
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.advisor_schema import AdvisorSettingsRequest
from app.schemas.common import ApiResponse
from app.services import advisor_service

router = APIRouter(prefix="/advisor", tags=["军师"])


@router.get("/settings", response_model=ApiResponse)
def get_advisor_settings(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """读取军师设置。单人模式返回默认值（GET → PUT 同构，见 service）。"""
    return ApiResponse(data=advisor_service.get_settings(db, current_user.id))


@router.put("/settings", response_model=ApiResponse)
def update_advisor_settings(
    req: AdvisorSettingsRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """更新军师设置（部分更新）。枚举非法 → 422 → 10002；无关系 → 30005。"""
    data = req.model_dump(exclude_unset=True)
    if not data:
        # 空 PUT：不写库，回读当前值（幂等）
        return ApiResponse(data=advisor_service.get_settings(db, current_user.id))
    try:
        return ApiResponse(
            data=advisor_service.update_settings(db, current_user.id, data)
        )
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 30005, "message": "无权操作此关系", "data": None},
        )
