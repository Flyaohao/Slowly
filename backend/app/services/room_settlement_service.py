"""共同调解室 · worker 侧任务：结算（调解书）与滚动摘要压缩。

为什么在 ai-task-worker 里跑（D-CHANNEL）：结算与压缩都是非交互的分钟级
LLM 任务；军师发言（交互式）才走 API 进程 SSE。worker 巡回入口
`ai_task_worker.run_once()` 会在每轮追加调用本模块的 `run_due_room_tasks`。

结算不搭 `ai_task` 队列：那张表的 `session_id` 是指向 `ai_chat_session`
的强外键，房间没有会话行；租约/重试语义在 `mediation_room` 行上自含
（claim/retry 见 `mediation_room_repo`），避免为搭车造锚定假会话。

结算一次事务完成（§七 + D-RESULT）：
  调解书落库 + 状态 settlement_ready + 吵架事件落库 + 双方观点落库
  （观点走 diary_entry，source='mediation_room'；D-OPINION 默认不计入画像，
  军师记忆开关由用户在观点页手动开启，这里不写 ai_memory）。

④「入队观察任务」属 P1（§十：观察系统联动打通），MVP 只落事件素材。
"""

import json
import logging
from datetime import datetime
from typing import List, Optional

from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.core.database import SessionLocal
from app.models.couple_relation import CoupleRelation
from app.models.diary_entry import DiaryEntry
from app.services.party_labels import NEUTRAL_A, NEUTRAL_B, party_labels
from app.models.mediation_room import (
    MediationEvent,
    MediationRoom,
    RESULT_COLD_WAR,
    RESULT_DEFERRED,
    RESULT_RECONCILED,
    RoomMessage,
)
from app.repositories import mediation_room_repo as room_repo
from app.services.llm_client import llm

logger = logging.getLogger("couple.room.settlement")

SCENE_SETTLE = "room_settlement"
SCENE_SUMMARY = "room_summary"

#: 结算 prompt 全文预算（worker 侧可以放宽，但仍要有界）
TRANSCRIPT_CHAR_LIMIT = 15000

_VALID_RESULTS = (RESULT_RECONCILED, RESULT_DEFERRED, RESULT_COLD_WAR)


class SettlementOutput(BaseModel):
    """调解书结构化输出。"""

    result: str = Field(description="结果判定，只能是：reconciled / deferred / cold_war")
    summary_text: str = Field(description="调解书正文：对本次事件的复盘与双方达成的理解")
    agreements: List[str] = Field(description="双方共同约定，每条一句可执行的话，2-5 条")
    responsibility_a: str = Field(description="user_a 当事人（称谓见对话记录标签）要做的责任与行动")
    responsibility_b: str = Field(description="user_b 当事人（称谓见对话记录标签）要做的责任与行动")
    viewpoint_a: str = Field(
        description="把 user_a 当事人在调解室里的发言压缩成一条第一人称观点（150 字内），"
        "只呈现其立场与感受，不加评判"
    )
    viewpoint_b: str = Field(
        description="把 user_b 当事人在调解室里的发言压缩成一条第一人称观点（150 字内），"
        "只呈现其立场与感受，不加评判"
    )


# --------------------------------------------------------------------------- #
# worker 巡回入口
# --------------------------------------------------------------------------- #

def run_due_room_tasks(db: Session, worker_id: str) -> int:
    """每轮 worker 巡回追加执行的房间任务。返回处理数量。"""
    done = 0
    if run_due_settlements(db, worker_id):
        done += 1
    if run_due_summaries(db):
        done += 1
    return done


def run_due_settlements(db: Session, worker_id: str) -> bool:
    room = room_repo.claim_settlement(db, worker_id)
    if room is None:
        return False
    try:
        _run_settlement(room.id)
        return True
    except Exception as exc:  # noqa: BLE001 —— 失败收口进退避，不拖垮 worker
        logger.warning(
            "[ROOM] 结算失败 room=%s attempt=%s: %s", room.id, room.settle_attempt, exc
        )
        _record_settlement_failure(room.id, "%s: %s" % (type(exc).__name__, exc))
        return False


def run_due_summaries(db: Session) -> bool:
    rooms = room_repo.rooms_needing_summary(db)
    for room in rooms:
        try:
            _run_summary(room)
            return True
        except Exception as exc:  # noqa: BLE001
            logger.warning("[ROOM] 摘要压缩失败 room=%s: %s", room.id, exc)
            # 清标记防抖：压缩失败不重试到死，下一轮窗口再次超限时重新置位
            _clear_summary_flag(room.id)
            return True
    return False


# --------------------------------------------------------------------------- #
# 结算
# --------------------------------------------------------------------------- #

def _run_settlement(room_id: int) -> None:
    """生成调解书 + 单事务写回。执行期间不持有事务（先 claim 已 commit）。"""
    db = SessionLocal()
    try:
        room = room_repo.get_room(db, room_id)
        if room is None or room.status != "settling":
            return
        relation = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.id == room.relation_id)
            .first()
        )
        transcript = _transcript(db, room_id, party_labels(db, relation))
        output = _invoke_settlement(room, transcript)

        now = datetime.utcnow()
        settlement_json = json.dumps(
            {
                "summary_text": output.summary_text.strip(),
                "agreements": [a.strip() for a in output.agreements if a.strip()][:5],
                "responsibilities": {
                    "user_a": output.responsibility_a.strip(),
                    "user_b": output.responsibility_b.strip(),
                },
                "generated_at": now.isoformat(),
            },
            ensure_ascii=False,
        )

        # ---- 单事务写回 ----
        room.settlement = settlement_json
        room.result = output.result if output.result in _VALID_RESULTS else RESULT_DEFERRED
        room.status = "settlement_ready"
        room.settle_locked_by = None
        room.settle_locked_until = None
        room.settle_next_retry_at = None
        room.settle_last_error = None

        db.add(
            MediationEvent(
                room_id=room.id,
                relation_id=room.relation_id,
                name=room.name,
                event_time=room.event_time,
                cause_text=room.cause_text,
                process_text=room.process_text,
                result=room.result,
                settled_at=None,  # 双方确认时回填
            )
        )
        # D-OPINION：观点落库默认不计入画像（enrich 由用户手动触发）、
        # 不写军师记忆（开关在观点页手动开）
        db.add(
            DiaryEntry(
                user_id=relation.user_a_id,
                title="调解室·%s" % room.name[:180],
                content=output.viewpoint_a.strip(),
                relation_id=room.relation_id,
                source="mediation_room",
            )
        )
        db.add(
            DiaryEntry(
                user_id=relation.user_b_id,
                title="调解室·%s" % room.name[:180],
                content=output.viewpoint_b.strip(),
                relation_id=room.relation_id,
                source="mediation_room",
            )
        )
        db.commit()
    finally:
        db.close()


def _invoke_settlement(room: MediationRoom, transcript: str) -> SettlementOutput:
    system = (
        "你是情侣双人调解室的军师。双方点击了「结束调解」，现在请你收拢全程对话，"
        "生成一份调解书。你必须中立方：判定结果、共同约定、双方各自责任，"
        "并把双方各自的发言压缩成观点（第一人称、不加评判）。"
        "\n\n结果判定说明：reconciled=双方已互相理解并达成约定；"
        "deferred=暂时搁置、约定择日再谈；cold_war=分歧仍在、进入冷战。"
        "按对话实际走向诚实判定，不粉饰。\n\n"
        "## 事件卡\n名称：%s\n时间：%s\n起因：%s\n经过：%s\n现状：%s\n\n"
        "## 房间全程对话\n%s" % (
            room.name, room.event_time, room.cause_text,
            room.process_text, room.current_text, transcript,
        )
    )
    messages = [
        {"role": "system", "content": system},
        {
            "role": "user",
            "content": "请生成调解书（结果判定 + 共同约定 + 双方责任 + 双方观点压缩）。",
        },
    ]
    return llm.invoke_structured(
        messages, output_model=SettlementOutput, scene=SCENE_SETTLE, max_tokens=2400
    )


def _transcript(db: Session, room_id: int, labels: Optional[dict] = None) -> str:
    """全文对话记录。labels 为 {"user_a": 称呼, "user_b": 称呼}；不传用中性称呼。"""
    labels = labels or {"user_a": NEUTRAL_A, "user_b": NEUTRAL_B}
    rows = (
        db.query(RoomMessage)
        .filter(RoomMessage.room_id == room_id)
        .order_by(RoomMessage.id.asc())
        .all()
    )
    lines = []
    for m in rows:
        who = {
            "user_a": labels["user_a"],
            "user_b": labels["user_b"],
            "advisor": "军师",
        }.get(m.sender_type, m.sender_type)
        lines.append("[%s] %s" % (who, m.content))
    text = "\n".join(lines)
    if len(text) > TRANSCRIPT_CHAR_LIMIT:
        text = "(…更早内容略…)\n" + text[-TRANSCRIPT_CHAR_LIMIT:]
    return text


def _record_settlement_failure(room_id: int, error: str) -> None:
    db = SessionLocal()
    try:
        room_repo.settle_retry_or_fail(db, room_id, error)
    finally:
        db.close()


# --------------------------------------------------------------------------- #
# 滚动摘要压缩（§四：军师历史发言超阈值并入滚动摘要）
# --------------------------------------------------------------------------- #

#: 单次压缩并入的消息数上限
SUMMARY_BATCH = 40


def _run_summary(room: MediationRoom) -> None:
    db = SessionLocal()
    try:
        fresh = room_repo.get_room(db, room.id)
        if fresh is None or not fresh.summary_pending:
            return
        upto = int(fresh.summary_upto_message_id or 0)
        msgs = (
            db.query(RoomMessage)
            .filter(RoomMessage.room_id == room.id, RoomMessage.id > upto)
            .order_by(RoomMessage.id.asc())
            .limit(SUMMARY_BATCH)
            .all()
        )
        if len(msgs) < SUMMARY_BATCH:
            # 窗口还没攒满一批：等下一次（保持标记，避免频繁小压缩）
            return
        relation = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.id == fresh.relation_id)
            .first()
        )
        labels = party_labels(db, relation) if relation else None
        labels = labels or {"user_a": NEUTRAL_A, "user_b": NEUTRAL_B}
        new_text = "\n".join(
            "[%s] %s" % (
                {
                    "user_a": labels["user_a"],
                    "user_b": labels["user_b"],
                    "advisor": "军师",
                }.get(m.sender_type, m.sender_type),
                m.content,
            )
            for m in msgs
        )
        old_summary = (fresh.advisor_summary or "").strip()
        prompt = (
            "你是军师的记录员。把下面这段调解室对话压缩成一段不超过 300 字的滚动摘要，"
            "保留：双方的核心诉求、已达成的共识、仍未解决的分歧、军师给过的关键建议。"
            "只输出摘要正文。\n\n"
            + ("已有摘要（请把新内容并进去，不要丢失旧要点）：\n%s\n\n" % old_summary
               if old_summary else "")
            + "新对话：\n" + new_text
        )
        summary = llm.invoke(
            [{"role": "user", "content": prompt}],
            scene=SCENE_SUMMARY,
            max_tokens=800,
        ).strip()
        if summary:
            room_repo.update_summary(
                db, room.id, summary=summary, upto_message_id=msgs[-1].id
            )
    finally:
        db.close()


def _clear_summary_flag(room_id: int) -> None:
    db = SessionLocal()
    try:
        db.query(MediationRoom).filter(MediationRoom.id == room_id).update(
            {"summary_pending": False}
        )
        db.commit()
    finally:
        db.close()
