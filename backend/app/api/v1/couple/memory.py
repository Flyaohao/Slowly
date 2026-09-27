from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime
import logging

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.services import memory_service
from app.repositories import couple_repo
from app.repositories.single import diary_repo
from app.models.diary_entry import DiaryEntry

logger = logging.getLogger("couple.memory")

router = APIRouter(prefix="/ai/memory", tags=["AI 记忆"])


@router.get("", response_model=ApiResponse)
def get_memories(
    relation_id: Optional[int] = Query(None),
    memory_type: Optional[str] = Query(None),
    source: Optional[str] = Query(None),
    importance: Optional[int] = Query(None),
    since: Optional[datetime] = Query(None),
    until: Optional[datetime] = Query(None),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    # P-C3 §4.1：筛选参数只加不删，全部可选（不传 = 旧行为）
    memories = memory_service.get_memories(
        db, current_user.id, relation_id, memory_type,
        source=source, importance=importance, since=since, until=until,
    )
    return ApiResponse(data=memories)


@router.get("/couple/{relation_id}", response_model=ApiResponse)
def get_couple_memories(
    relation_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    # IDOR 守卫（v3.2 附录 §5.2）：非关系成员一律 403，不区分「关系不存在」
    try:
        memories = memory_service.get_couple_memories(
            db, current_user.id, relation_id
        )
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权访问该关系的记忆", "data": None},
        )
    return ApiResponse(data=memories)


@router.put("/{memory_id}/visibility", response_model=ApiResponse)
def update_visibility(
    memory_id: int,
    body: dict,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    visibility = body.get("visibility", "private")
    if visibility not in ("private", "couple"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40001, "message": "无效的可见性", "data": None},
        )
    try:
        result = memory_service.update_visibility(db, memory_id, current_user.id, visibility)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权操作此记忆", "data": None},
        )
    return ApiResponse(data=result)


@router.put("/{memory_id}/importance", response_model=ApiResponse)
def update_importance(
    memory_id: int,
    body: dict,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """标星/取消标星（P-C3 §4.2）。只接受 2（标星）/ 0（取消）。"""
    importance = body.get("importance", 0)
    if importance not in (0, 2):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40001, "message": "importance 只能是 0 或 2", "data": None},
        )
    try:
        result = memory_service.update_importance(db, memory_id, current_user.id, importance)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权操作此记忆", "data": None},
        )
    return ApiResponse(data=result)


@router.delete("/{memory_id}", response_model=ApiResponse)
def delete_memory(
    memory_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        memory_service.delete_memory(db, memory_id, current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权删除此记忆", "data": None},
        )
    return ApiResponse()


# ============================================================
# 观点 → 军师记忆 开关（2026-09-27 用户裁决）
#
# 背景：写观点时 `create_diary` 已经会走 `write_diary_memory` 自动蒸馏一条记忆
# ——那是「AI 判断值不值得记」。本节给的是**用户自己的决定权**。
#
# 为什么计入时不复用那条蒸馏链路：蒸馏带 `should_remember` 闸门，用户明确点了
# 「记住这件事」却被模型判成不值得记，开关会自己弹回去，等于这个开关是假的。
# 用户主动选择应当被尊重，所以这里直接落一条断言（类型取 AI 建议、服务端校验
# 白名单），并带上 source/source_id —— 保证可查、可撤、可溯源。
# ============================================================


def _safe_memory_type(value: Optional[str]) -> str:
    """模型给的类型必须落在白名单里；不在就退回「核心诉求」。

    和 `dimensions` 同一个理由：`MEMORY_TYPES` 是产品配置，模型自造的类型会让
    记忆页出现没有标签的孤儿条目。
    """
    return value if value in memory_service.MEMORY_TYPES else "核心诉求"


def _compose_memory_text(entry: DiaryEntry) -> str:
    """标题 + 正文压成一条记忆文本，控制在 500 字内（记忆不是原文仓库）。"""
    title = (entry.title or "").strip()
    content = (entry.content or "").strip()
    if title and content:
        text = "%s：%s" % (title, content)
    else:
        text = title or content
    return text[:500]


@router.get("/viewpoint/{diary_id}", response_model=ApiResponse)
def get_viewpoint_memory(
    diary_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """这条观点有没有进军师记忆——详情页开关的初始状态。"""
    return ApiResponse(
        data=memory_service.get_memories_by_source(
            db, current_user.id, "diary", diary_id
        )
    )


@router.post("/viewpoint/{diary_id}", response_model=ApiResponse)
def link_viewpoint_memory(
    diary_id: int,
    body: Optional[dict] = None,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """用户选择「计入军师记忆」。幂等：已有记忆直接返回，不重复写。"""
    existing = memory_service.get_memories_by_source(
        db, current_user.id, "diary", diary_id
    )
    if existing:
        return ApiResponse(data=existing)

    entry = diary_repo.get_by_id(db, diary_id, current_user.id)
    if entry is None:
        return ApiResponse(code=60001, message="观点不存在", data=None)

    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if relation is None:
        # 单身模式没有军师记忆（`ai_memory.relation_id` 是非空外键）。
        # 客户端的开关本来就只在情侣模式出现，这里是服务端兜底。
        return ApiResponse(
            code=30005, message="还没绑定情侣关系，暂时没有军师记忆", data=None
        )

    text = _compose_memory_text(entry)
    if not text:
        return ApiResponse(code=40001, message="这条观点没有可记录的内容", data=None)

    try:
        saved = memory_service.create_memory(
            db,
            current_user.id,
            relation.id,
            memory_type=_safe_memory_type((body or {}).get("memory_type")),
            memory_text=text,
            # 隐私默认仅自己可见：观点是个人资产，要不要让伴侣看到应由用户
            # 在记忆页单独决定，绝不能因为「写了一条观点」就默认共享出去。
            visibility="private",
            occurred_at=entry.created_at,
            source="diary",
            source_id=entry.id,
            importance=0,
        )
    except Exception:
        db.rollback()
        logger.exception("[MEMORY] 观点计入记忆失败 diary_id=%s", diary_id)
        return ApiResponse(code=50001, message="写入记忆失败，可以稍后再试", data=None)

    return ApiResponse(data=[saved])


@router.delete("/viewpoint/{diary_id}", response_model=ApiResponse)
def unlink_viewpoint_memory(
    diary_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """用户选择「不计入军师记忆」：撤除这条观点产生的记忆。"""
    deleted = memory_service.delete_memories_by_source(
        db, current_user.id, "diary", diary_id
    )
    return ApiResponse(data={"deleted": deleted})
