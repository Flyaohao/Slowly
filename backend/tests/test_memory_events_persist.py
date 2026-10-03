"""P-C1 §8.1：事件入库落库验证（真库、零外部依赖、不调模型）。

用例（对应任务单编号）：
  C1  迁移幂等轻量版——inspector 断言 ai_memory 列集/索引集 + diary_entry.relation_id
  C2  5 类源各造一个 MemoryEvent → 落库行**都有** occurred_at / source / source_id
      （事件行 + 蒸馏行双写都断言；importance 按 §4 默认表）
  C3  should_remember=False（monkeypatch 模型返回）→ 库里**仍有** memory_type='事件' 行
  C9  日记路径 → 新行含 occurred_at 与 source='diary'；**反向断言**：无 active relation 不写
  C10 归档摘要 → couple_memory 能查到该 memory_id（证明 create_memory 向量化挂钩生效）

数据隔离红线（§8.3）：
  - 顶部 COUPLE_DISABLE_MEMORY_DISTILL=1（挡住聊天链路的后台蒸馏；
    C10 临时摘掉该开关，用例内 finally 恢复）
  - 真库写入全部在 finally **按 id 显式单行删除**（DB 行 + 向量）；
    禁批量删除。开头/结尾各记一次基线行数，必须相等。

运行：cd backend && python tests/test_memory_events_persist.py
"""
import os
import sys
import time
from datetime import datetime

os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# 注意（2026-10-03）：桩必须拦在 `user_ai_config_service.build_chat_client`。
# 模块级的 `memory_service.distill_llm` 在蒸馏路径上**不会被读**——
# `distill_and_save(db, ..., llm_client=None)` 会现解析 uaicfg 客户端，
# 然后调 `llm_client.invoke_structured(...)`（memory_service.py:635）。
# 打旧位置会让请求真发上游 → 403 Free quota exhausted。


def _stub_client_factory(fake):
    """把 `fake` 接成蒸馏链路的 LLM 客户端，返回原函数供还原。"""
    from app.services import user_ai_config_service as uaicfg

    original = uaicfg.build_chat_client
    uaicfg.build_chat_client = lambda *a, **k: fake
    return original


def _restore_client_factory(original):
    from app.services import user_ai_config_service as uaicfg

    uaicfg.build_chat_client = original


FAILURES = []
#: 本次用例创建、finally 需要清理的 id 列表（DB 行；向量按同批 id 全量补删）
_CREATED_IDS = []


def ok(name: str, passed: bool, detail: str = ""):
    mark = "✅" if passed else "❌"
    print(f"  {mark} {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


def _baseline_count() -> int:
    from sqlalchemy import text

    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        return db.execute(text("SELECT COUNT(*) FROM ai_memory")).scalar()
    finally:
        db.close()


class FakeLlm:
    """monkeypatch memory_service.distill_llm——不调模型。"""

    api_key = "test-key"

    def __init__(self, should_remember=True):
        self.should_remember = should_remember
        self.calls = []
        # 每实例唯一后缀：不同用例的蒸馏文本互不撞 _is_duplicate_memory
        # （首轮 museum/dual 双写失败的根因——常量文本先被 letter 写走）
        self.uid = datetime.now().strftime("%H%M%S%f")

    def invoke_structured(self, messages, scene="unknown", **kw):
        self.calls.append(scene)
        from app.schemas.ai_output import MemoryDistillOutput

        return MemoryDistillOutput(
            should_remember=self.should_remember,
            memory_type="关系事实",
            memory_text=(
                "测试蒸馏记忆文本-%s-%d" % (self.uid, len(self.calls))
                if self.should_remember
                else ""
            ),
        )

    def invoke(self, *a, **kw):
        return "归档摘要文本-%s" % self.uid


def _event(source: str, user_id: int, relation_id: int, source_id: int):
    from app.services.memory_events import MemoryEvent

    stamp = datetime.now().strftime("%H%M%S%f")
    return MemoryEvent(
        source=source,
        source_id=source_id,
        user_id=user_id,
        relation_id=relation_id,
        content="这是一段足够长的%s事件内容-%s，用于通过最小长度闸门并避免撞去重" % (source, stamp),
        occurred_at=datetime(2026, 9, 20, 10, 30, 0),
        extra={"context": "%s持久化测试-%s" % (source, stamp)},
    )


def _rows_by_ids(ids):
    from app.core.database import SessionLocal
    from app.models.ai import AiMemory

    if not ids:
        return []
    db = SessionLocal()
    try:
        return db.query(AiMemory).filter(AiMemory.id.in_(ids)).all()
    finally:
        db.close()


def case_c1_schema():
    print("\n[C1] 迁移列集/索引集（inspector）")
    from sqlalchemy import inspect

    from app.core.database import engine

    insp = inspect(engine)
    cols = {c["name"] for c in insp.get_columns("ai_memory")}
    for name in ("occurred_at", "source", "source_id", "importance"):
        ok(f"ai_memory 列 {name} 存在", name in cols, str(sorted(cols)))
    idx = {i["name"] for i in insp.get_indexes("ai_memory")}
    ok("索引 ix_ai_memory_relation_occurred 存在",
       "ix_ai_memory_relation_occurred" in idx, str(sorted(idx)))
    ok("diary_entry.relation_id 存在",
       "relation_id" in {c["name"] for c in insp.get_columns("diary_entry")})
    engine.dispose()


def case_c2_five_sources(user_id: int, relation_id: int):
    print("\n[C2] 5 类源落库行都带 occurred_at / source / source_id")
    from app.services import memory_service
    from app.services.memory_events import IMPORTANCE_BY_SOURCE, distill_event

    sources = ("letter", "museum", "dual", "anniversary", "questionnaire")
    fake = FakeLlm()
    uaicfg = _stub_client_factory(fake)
    orig = uaicfg
    try:
        for i, source in enumerate(sources):
            before_ids = set(_recent_ids(relation_id))
            result = distill_event(_event(source, user_id, relation_id, 90000 + i))
            new_ids = [r["id"] for r in result]
            _CREATED_IDS.extend(new_ids)
            ok(f"{source}: 返回 >=1 条落库", len(result) >= 1, f"result={result}")
            rows = _rows_by_ids(new_ids)
            for row in rows:
                ok(f"{source}: id={row.id} occurred_at 非空", row.occurred_at is not None)
                ok(f"{source}: id={row.id} source == {source}", row.source == source,
                   str(row.source))
                ok(f"{source}: id={row.id} source_id == 900{i}",
                   row.source_id == 90000 + i, str(row.source_id))
                ok(f"{source}: id={row.id} importance 按 §4 表 == "
                   f"{IMPORTANCE_BY_SOURCE[source]}",
                   row.importance == IMPORTANCE_BY_SOURCE[source],
                   str(row.importance))
            ok(f"{source}: 蒸馏行也写了（双写 >=2）" if source in
               ("letter", "museum", "dual") else f"{source}: 结构化双写 >=2",
               len(result) >= 2, f"len={len(result)}")
        # AI 源各调一次（monkeypatch 生效），结构化源没调。
        # scene 键由 distill_and_save 固定为 'memory_distill'，故只断言次数
        ok("AI 源各调一次 invoke_structured（共 3 次、不打真模型）",
           len(fake.calls) == 3, str(fake.calls))
    finally:
        _restore_client_factory(orig)


def _recent_ids(relation_id: int):
    from sqlalchemy import text

    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        rows = db.execute(text(
            "SELECT id FROM ai_memory WHERE relation_id = :r ORDER BY id"
        ), {"r": relation_id}).fetchall()
        return [r[0] for r in rows]
    finally:
        db.close()


def case_c3_event_row_without_distill(user_id: int, relation_id: int):
    print("\n[C3] should_remember=False → 仍有 memory_type='事件' 行")
    from app.services import memory_service
    from app.services.memory_events import distill_event

    fake = FakeLlm(should_remember=False)
    uaicfg = _stub_client_factory(fake)
    orig = uaicfg
    try:
        result = distill_event(_event("letter", user_id, relation_id, 91001))
        new_ids = [r["id"] for r in result]
        _CREATED_IDS.extend(new_ids)
        ok("蒸馏被闸门挡住（只回事件行）", len(result) == 1, f"len={len(result)}")
        rows = _rows_by_ids(new_ids)
        ok("库里仍有 memory_type='事件' 行",
           any(r.memory_type == "事件" for r in rows),
           str([(r.id, r.memory_type) for r in rows]))
        ok("事件行带 source='letter'",
           all(r.source == "letter" for r in rows if r.memory_type == "事件"))
    finally:
        _restore_client_factory(orig)


def case_c9_diary(active_user_id: int, relation_id: int, no_rel_user_id: int):
    print("\n[C9] 日记 → 记忆（含反向断言：无 active relation 不写）")
    from app.core.database import SessionLocal
    from app.models.diary_entry import DiaryEntry
    from app.services import memory_service
    from app.services.memory_events import write_diary_memory

    # 打桩蒸馏（§8.1 不调模型；首轮 C9 曾真调了 distill_llm）
    fake = FakeLlm()
    uaicfg = _stub_client_factory(fake)
    orig = uaicfg
    db = SessionLocal()
    entry = None
    try:
        stamp = datetime.now().strftime("%H%M%S%f")
        entry = DiaryEntry(
            user_id=active_user_id,
            title="持久化日记-%s" % stamp,
            content="今天天气不错，写下一件具体的小事用于事件入库验证。",
        )
        db.add(entry)
        db.commit()
        before = _recent_ids(relation_id)

        fired = write_diary_memory(db, active_user_id, entry, background=False)
        ok("有 active relation → 写入触发", fired is True)
        ok("diary_entry.relation_id 已回填", entry.relation_id == relation_id,
           str(entry.relation_id))
        new_ids = [i for i in _recent_ids(relation_id) if i not in set(before)]
        _CREATED_IDS.extend(new_ids)
        rows = _rows_by_ids(new_ids)
        diary_rows = [r for r in rows if r.source == "diary"]
        ok("新行含 source='diary'", len(diary_rows) >= 1,
           str([(r.id, r.source) for r in rows]))
        ok("日记行 occurred_at 非空（业务时间）",
           all(r.occurred_at is not None for r in diary_rows))
        ok("日记行含蒸馏/事件内容", all(r.memory_text for r in diary_rows))

        # ---- 反向断言：无 active relation 时不写 ----
        entry2 = DiaryEntry(
            user_id=no_rel_user_id,
            title="单身日记-%s" % stamp,
            content="没有 active relation 的用户写日记，不应产生任何记忆行。",
        )
        db.add(entry2)
        db.commit()
        _CREATED_DIARY_IDS.append(entry2.id)
        fired2 = write_diary_memory(db, no_rel_user_id, entry2, background=False)
        ok("无 active relation → 不触发", fired2 is False)
        from sqlalchemy import text as sa_text

        leak = db.execute(sa_text(
            "SELECT COUNT(*) FROM ai_memory WHERE user_id = :u AND source = 'diary'"
        ), {"u": no_rel_user_id}).scalar()
        ok("无 active relation → 该用户 0 条 diary 记忆", leak == 0, f"leak={leak}")
        ok("单身日记 relation_id 保持 NULL", entry2.relation_id is None,
           str(entry2.relation_id))
    finally:
        _restore_client_factory(orig)
        if entry is not None:
            _CREATED_DIARY_IDS.append(entry.id)
        db.close()


_CREATED_DIARY_IDS = []


def case_c10_session_summary_vector(relation_id: int):
    print("\n[C10] 归档摘要 → couple_memory 查得到（向量化挂钩生效）")
    from sqlalchemy import text

    from app.core.database import SessionLocal
    from app.services import memory_service

    db = SessionLocal()
    session_id = None
    try:
        row = db.execute(text(
            "SELECT DISTINCT session_id FROM ai_chat_message "
            "WHERE session_id IS NOT NULL ORDER BY session_id DESC LIMIT 1"
        )).first()
        session_id = row[0] if row else None
    finally:
        db.close()
    ok("存在带消息的会话可归档", session_id is not None, str(session_id))
    if session_id is None:
        return

    saved_flag = os.environ.pop("COUPLE_DISABLE_MEMORY_DISTILL", None)
    fake = FakeLlm()
    uaicfg = _stub_client_factory(fake)
    orig = uaicfg
    mem_id = None
    try:
        # 先就绪 collection 再触发后台写——避免向量线程与主线程竞速懒加载
        from app.services.memory_retrieval import _get_collection, reset_store_for_tests

        reset_store_for_tests()
        col = _get_collection()
        ok("couple_memory 可加载（C10 前置）", col is not None)
        if col is None:
            return
        summary_text = "归档摘要文本-%s" % fake.uid
        memory_service.distill_session_summary_in_background(
            session_id, _summary_user_id(), relation_id, "private_advisor"
        )
        # 后台线程：轮询等行出现
        deadline = time.time() + 8
        while time.time() < deadline:
            db = SessionLocal()
            try:
                found = db.execute(text(
                    "SELECT id FROM ai_memory WHERE source='chat_summary' "
                    "AND memory_text = :t ORDER BY id DESC LIMIT 1"
                ), {"t": summary_text}).first()
            finally:
                db.close()
            if found:
                mem_id = found[0]
                break
            time.sleep(0.2)
        ok("session_summary 行落库且 source='chat_summary'", mem_id is not None)
        if mem_id is None:
            return
        _CREATED_IDS.append(mem_id)

        # 向量挂钩：轮询（不再 reset——与后台写入共用同一 collection）
        vdeadline = time.time() + 15
        present = False
        while time.time() < vdeadline:
            got = col.get(ids=[str(mem_id)])
            if got and got.get("ids"):
                present = True
                meta = (got.get("metadatas") or [{}])[0] or {}
                ok("向量 metadata 带 source='chat_summary'",
                   meta.get("source") == "chat_summary", str(meta))
                break
            time.sleep(0.3)
        ok("couple_memory 查得到该 memory_id", present)
    finally:
        _restore_client_factory(orig)
        if saved_flag is not None:
            os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = saved_flag


def _summary_user_id() -> int:
    """归档摘要行的 user_id：取 active relation 的 user_a（真实 FK）。"""
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation

    db = SessionLocal()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        return rel.user_a_id
    finally:
        db.close()


def _cleanup():
    """按 id 显式单行删除（DB + 向量），禁批量。"""
    from sqlalchemy import text

    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        for mid in list(dict.fromkeys(_CREATED_IDS)):
            db.execute(text("DELETE FROM ai_memory WHERE id = :i"), {"i": mid})
        db.commit()
        for did in list(dict.fromkeys(_CREATED_DIARY_IDS)):
            db.execute(text("DELETE FROM diary_entry WHERE id = :i"), {"i": did})
        db.commit()
    finally:
        db.close()
    # 向量清理：按同批 id 全量清（首轮只清 C10 收集的 id，漏了 C2 等
    # 后台向量化线程写出的向量 → 孤儿 212）。两遍：向量线程可能仍在
    # embed 窗口内（先查过库、随后才 add），等其落地后补删一次。
    try:
        from app.services.memory_retrieval import _get_collection

        def _sweep():
            col = _get_collection()
            if col is None:
                return
            for vid in list(dict.fromkeys(str(i) for i in _CREATED_IDS)):
                try:
                    col.delete(ids=[vid])
                except Exception:
                    pass

        _sweep()
        time.sleep(2.0)
        _sweep()
    except Exception:
        pass


def main() -> int:
    print("=" * 72)
    print("P-C1 §8.1 事件入库落库验证（真库 / 不调模型 / finally 单行删除）")
    print("=" * 72)

    from sqlalchemy import text

    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation

    baseline = _baseline_count()
    print(f"基线行数: {baseline}")
    db0 = SessionLocal()
    try:
        baseline_max_id = db0.execute(
            text("SELECT COALESCE(MAX(id), 0) FROM ai_memory")
        ).scalar()
    finally:
        db0.close()

    # 定位真实 user / relation 与无 active relation 的 user

    db = SessionLocal()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        user_id, relation_id = rel.user_a_id, rel.id
        no_rel = db.execute(text(
            "SELECT u.id FROM user u "
            "WHERE u.id NOT IN (SELECT user_a_id FROM couple_relation WHERE status='active') "
            "  AND u.id NOT IN (SELECT user_b_id FROM couple_relation WHERE status='active') "
            "LIMIT 1"
        )).first()
        if no_rel is None:
            from app.models.user import User

            u = User(email="pc1_no_rel_%s@example.com" % int(time.time()),
                     password_hash="x")
            db.add(u)
            db.commit()
            _CREATED_USER_IDS.append(u.id)
            no_rel_user_id = u.id
        else:
            no_rel_user_id = no_rel[0]
    finally:
        db.close()

    print(f"测试用户 user={user_id} relation={relation_id} 无关系用户={no_rel_user_id}")

    try:
        case_c1_schema()
        case_c2_five_sources(user_id, relation_id)
        case_c3_event_row_without_distill(user_id, relation_id)
        case_c9_diary(user_id, relation_id, no_rel_user_id)
        case_c10_session_summary_vector(relation_id)

        # 完成定义 #1：基线之后的新行 source IS NULL 计数（清理前取证）
        db = SessionLocal()
        try:
            n_null = db.execute(text(
                "SELECT COUNT(*) FROM ai_memory WHERE source IS NULL AND id > :b"
            ), {"b": baseline_max_id}).scalar()
            ok("新行（id>基线）source IS NULL 计数 == 0",
               n_null == 0, f"null={n_null}")
        finally:
            db.close()
    finally:
        _cleanup()
        # 清理临时用户
        if _CREATED_USER_IDS:
            db = SessionLocal()
            try:
                for uid in _CREATED_USER_IDS:
                    db.execute(text("DELETE FROM user WHERE id = :i"), {"i": uid})
                db.commit()
            finally:
                db.close()

    after = _baseline_count()
    print(f"收尾行数: {after}（基线 {baseline}）")
    ok("基线行数 == 收尾行数", after == baseline, f"{baseline} != {after}")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


_CREATED_USER_IDS = []


if __name__ == "__main__":
    sys.exit(main())
