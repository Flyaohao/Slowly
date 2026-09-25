"""v3.2 附录 §1.3/§7.2 证据服务（真库、不调模型）

用例：
  ① full_span / content_hash 归一口径（只行结束符，码点长度）
  ② attach_evidence 落库 + 同键重复 no-op
  ③ evidence_count 按 (source_type, source_id) 去重（两 revision=1）
  ④ reporter_count：可解源去重计数、不可解源不计数
  ⑤ mark_evidence_lost 幂等

数据隔离：finally 按 id 删证据与断言行，基线计数首尾相等。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_evidence.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []
_CREATED_MEM_IDS = []
_CREATED_EVIDENCE_IDS = []
_RESOLVER_SOURCES = []


def ok(name: str, passed: bool, detail: str = ""):
    mark = "PASS" if passed else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


def _baseline() -> tuple:
    from sqlalchemy import text
    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        m = db.execute(text("SELECT COUNT(*) FROM ai_memory")).scalar()
        e = db.execute(text("SELECT COUNT(*) FROM memory_assertion_evidence")).scalar()
        return m, e
    finally:
        db.close()


def _cleanup(db):
    from app.models.ai import AiMemory, MemoryAssertionEvidence
    from app.services.memory_evidence_service import SOURCE_REPORTER_RESOLVERS

    if _CREATED_EVIDENCE_IDS:
        db.query(MemoryAssertionEvidence).filter(
            MemoryAssertionEvidence.id.in_(_CREATED_EVIDENCE_IDS)
        ).delete(synchronize_session=False)
        db.commit()
    for row_id in list(_CREATED_MEM_IDS):
        db.query(AiMemory).filter(AiMemory.id == row_id).delete(
            synchronize_session=False
        )
        db.commit()
    _CREATED_MEM_IDS.clear()
    _CREATED_EVIDENCE_IDS.clear()
    for src in _RESOLVER_SOURCES:
        SOURCE_REPORTER_RESOLVERS.pop(src, None)
    _RESOLVER_SOURCES.clear()


def main() -> int:
    print("=" * 72)
    print("附录 §1.3/§7.2 证据服务")
    print("=" * 72)
    base_mem, base_evid = _baseline()

    from app.core.database import SessionLocal
    from app.models.ai import AiMemory
    from app.models.couple_relation import CoupleRelation
    from app.services.memory_evidence_service import (
        SOURCE_REPORTER_RESOLVERS,
        attach_evidence,
        content_hash,
        evidence_count,
        full_span,
        mark_evidence_lost,
        reporter_count,
    )
    from app.services.memory_fingerprint import normalize_evidence_text

    db = SessionLocal()
    try:
        rel = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active")
            .order_by(CoupleRelation.id.asc())
            .first()
        )
        if rel is None:
            raise RuntimeError("库里没有 active couple_relation")

        print("\n[1] full_span / content_hash")
        off, length, h = full_span("hello\r\nworld")
        ok("CRLF 归一后码点长度", (off, length) == (0, 11), f"{off},{length}")
        ok("hash 与 content_hash 一致", h == content_hash("hello\r\nworld"))
        ok("归一只行结束符", normalize_evidence_text("a\r\nb") == "a\nb")

        print("\n[2] attach_evidence + 唯一键 no-op")
        mem = AiMemory(
            user_id=rel.user_a_id,
            relation_id=rel.id,
            memory_type="关系事实",
            memory_text="evidence test row",
            visibility="couple",
            status="active",
        )
        db.add(mem)
        db.commit()
        _CREATED_MEM_IDS.append(mem.id)

        ev1 = attach_evidence(
            db, assertion_id=mem.id, source_type="chat_message",
            source_id=900001, text="hello\r\nworld", commit=True,
        )
        _CREATED_EVIDENCE_IDS.append(ev1)
        ok("首挂返回 id", ev1 is not None, str(ev1))
        dup = attach_evidence(
            db, assertion_id=mem.id, source_type="chat_message",
            source_id=900001, text="hello world", commit=True,
        )
        ok("同键重复挂 → no-op None", dup is None, str(dup))
        # 第二个 revision 同 source：唯一键不同（revision 参与）→ 应插入
        ev2 = attach_evidence(
            db, assertion_id=mem.id, source_type="chat_message",
            source_id=900001, source_revision_id=2, text="hello v2", commit=True,
        )
        _CREATED_EVIDENCE_IDS.append(ev2)
        ok("同 source 第二 revision 可挂（唯一键含 revision）",
           ev2 is not None, str(ev2))

        print("\n[3] evidence_count 去重口径（§7.2）")
        ok("同 source 两 revision = 1", evidence_count(db, mem.id) == 1,
           str(evidence_count(db, mem.id)))
        ev3 = attach_evidence(
            db, assertion_id=mem.id, source_type="diary",
            source_id=900002, text="diary entry", commit=True,
        )
        _CREATED_EVIDENCE_IDS.append(ev3)
        ok("两个不同 source = 2", evidence_count(db, mem.id) == 2,
           str(evidence_count(db, mem.id)))

        print("\n[4] reporter_count（可解去重 / 不可解不计）")
        # 临时可解源（diary 作者 = 987654，与真实用户 id 无碰撞）
        SOURCE_REPORTER_RESOLVERS["diary"] = lambda db_, sid, rev: 987654
        _RESOLVER_SOURCES.append("diary")
        from app.models.ai import AiChatMessage, AiChatSession

        real_msg = db.query(AiChatMessage).order_by(AiChatMessage.id.desc()).first()
        expected_reporters = 1  # diary → 987654
        if real_msg is not None:
            sess = (
                db.query(AiChatSession)
                .filter(AiChatSession.id == real_msg.session_id)
                .first()
            )
            if sess is not None:
                ev_real = attach_evidence(
                    db, assertion_id=mem.id, source_type="chat_message",
                    source_id=real_msg.id, text="real chat evidence", commit=True,
                )
                _CREATED_EVIDENCE_IDS.append(ev_real)
                expected_reporters = 2  # session.user_id + diary 987654
        ok("可解源按作者去重计数",
           reporter_count(db, mem.id) == expected_reporters,
           str(reporter_count(db, mem.id)))
        # 同一作者的第二个可解源 → 计数不变（按作者去重）
        ev4 = attach_evidence(
            db, assertion_id=mem.id, source_type="diary",
            source_id=900003, text="another diary", commit=True,
        )
        _CREATED_EVIDENCE_IDS.append(ev4)
        ok("两 diary 源同一作者 → 作者去重",
           reporter_count(db, mem.id) == expected_reporters,
           str(reporter_count(db, mem.id)))
        # 不可解源（无 resolver）：evidence_count+1 但 reporter 不变
        evid_before = evidence_count(db, mem.id)
        ev5 = attach_evidence(
            db, assertion_id=mem.id, source_type="letter",
            source_id=900004, text="letter", commit=True,
        )
        _CREATED_EVIDENCE_IDS.append(ev5)
        ok("不可解源不计 reporter",
           reporter_count(db, mem.id) == expected_reporters
           and evidence_count(db, mem.id) == evid_before + 1,
           f"reporter={reporter_count(db, mem.id)}, evidence={evidence_count(db, mem.id)}")

        print("\n[5] mark_evidence_lost 幂等")
        first = mark_evidence_lost(db, mem.id, commit=True)
        second = mark_evidence_lost(db, mem.id, commit=True)
        db.refresh(mem)
        ok("首次标记 True", first is True)
        ok("重复标记 False（幂等）", second is False)
        ok("evidence_lost=True", mem.evidence_lost is True)
        ok("不存在的断言返回 False", mark_evidence_lost(db, 999999999) is False)
    finally:
        _cleanup(db)
        db.close()

    end_mem, end_evid = _baseline()
    ok("基线行数收尾相等（ai_memory）", base_mem == end_mem, f"{base_mem} vs {end_mem}")
    ok("基线行数收尾相等（evidence）", base_evid == end_evid, f"{base_evid} vs {end_evid}")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
