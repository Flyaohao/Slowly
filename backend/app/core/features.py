"""收敛期端点冻结框架（契约 §1）。

收敛期的总则是「隐藏 ≠ 删除」：路由、页面、模型都还在，但服务端对已裁决下线的
功能统一返回业务错误 `code=10006`（新增通用 1xxxx 段），message「该功能已停用」。
这样旧 APK 调用得到的是明确的业务错误，而不是被静默返回的错数据或空数据。

用法（模块级整表冻结）::

    router = APIRouter(
        prefix="/museum",
        dependencies=[Depends(require_feature("museum"))],
    )

用法（单条路由冻结，模块内有保留端点时）::

    @router.post("/presence/moment",
                 dependencies=[Depends(require_feature("presence"))])

W6 真正删除时：整个模块连同它的 `require_feature` 依赖一起摘除即可——
`FEATURE_FLAGS` 里的条目是唯一需要同步修改的地方。

信件是按**类型**冻结而不是整模块冻结（§1「按类型冻结」行），
用 `assert_letter_type_allowed`，见 `app.services.letter_service`。
"""

from typing import Callable

from fastapi import HTTPException

#: 冻结业务码（契约 §0-3 / §0-6）
FEATURE_DISABLED_CODE = 10006
FEATURE_DISABLED_MESSAGE = "该功能已停用"

#: 按类型冻结的信件类型（契约 §2.5-1）。
#: 只拒 future / private——现有前端还会发 unsaid / calm，信件主体必须保留。
FROZEN_LETTER_TYPES = frozenset({"future", "private"})

#: 功能开关表。False = 冻结（返回 10006）。
#: 名单与契约 §1 裁决表一一对应；未登记的名字会在路由注册时就抛错，
#: 避免拼写错误把冻结悄悄变成放行。
FEATURE_FLAGS: dict = {
    "museum": False,          # §1 纪念馆 7 个
    "wishlists": False,       # §1 愿望清单 5 个
    "presence": False,         # §1 异地陪伴 3 个（meet-date 保留，不挂本依赖）
    "self_practices": False,  # §1 自我练习 5 个
    "practices": False,       # §1 关系练习 5 个
    "memory_card": False,     # §1 AI 回忆卡
    "practice_summary": False,  # §1 练习 AI 摘要（随练习模块）
    # §1 AI 形象（捏脸）：appearance 字段 + GET /avatars/assets 冻结；
    # name / voice-style 保留并升级为军师设置存储（§3.3），不随本开关冻结。
    "avatar_appearance": False,
}


def feature_disabled_error() -> HTTPException:
    """冻结业务错误。

    `status_code=400` + 字典 detail：经 `app/main.py` 的统一处理器归一化成
    **HTTP 200 + {code:10006, message:"该功能已停用"}**，与信封约定一致。
    """
    return HTTPException(
        status_code=400,
        detail={
            "code": FEATURE_DISABLED_CODE,
            "message": FEATURE_DISABLED_MESSAGE,
            "data": None,
        },
    )


def require_feature(feature: str) -> Callable:
    """返回一个 FastAPI 依赖：功能被冻结时抛 10006，否则放行。

    未在 `FEATURE_FLAGS` 登记的功能名**在创建依赖时**（即路由模块 import 时）
    直接 `ValueError`——宁可启动即炸，也不让一个拼错的名字静默放行。
    """
    if feature not in FEATURE_FLAGS:
        raise ValueError(
            f"未登记的功能名 {feature!r}：请先在 app.core.features.FEATURE_FLAGS 登记"
        )

    def dependency() -> None:
        if not FEATURE_FLAGS[feature]:
            raise feature_disabled_error()

    return dependency
