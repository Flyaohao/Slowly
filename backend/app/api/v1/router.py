"""API v1 路由注册

按模式分组：
- common: 共享 API（auth, couples, questionnaire, profile, home）
- single: 历史命名的前缀，现仅承载「观点」（diary）。单身模式已于 2026-09-29 删除，
  但观点是情侣模式的功能，路由前缀保持不变（客户端已在用，且后端不校验模式）
- couple: 情侣模式专属 API（letters, ai, mediation, etc.）
"""

from fastapi import APIRouter

from app.api.v1.common.router import router as common_router
from app.api.v1.single.router import router as single_router
from app.api.v1.couple.router import router as couple_router
from app.api.v1 import admin

router = APIRouter(prefix="/api/v1")

# 共享 API
router.include_router(common_router)

# 观点 API（前缀是历史命名，与模式无关）
router.include_router(single_router, prefix="/single", tags=["观点"])

# 情侣模式专属 API
router.include_router(couple_router, prefix="/couple", tags=["情侣模式"])

# 管理 API
router.include_router(admin.router)
