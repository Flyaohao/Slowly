"""v3.2 §7.4 flags 关闭平价门禁（4 flag 强制 0 = v1 行为）。

用例：
  (a) 4 flag 强制 0：create_memory → `_to_dict` 键集 == 冻结基线、
      schema_version NULL、旧向量化钩子照常调用
  (b) chat 调用点：DUAL_WRITE=0 → **不插**管线任务、
      distill_in_background 被调用（spy）、不启 worker
  (c) retrieve_memory_items：签名含 memory_need（默认 personal_fact）；
      query 空 → 近因契约（DB-only，返回含最新行）
  (d) 工具 schema 无 user_id/relation_id（get_ai_memory 只剩 query）
  (e) ensure_started 在 DUAL_WRITE=0 时无副作用（线程不启动）

与 dualwrite_parity 的分工：那份测「flag 开」的 v3 契约与开/关双态，
本份是**关态门禁**的独立复述 + chat 调用点 / 检索签名 / 工具面。

数据隔离：finally 按 id 删行（消息 → 会话 → 场景 → 记忆），
任务/记忆基线计数首尾相等；恢复全部 patch 与 flag。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_flags_off_parity.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []

#: 冻结基线：v1 `_to_dict` 键集与顺序（与 dualwrite_parity 同源，改它=改客户端契约）
BASELINE_KEYS = [
    "id", "user_id", "relation_id", "memory_type", "memory_text",
    "visibility", "created_at", "occurred_at", "source", "source_id",
    "importance",
]

_MSG_IDS = []
_SESSION_ID = None
_SCENE_KEY = "z_flags_scene"
_SCENE_CREATED = False
_MEM_IDS = []

_DISTILL_CALLS = []
_ENQUEUE_CALLS = []
_HOOK_CALLS = []


def ok(name: str, passed: bool, detail: str = ""):
    mark = "PASS" if passed else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


def _baseline(db):
    from sqlalchemy import text

    counts = {}
    for table in ("memory_pipeline_task", "ai_memory"):
        counts[table] = db.execute(text(f"SELECT COUNT(*) FROM {table}")).scalar()
    return counts


def _cleanup(db):
    try:
        db.rollback()
    except Exception:
        pass
    from app.models.ai import AiChatMessage, AiChatSession, AiMemory, AiScene

    if _MSG_IDS:
        db.query(AiChatMessage).filter(AiChatMessage.id.in_(_MSG_IDS)).delete(
            synchronize_session=False
        )
    if _SESSION_ID is not None:
        db.query(AiChatSession).filter(AiChatSession.id == _SESSION_ID).delete(
            synchronize_session=False
        )
    if _SCENE_CREATED:
        db.query(AiScene).filter(AiScene.scene_key == _SCENE_KEY).delete(
            synchronize_session=False
        )
    if _MEM_IDS:
        db.query(AiMemory).filter(AiMemory.id.in_(_MEM_IDS)).delete(
            synchronize_session=False
        )
    db.commit()


def main() -> int:
    global _SESSION_ID, _SCENE_CREATED

    print("=" * 72)
    print("flags 关闭平价门禁（§7.4：4 flag 强制 0 = v1）")
    print("=" * 72)

    from app.core import config as app_config
    from app.core.database import SessionLocal
    from app.models.ai import AiChatSession, AiScene, AiMemory
    from app.models.couple_relation import CoupleRelation
    from app.services import ai_service, memory_pipeline, memory_retrieval
    from app.services import memory_service

    orig_flags = {
        "dual": app_config.MEMORY_ASSERTION_DUAL_WRITE,
        "read": app_config.MEMORY_ASSERTION_READ_V3,
        "strict": app_config.MEMORY_ASSERTION_REQUIRE_COMPLETE,
        "worker": app_config.MEMORY_ASSERTION_INDEX_WORKER,
    }
    # 4 flag 全关（§7.4 红线：关=v1）
    app_config.MEMORY_ASSERTION_DUAL_WRITE = False
    app_config.MEMORY_ASSERTION_READ_V3 = False
    app_config.MEMORY_ASSERTION_REQUIRE_COMPLETE = False
    app_config.MEMORY_ASSERTION_INDEX_WORKER = False

    db = SessionLocal()
    base = _baseline(db)
    try:
        rel = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active")
            .order_by(CoupleRelation.id.asc())
            .first()
        )
        if rel is None:
            raise RuntimeError("库里没有 active couple_relation")
        uid, partner_uid = rel.user_a_id, rel.user_b_id

        # ---- (a) create_memory 关态平价 ----
        print("\n(a) create_memory：flag 关 = v1 键集/列平价")

        def _spy_hook(saved):
            _HOOK_CALLS.append(saved.get("id"))

        orig_hook = memory_retrieval.vectorize_memory_async
        memory_retrieval.vectorize_memory_async = _spy_hook
        try:
            _HOOK_CALLS.clear()
            saved = memory_service.create_memory(
                db, uid, rel.id, "关系事实", "flags off parity row",
                visibility="couple", source="diary",
            )
            _MEM_IDS.append(saved["id"])
            ok("_to_dict 键集 == 冻结基线",
               list(saved.keys()) == BASELINE_KEYS, str(list(saved.keys())))
            row = db.get(AiMemory, saved["id"])
            ok("schema_version NULL（不写 v3）", row.schema_version is None,
               str(row.schema_version))
            ok("v3 列不写入",
               row.predicate_code is None and row.fact_key is None
               and row.ownership_type is None)
            ok("旧向量化钩子照常调用", _HOOK_CALLS == [saved["id"]],
               str(_HOOK_CALLS))
        finally:
            memory_retrieval.vectorize_memory_async = orig_hook

        # ---- (b) chat 调用点：旧 distill 路径 + 不插任务 ----
        print("\n(b) chat 调用点：DUAL_WRITE=0 → distill_in_background、零任务")
        # scene 独立 flush（裸 FK 无 relationship，UOW 不排序 → FK 1452）
        if db.query(AiScene).filter(AiScene.scene_key == _SCENE_KEY).first() is None:
            db.add(AiScene(scene_key=_SCENE_KEY, name="flags测试场景"))
            db.flush()
            _SCENE_CREATED = True
        sess = AiChatSession(
            user_id=uid, relation_id=rel.id, scene_key=_SCENE_KEY,
            partner_user_id=partner_uid, status="active", message_count=99,
        )
        db.add(sess)
        db.flush()
        _SESSION_ID = sess.id
        db.commit()
        scene_obj = db.query(AiScene).filter(AiScene.scene_key == _SCENE_KEY).first()

        def _fake_preprocess(*args, **kwargs):
            return {
                "blocked": None,
                "session_id": sess.id,
                "session": sess,
                "scene": scene_obj,
                "messages": [],
                "stream_messages": [],
                "user_input": "flags off 聊天输入",
                "rag_chunks": [],
                "evidence": None,
                "chat_mode": "deep",
            }

        def _fake_llm(messages, scene_key, **kwargs):
            return {"raw_text": "flags off 聊天回复", "risk_level": "normal"}

        def _fake_title(**kwargs):
            _TITLE_CALLS.append(kwargs)

        def _fake_distill(user_id, relation_id, scene_key, user_text, ai_text):
            _DISTILL_CALLS.append(
                (user_id, relation_id, scene_key, user_text, ai_text)
            )

        def _fake_enqueue(*args, **kwargs):
            _ENQUEUE_CALLS.append(kwargs)
            raise AssertionError("flag 关时 enqueue_task 不应被调用")

        _TITLE_CALLS = []
        _DISTILL_CALLS.clear()
        _ENQUEUE_CALLS.clear()
        orig_preprocess = ai_service._preprocess
        orig_call_llm = ai_service._call_llm
        orig_title = ai_service._schedule_session_title
        orig_distill = ai_service.distill_in_background
        orig_enqueue = memory_pipeline.enqueue_task
        ai_service._preprocess = _fake_preprocess
        ai_service._call_llm = _fake_llm
        ai_service._schedule_session_title = _fake_title
        ai_service.distill_in_background = _fake_distill
        memory_pipeline.enqueue_task = _fake_enqueue
        try:
            result = ai_service.chat(
                db, uid, rel.id, sess.id, _SCENE_KEY, "flags off 聊天输入",
            )
            ok("chat 正常返回（非 blocked）",
               not result.get("blocked") and result["message"]["id"] > 0,
               str(result.get("blocked")))
            ok("distill_in_background 被调（旧路径 spy）",
               len(_DISTILL_CALLS) == 1
               and _DISTILL_CALLS[0][0] == uid
               and _DISTILL_CALLS[0][1] == rel.id
               and _DISTILL_CALLS[0][2] == _SCENE_KEY,
               str(_DISTILL_CALLS))
            ok("enqueue_task 未被调用", _ENQUEUE_CALLS == [],
               str(_ENQUEUE_CALLS))
            msg_id = result["message"]["id"]
            _MSG_IDS.append(msg_id)
            from app.models.ai import MemoryPipelineTask

            task_count = (
                db.query(MemoryPipelineTask)
                .filter(
                    MemoryPipelineTask.source_type == "chat_message",
                    MemoryPipelineTask.source_id == msg_id,
                )
                .count()
            )
            ok("管线任务表零插入", task_count == 0, str(task_count))
        finally:
            ai_service._preprocess = orig_preprocess
            ai_service._call_llm = orig_call_llm
            ai_service._schedule_session_title = orig_title
            ai_service.distill_in_background = orig_distill
            memory_pipeline.enqueue_task = orig_enqueue

        # ---- (c) 检索签名 + 空 query 近因契约 ----
        print("\n(c) retrieve_memory_items：memory_need 签名 + 空 query 近因")
        import inspect

        sig = inspect.signature(memory_retrieval.retrieve_memory_items)
        ok("签名含 keyword-only memory_need（默认 personal_fact）",
           "memory_need" in sig.parameters
           and sig.parameters["memory_need"].default == "personal_fact"
           and sig.parameters["memory_need"].kind is inspect.Parameter.KEYWORD_ONLY,
           str(sig.parameters.get("memory_need")))
        fresh = memory_service.create_memory(
            db, uid, rel.id, "偏好", "flags off 近因契约最新行",
            visibility="couple", source="diary",
        )
        _MEM_IDS.append(fresh["id"])
        results = memory_retrieval.retrieve_memory_items(
            db, uid, rel.id, query=None
        )
        result_ids = {r.get("id") for r in results}
        ok("空 query → 近因（返回列表含最新行）",
           isinstance(results, list) and fresh["id"] in result_ids,
           f"n={len(results) if isinstance(results, list) else '-'}")

        # ---- (d) 工具 schema 无身份字段 ----
        print("\n(d) 工具 schema：无 user_id/relation_id")
        from app.agent.tools import build_agent_tools

        tools = build_agent_tools(user_id=uid, relation_id=rel.id)
        mem_tool = next(t for t in tools if t.name == "get_ai_memory")
        props = list(mem_tool.args_schema.model_json_schema()["properties"].keys())
        ok("get_ai_memory 只剩 query", props == ["query"], str(props))

        # ---- (e) ensure_started 关态 no-op ----
        print("\n(e) ensure_started：DUAL_WRITE=0 → 线程不启动")
        from app.services import memory_pipeline_worker as mpw

        mpw.stop_for_tests()
        mpw.ensure_started()
        ok("线程未启动", not mpw.is_running())
        mpw.stop_for_tests()

    finally:
        _cleanup(db)
        end = _baseline(db)
        ok("任务/记忆基线收尾相等", base == end, f"{base} vs {end}")
        db.close()
        app_config.MEMORY_ASSERTION_DUAL_WRITE = orig_flags["dual"]
        app_config.MEMORY_ASSERTION_READ_V3 = orig_flags["read"]
        app_config.MEMORY_ASSERTION_REQUIRE_COMPLETE = orig_flags["strict"]
        app_config.MEMORY_ASSERTION_INDEX_WORKER = orig_flags["worker"]

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：")
        for item in FAILURES:
            print(f"  ❌ {item}")
        print("=" * 72)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
