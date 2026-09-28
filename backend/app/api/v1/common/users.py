from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.user_schema import (
    UserProfileUpdateRequest,
    PrivatePasswordRequest,
    PrivateVerifyRequest,
    NotificationPrefUpdateRequest,
)
from app.schemas.user_schema import (
    AiConfigSaveRequest,
    AiConfigTestRequest,
    AiKeyAddRequest,
    AiKeyToggleRequest,
)
from app.services import user_service
from app.services import user_ai_config_service as uaicfg

router = APIRouter(prefix="/users", tags=["用户"])


@router.get("/me", response_model=ApiResponse)
def get_me(current_user=Depends(get_current_user), db: Session = Depends(get_db)):
    try:
        profile = user_service.get_profile(db, current_user.id)
    except ValueError:
        return ApiResponse(code=10002, message="用户信息不存在", data=None)
    return ApiResponse(data=profile)


@router.put("/me", response_model=ApiResponse)
def update_me(
    req: UserProfileUpdateRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    data = req.model_dump(exclude_unset=True)
    try:
        profile = user_service.update_profile(db, current_user.id, data)
    except ValueError:
        return ApiResponse(code=10002, message="更新失败", data=None)
    return ApiResponse(data=profile)


@router.post("/me/avatar", response_model=ApiResponse)
async def upload_avatar(
    file: UploadFile = File(...),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    content = await file.read()
    if len(content) > 5 * 1024 * 1024:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 10003, "message": "文件大小超过限制", "data": None},
        )
    try:
        avatar_url = user_service.upload_avatar(db, current_user.id, content, file.filename)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 10003, "message": "不支持的文件类型", "data": None},
        )
    return ApiResponse(data={"avatar_url": avatar_url})


@router.get("/me/notification-pref", response_model=ApiResponse)
def get_notification_pref(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """读邮件通知开关。默认关闭——新用户没有任何额外通知。"""
    try:
        data = user_service.get_notification_pref(db, current_user.id)
    except ValueError:
        return ApiResponse(code=10002, message="用户信息不存在", data=None)
    return ApiResponse(data=data)


@router.put("/me/notification-pref", response_model=ApiResponse)
def update_notification_pref(
    req: NotificationPrefUpdateRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """开关邮件通知。只影响「是否发提醒邮件」，不影响 App 内的 WS 实时通知。"""
    try:
        data = user_service.set_notification_pref(
            db, current_user.id, req.email_notify_enabled
        )
    except ValueError:
        return ApiResponse(code=10002, message="更新失败", data=None)
    return ApiResponse(data=data)


@router.post("/me/private-password", response_model=ApiResponse)
def set_private_password(
    req: PrivatePasswordRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    user_service.set_private_password(db, current_user.id, req.password)
    return ApiResponse()


@router.post("/me/private-verify", response_model=ApiResponse)
def verify_private_password(
    req: PrivateVerifyRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        token = user_service.verify_private_password(db, current_user.id, req.password)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": 10005, "message": "密码错误", "data": None},
        )
    return ApiResponse(data={"private_token": token})


# --------------------------------------------------------------------------- #
# v5.0 用户级 AI 服务配置（设置页「AI 服务配置」）
#
# 错误约定：未配置 → 30010（全局处理器兜底）、配置非法/测试未过 → 30011
# （AiConfigInvalidError 全局处理器兜底，message 透传探测原因）。
# key 明文只进不出：读取一律打码。
# --------------------------------------------------------------------------- #
@router.get("/me/ai-config", response_model=ApiResponse)
def get_ai_config(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """读取当前用户 AI 配置（key 打码）。未配置时 data=null。"""
    return ApiResponse(data=uaicfg.masked_payload(db, current_user.id))


@router.put("/me/ai-config", response_model=ApiResponse)
def save_ai_config(
    req: AiConfigSaveRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """保存配置。保存前强制「对话 + Embedding」双连通性测试（D10）。"""
    uaicfg.test_and_save_config(
        db,
        current_user.id,
        provider_type=req.provider_type,
        base_url=req.base_url,
        model_name=req.model_name,
        embedding_base_url=req.embedding_base_url,
        embedding_model=req.embedding_model,
        embedding_api_key=req.embedding_api_key,
        enable_rate_limit=req.enable_rate_limit,
        new_keys=req.new_keys,
    )
    return ApiResponse(data=uaicfg.masked_payload(db, current_user.id))


@router.delete("/me/ai-config", response_model=ApiResponse)
def clear_ai_config(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """清空配置。清空后所有 AI 功能即刻不可用（D4 强制配置）。"""
    uaicfg.clear_config(db, current_user.id)
    return ApiResponse()


@router.post("/me/ai-config/test", response_model=ApiResponse)
def test_ai_config(
    req: AiConfigTestRequest,
    current_user=Depends(get_current_user),
):
    """只测不存：对话 + Embedding 双探测，通过返回实测向量维度。"""
    dim = uaicfg.test_config(
        provider_type=req.provider_type,
        base_url=req.base_url,
        model_name=req.model_name,
        embedding_base_url=req.embedding_base_url,
        embedding_model=req.embedding_model,
        embedding_api_key=req.embedding_api_key,
        keys=req.keys,
    )
    return ApiResponse(data={"ok": True, "embedding_dim": dim})


@router.post("/me/ai-config/keys", response_model=ApiResponse)
def add_ai_key(
    req: AiKeyAddRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """新增一把 key（即时探测通过才入库，D8 轮换）。"""
    item = uaicfg.add_key(db, current_user.id, req.key, req.label)
    return ApiResponse(data=item)


@router.patch("/me/ai-config/keys/{key_id}", response_model=ApiResponse)
def toggle_ai_key(
    key_id: int,
    req: AiKeyToggleRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """启用/停用一把 key（停用后轮换会跳过它）。"""
    uaicfg.set_key_enabled(db, current_user.id, key_id, req.enabled)
    return ApiResponse()


@router.delete("/me/ai-config/keys/{key_id}", response_model=ApiResponse)
def delete_ai_key(
    key_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """删除一把 key；最后一把被删 = 整份配置一并清掉（避免半死状态）。"""
    uaicfg.delete_key(db, current_user.id, key_id)
    return ApiResponse()
