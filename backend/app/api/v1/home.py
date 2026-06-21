from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.services import home_service

router = APIRouter(prefix="/home", tags=["首页"])


@router.get("", response_model=ApiResponse)
def get_home(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = home_service.get_home_data(db, current_user.id)
    except ValueError as e:
        code = str(e)
        error_map = {
            "30005": (400, "请先绑定情侣关系"),
            "10001": (400, "用户不存在"),
        }
        sc, msg = error_map.get(code, (400, "获取失败"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=data)
