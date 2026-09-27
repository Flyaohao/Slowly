# -*- coding: utf-8 -*-
"""军师观察卡（V1 聚合版，《军师主动观察》设计文档 2026-09-28 §十）。

V1 **无新 LLM 调用**：观察正文由既有数据拼装——
1. 最近一次已结算的共同调解室调解书（最高价值素材，设计文档 §九）；
2. 关系画像摘要（GET /profiles/couple 的 summary，即客户端的 coupleSummary）。

有 1 用 1（引用调解书名，前端按 F-3 只展示来源文字不跳转）；
两者皆无 → content=None，前端渲染冷启动引导语。

「有新」判定（决策⑥：已读走服务端 ack，角标跨设备）：
当前拼装内容的 md5 签名 ≠ couple_relation.observation_read_key。
ack 把签名写回。V2 事件驱动生成（任务调度 + 水位线 + prompt 组装）
独立排期，届时该列语义升级为 last_read_observation_id。

红线：只用 couple visibility 数据；画像摘要是关系级字段，天然合规。
"""
import hashlib
import json
from datetime import datetime
from typing import Optional

from sqlalchemy.orm import Session

from app.models.mediation_room import MediationRoom, RESULT_LABELS, ROOM_SETTLED
from app.repositories import couple_repo, profile_repo

#: 未绑定情侣关系（与 profiles / relationship-events 同码）
ERR_NO_RELATION = "30005"
#: ack 参数不合法
ERR_BAD_SIGNATURE = "100001"

#: 观察正文上限（设计文档 §五：观察正文 ≤120 字）
_MAX_CONTENT_LEN = 120


def _latest_settled_room(db: Session, relation_id: int) -> Optional[MediationRoom]:
    return (
        db.query(MediationRoom)
        .filter(
            MediationRoom.relation_id == relation_id,
            MediationRoom.status == ROOM_SETTLED,
        )
        .order_by(MediationRoom.settled_at.desc(), MediationRoom.id.desc())
        .first()
    )


def _trim(text: str) -> str:
    text = text.strip()
    if len(text) > _MAX_CONTENT_LEN:
        return text[: _MAX_CONTENT_LEN - 1] + "…"
    return text


def _settlement_fields(room: MediationRoom) -> tuple[list, str]:
    """解析调解书 JSON →（约定条数用的列表, 军师一句话）。坏 JSON 不炸接口。"""
    if not room.settlement:
        return [], ""
    try:
        data = json.loads(room.settlement)
    except (TypeError, ValueError):
        return [], ""
    if not isinstance(data, dict):
        return [], ""
    agreements = data.get("agreements") or []
    if not isinstance(agreements, list):
        agreements = []
    return agreements, str(data.get("summary_text") or "").strip()


def _assemble(
    db: Session, relation
) -> tuple[Optional[str], Optional[dict], Optional[datetime]]:
    """拼装观察正文。返回 (content, citation, observed_at)，无素材时全 None。"""
    room = _latest_settled_room(db, relation.id)
    if room is not None:
        label = RESULT_LABELS.get(room.result, room.result or "")
        agreements, summary_text = _settlement_fields(room)
        head = "上次关于「%s」的调解以%s收场" % (room.name, label)
        if agreements:
            head += "，定下了 %d 条约定" % len(agreements)
        head += "。"
        content = _trim(head + summary_text)
        citation = {"type": "mediation_room", "id": room.id, "title": room.name}
        return content, citation, room.settled_at or room.updated_at

    cp = profile_repo.get_latest_couple_profile(db, relation.id)
    if cp is not None and (cp.summary or "").strip():
        content = _trim("军师目前对你们关系的理解：" + cp.summary)
        return content, None, cp.created_at

    return None, None, None


def _signature(content: str) -> str:
    return hashlib.md5(content.encode("utf-8")).hexdigest()


def get_observation(db: Session, user_id: int) -> dict:
    """观察卡聚合查询。业务错误抛 ValueError（api 层转信封）。"""
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if relation is None:
        raise ValueError(ERR_NO_RELATION)

    content, citation, observed_at = _assemble(db, relation)
    if content is None:
        # 冷启动：没有任何可拼装素材，前端显示引导语，无「新」可言
        return {
            "content": None,
            "observed_at": None,
            "citation": None,
            "signature": None,
            "has_new": False,
        }

    signature = _signature(content)
    return {
        "content": content,
        "observed_at": observed_at.isoformat() if observed_at else None,
        "citation": citation,
        "signature": signature,
        "has_new": signature != relation.observation_read_key,
    }


def ack_observation(db: Session, user_id: int, signature: str) -> None:
    """已读上报（决策⑥）。写 couple_relation.observation_read_key。"""
    if not signature or not signature.strip() or len(signature) > 64:
        raise ValueError(ERR_BAD_SIGNATURE)
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if relation is None:
        raise ValueError(ERR_NO_RELATION)
    relation.observation_read_key = signature.strip()
    db.commit()
