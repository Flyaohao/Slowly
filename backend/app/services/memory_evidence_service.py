"""证据（v3.2 附录 §1.3 / §7.2）：attach、计数、evidence_lost。

口径（附录 §7.2，逐字）：
- offset/length 按 **Unicode 码点**，计算前文本先 `\r\n→\n` 归一（复用
  `memory_fingerprint.normalize_evidence_text`——只归一行结束符，不折叠）。
- `evidence_count` 按 `(source_type, source_id)` 去重；同一来源不同 revision 算 1。
- `reporter_count` 按源证据**实际作者** user_id 去重；不可解源不计数。
- 唯一键冲突 no-op（uk_memory_evidence）。
"""
import logging
from typing import Callable, Dict, Optional, Tuple

from sqlalchemy.exc import IntegrityError

from app.services.memory_fingerprint import (
    normalize_evidence_text,
    sha256_hex,
)

logger = logging.getLogger("couple.memory.evidence")


def content_hash(text: str) -> str:
    """归一化后内容的 SHA-256（展示「为什么记住」时回读校验）。"""
    return sha256_hex(normalize_evidence_text(text))


def full_span(text: str) -> Tuple[int, int, str]:
    """全跨度简化证据（D6）→ `(offset, length, content_hash)`。

    length 用归一后文本的码点数；offset 恒 0。
    """
    normalized = normalize_evidence_text(text)
    return 0, len(normalized), sha256_hex(normalized)


def _chat_message_reporter(db, source_id: int, revision: int) -> Optional[int]:
    """chat → 消息所属 session.user_id（Stage A：session 即证据作者）。"""
    from app.models.ai import AiChatMessage, AiChatSession

    msg = (
        db.query(AiChatMessage.session_id)
        .filter(AiChatMessage.id == source_id)
        .first()
    )
    if msg is None:
        return None
    sess = (
        db.query(AiChatSession.user_id)
        .filter(AiChatSession.id == msg.session_id)
        .first()
    )
    return sess[0] if sess else None


#: source_type → 可解作者的 resolver；未列入的源**不计入** reporter_count
SOURCE_REPORTER_RESOLVERS: Dict[str, Callable] = {
    "chat_message": _chat_message_reporter,
}


def attach_evidence(
    db,
    *,
    assertion_id: int,
    source_type: str,
    source_id: int,
    source_revision_id: int = 1,
    offset_codepoint: Optional[int] = None,
    length_codepoint: Optional[int] = None,
    text: Optional[str] = None,
    hash_hex: Optional[str] = None,
    evidence_role: str = "support",
    commit: bool = False,
) -> Optional[int]:
    """挂证据（唯一键冲突 no-op 返回 None）。

    `text` 给出且未显式传 offset/length → 走 `full_span` 全跨度简化（D6）。
    hash 未给 → 由 text 归一计算；两者都缺 → ValueError（hash 是必填列）。
    """
    from app.models.ai import MemoryAssertionEvidence

    if offset_codepoint is None or length_codepoint is None:
        if text is None:
            raise ValueError("need text (full span) or explicit offset/length")
        auto_offset, auto_length, auto_hash = full_span(text)
        offset_codepoint = auto_offset if offset_codepoint is None else offset_codepoint
        length_codepoint = auto_length if length_codepoint is None else length_codepoint
        hash_hex = hash_hex or auto_hash
    if hash_hex is None:
        if text is None:
            raise ValueError("need text or hash_hex")
        hash_hex = content_hash(text)

    exists = (
        db.query(MemoryAssertionEvidence.id)
        .filter(
            MemoryAssertionEvidence.assertion_id == assertion_id,
            MemoryAssertionEvidence.source_type == source_type,
            MemoryAssertionEvidence.source_id == source_id,
            MemoryAssertionEvidence.source_revision_id == source_revision_id,
            MemoryAssertionEvidence.offset_codepoint == offset_codepoint,
            MemoryAssertionEvidence.length_codepoint == length_codepoint,
            MemoryAssertionEvidence.evidence_role == evidence_role,
        )
        .first()
    )
    if exists is not None:
        return None  # 唯一键 no-op

    row = MemoryAssertionEvidence(
        assertion_id=assertion_id,
        source_type=source_type,
        source_id=source_id,
        source_revision_id=source_revision_id,
        offset_codepoint=offset_codepoint,
        length_codepoint=length_codepoint,
        content_hash=hash_hex,
        evidence_role=evidence_role,
    )
    db.add(row)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        logger.info(
            "[MEM-EVID] concurrent duplicate evidence assertion=%s src=%s:%s",
            assertion_id, source_type, source_id,
        )
        return None
    if commit:
        db.commit()
    return row.id


def evidence_count(db, assertion_id: int) -> int:
    """按 `(source_type, source_id)` 去重的证据数（附录 §7.2）。"""
    from app.models.ai import MemoryAssertionEvidence

    pairs = (
        db.query(
            MemoryAssertionEvidence.source_type,
            MemoryAssertionEvidence.source_id,
        )
        .distinct()
        .filter(MemoryAssertionEvidence.assertion_id == assertion_id)
        .all()
    )
    return len(pairs)


def reporter_count(db, assertion_id: int) -> int:
    """按源证据实际作者去重（不可解源不计数，附录 §7.2）。"""
    from app.models.ai import MemoryAssertionEvidence

    rows = (
        db.query(
            MemoryAssertionEvidence.source_type,
            MemoryAssertionEvidence.source_id,
            MemoryAssertionEvidence.source_revision_id,
        )
        .filter(MemoryAssertionEvidence.assertion_id == assertion_id)
        .all()
    )
    reporters = set()
    seen_pairs = set()
    for source_type, source_id, revision in rows:
        pair = (source_type, source_id)
        if pair in seen_pairs:
            continue
        seen_pairs.add(pair)
        resolver = SOURCE_REPORTER_RESOLVERS.get(source_type)
        if resolver is None:
            continue  # 不可解源不计数
        try:
            uid = resolver(db, source_id, revision)
        except Exception:  # noqa: BLE001——解析失败 = 不可解，不计数
            logger.warning(
                "[MEM-EVID] reporter resolve failed src=%s:%s",
                source_type, source_id, exc_info=True,
            )
            uid = None
        if uid is not None:
            reporters.add(uid)
    return len(reporters)


def mark_evidence_lost(db, assertion_id: int, *, commit: bool = False) -> bool:
    """源被删 → 断言标 `evidence_lost=True`（高风险断言停止注入，§1.1）。"""
    from app.models.ai import AiMemory

    row = db.query(AiMemory).filter(AiMemory.id == assertion_id).first()
    if row is None:
        return False
    if row.evidence_lost:
        return False  # 幂等
    row.evidence_lost = True
    if commit:
        db.commit()
    return True
