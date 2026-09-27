"""蒸馏管线 T1–T4 幂等与出口测试（真库；附录 §3 契约）。

覆盖：
  [1] T1 同源重放 → 仅 1 个任务（唯一键幂等）
  [2] 正常链路 claim → distill → extraction_saved → commit：
      记忆 + 证据 + 任务 completed；extraction_result **不存消息原文**；
      身份/谓词/所有权/证据口径 spot-check
  [3] extraction_saved 崩溃恢复：重新 claim 后**不再调模型**（FakeLLM 计数不变）
  [4] 断言级幂等重放：任务退回 extraction_saved 再 commit → completed_noop，
      记忆行数不变
  [5] lease 未到期不可重领；到期后可重领（distill_attempts 累计）
  [6] should_remember=False → completed_noop（不写库）
  [7] LLM 连续失败 → attempts 耗尽 → failed(DISTILL_EXHAUSTED)
  [8] relation 非 active → completed_skipped（claim 前置守卫）
  [9] INDEX_WORKER=1 → T4 落 pending_upsert、任务转 indexing
  [10] 核心字段不全（memory_text 空）且 attempts 耗尽 → failed(FIELDS_INCOMPLETE)
  [11] 入队后关军师记忆沉淀开关 → 执行阶段复查 completed_skipped 且不调模型

数据隔离：finally 按 id 删行（证据 → 记忆 → 任务 → 消息 → 会话 → 场景 →
测试关系），任务/记忆/证据基线计数首尾相等。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_pipeline_idempotency.py
"""
import json
import os
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []

#: 消息原文哨兵：绝不能出现在 extraction_result / 记忆里
SOURCE_MARKER = "PIPELINE_SOURCE_TEXT_此原文不得入库"

_TASK_IDS = []
_MEMORY_IDS = []
_MSG_IDS = []
_SESSION_ID = None
_SCENE_CREATED = False
_TEST_RELATION_ID = None


def ok(name: str, passed: bool, detail: str = ""):
    mark = "PASS" if passed else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


def _utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


class FakeLLM:
    """结构化输出桩；error 给定则抛出。调用计数用于「不再调模型」断言。"""

    api_key = "test-key"

    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error
        self.calls = 0

    def invoke_structured(self, messages, scene="unknown", **kwargs):
        self.calls += 1
        if self.error:
            raise self.error
        return self.result


def _baseline(db):
    from sqlalchemy import text

    counts = {}
    for table in ("memory_pipeline_task", "ai_memory", "memory_assertion_evidence"):
        counts[table] = db.execute(text(f"SELECT COUNT(*) FROM {table}")).scalar()
    return counts


def _cleanup(db):
    # 失败 flush 会把 session 打成 PendingRollbackError，先清掉才能继续删行
    try:
        db.rollback()
    except Exception:
        pass
    from app.models.ai import (
        AiChatMessage,
        AiChatSession,
        AiMemory,
        MemoryAssertionEvidence,
        MemoryPipelineTask,
    )
    from app.models.couple_relation import CoupleRelation
    from app.models.ai import AiScene

    if _MEMORY_IDS:
        db.query(MemoryAssertionEvidence).filter(
            MemoryAssertionEvidence.assertion_id.in_(_MEMORY_IDS)
        ).delete(synchronize_session=False)
        db.query(AiMemory).filter(AiMemory.id.in_(_MEMORY_IDS)).delete(
            synchronize_session=False
        )
    if _TASK_IDS:
        db.query(MemoryPipelineTask).filter(
            MemoryPipelineTask.id.in_(_TASK_IDS)
        ).delete(synchronize_session=False)
    if _MSG_IDS:
        db.query(AiChatMessage).filter(AiChatMessage.id.in_(_MSG_IDS)).delete(
            synchronize_session=False
        )
    if _SESSION_ID is not None:
        db.query(AiChatSession).filter(AiChatSession.id == _SESSION_ID).delete(
            synchronize_session=False
        )
    if _SCENE_CREATED:
        db.query(AiScene).filter(AiScene.scene_key == "z_pipe_scene").delete(
            synchronize_session=False
        )
    if _TEST_RELATION_ID is not None:
        db.query(CoupleRelation).filter(
            CoupleRelation.id == _TEST_RELATION_ID
        ).delete(synchronize_session=False)
    db.commit()


def _mk_task(db, relation_id, assistant_msg_id, *, requested_by):
    from app.services.memory_pipeline import enqueue_task

    task = enqueue_task(
        db,
        relation_id=relation_id,
        trigger_kind="chat_turn",
        source_type="chat_message",
        source_id=assistant_msg_id,
        requested_by_user_id=requested_by,
    )
    db.commit()
    if task.id is not None and task.id not in _TASK_IDS:
        _TASK_IDS.append(task.id)
    return task


def _refresh(db, task):
    db.expire(task)
    db.refresh(task)
    return task


def main() -> int:
    global _SESSION_ID, _SCENE_CREATED, _TEST_RELATION_ID

    print("=" * 72)
    print("蒸馏管线 T1–T4 幂等与出口（附录 §3）")
    print("=" * 72)

    from app.core import config as app_config
    from app.core.database import SessionLocal
    from app.models.ai import (
        AiChatMessage,
        AiChatSession,
        AiMemory,
        AiScene,
        MemoryAssertionEvidence,
        MemoryPipelineTask,
    )
    from app.models.couple_relation import CoupleRelation
    from app.schemas.ai_output import MemoryDistillOutput
    from app.services import memory_pipeline
    from app.services.memory_evidence_service import evidence_count

    orig_dual = app_config.MEMORY_ASSERTION_DUAL_WRITE
    orig_worker = app_config.MEMORY_ASSERTION_INDEX_WORKER
    orig_strict = app_config.MEMORY_ASSERTION_REQUIRE_COMPLETE
    # 管线语义按「双写开、索引 worker 关」的默认生产形态验证
    app_config.MEMORY_ASSERTION_DUAL_WRITE = True
    app_config.MEMORY_ASSERTION_INDEX_WORKER = False
    app_config.MEMORY_ASSERTION_REQUIRE_COMPLETE = False

    db = SessionLocal()
    base = _baseline(db)
    try:
        # ---- fixtures ----
        rel = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active")
            .order_by(CoupleRelation.id.asc())
            .first()
        )
        if rel is None:
            raise RuntimeError("库里没有 active couple_relation")
        uid, partner_uid = rel.user_a_id, rel.user_b_id

        if db.query(AiScene).filter(AiScene.scene_key == "z_pipe_scene").first() is None:
            db.add(AiScene(scene_key="z_pipe_scene", name="管线测试场景"))
            # 必须独立 flush：AiScene↔AiChatSession 只有裸 FK 无 relationship，
            # UOW 不会自动排序，同批 flush 会先插子表触发 FK 1452
            db.flush()
            _SCENE_CREATED = True
        sess = AiChatSession(
            user_id=uid,
            relation_id=rel.id,
            scene_key="z_pipe_scene",
            partner_user_id=partner_uid,
            status="active",
        )
        db.add(sess)
        db.flush()
        _SESSION_ID = sess.id

        user_msg = AiChatMessage(
            session_id=sess.id, role="user",
            content=f"{SOURCE_MARKER} 我其实希望他直接说不要让我猜",
        )
        db.add(user_msg)
        db.flush()  # 先拿到 user 消息 id，保证 < 所有 assistant 消息
        asst_msgs = [
            AiChatMessage(session_id=sess.id, role="assistant", content=f"回复{i}")
            for i in range(1, 6)
        ]
        for m in asst_msgs:
            db.add(m)
        db.flush()
        _MSG_IDS.extend([user_msg.id] + [m.id for m in asst_msgs])
        db.commit()

        # 测试专用非 active 关系（skipped 出口）
        cooling = CoupleRelation(user_a_id=uid, user_b_id=partner_uid, status="cooling")
        db.add(cooling)
        db.flush()
        _TEST_RELATION_ID = cooling.id
        db.commit()

        # ---- [1] T1 同源重放 ----
        print("\n[1] T1 任务级幂等（同源重放 → 1 task）")
        task1 = _mk_task(db, rel.id, asst_msgs[0].id, requested_by=uid)
        task1_again = _mk_task(db, rel.id, asst_msgs[0].id, requested_by=uid)
        same_key_count = (
            db.query(MemoryPipelineTask)
            .filter(MemoryPipelineTask.idempotency_key == task1.idempotency_key)
            .count()
        )
        ok("两次 enqueue 返回同一任务", task1.id == task1_again.id,
           f"{task1.id} vs {task1_again.id}")
        ok("唯一键只有一行", same_key_count == 1, str(same_key_count))

        # ---- [2] 正常链路 ----
        print("\n[2] 正常链路 claim → distill → commit")
        llm_ok = FakeLLM(MemoryDistillOutput(
            should_remember=True, memory_type="偏好",
            memory_text="对方喜欢吃辣", predicate="preference",
            object_hint="辣", subject_role="self",
        ))
        claimed = memory_pipeline.claim_next_task(db, "test-w1")
        ok("claim 领到任务1", claimed is not None and claimed.id == task1.id)
        ok("claim 即 commit：attempts=1 且持 lease",
           claimed is not None
           and claimed.distill_attempts == 1
           and claimed.locked_until is not None
           and claimed.locked_by == "test-w1")
        memory_pipeline.run_distill(db, claimed, llm_client=llm_ok)
        _refresh(db, task1)
        ok("distill → extraction_saved", task1.state == "extraction_saved",
           task1.state)
        ok("LLM 恰好调用 1 次", llm_ok.calls == 1, str(llm_ok.calls))
        payload = json.dumps(task1.extraction_result, ensure_ascii=False)
        ok("extraction_result 不存消息原文", SOURCE_MARKER not in payload)
        ok("extraction_result 只存 id 引用",
           task1.extraction_result.get("evidence_ref", {}).get("source_id")
           == user_msg.id)

        memory_pipeline.commit_assertions(db, claimed)
        _refresh(db, task1)
        ok("commit → completed（worker 关）", task1.state == "completed",
           task1.state)
        mem = (
            db.query(AiMemory)
            .filter(AiMemory.pipeline_task_id == task1.id)
            .first()
        )
        ok("断言已落库", mem is not None)
        if mem is not None:
            _MEMORY_IDS.append(mem.id)
            ok("legacy 列：visibility=private/source=None",
               mem.visibility == "private" and mem.source is None)
            ok("schema_version=v3", mem.schema_version == "v3",
               str(mem.schema_version))
            ok("身份 self_report：reported=attributed=本人",
               mem.reported_by_user_id == uid and mem.attributed_to_user_id == uid
               and mem.epistemic_type == "self_report",
               f"{mem.reported_by_user_id}/{mem.attributed_to_user_id}/"
               f"{mem.epistemic_type}")
            ok("谓词 preference + object_key=food.spicy（词典命中）",
               mem.predicate_code == "preference"
               and mem.object_key == "food.spicy"
               and mem.registry_miss is False,
               f"{mem.predicate_code}/{mem.object_key}/{mem.registry_miss}")
            ok("fact_key/user 主体", mem.fact_key == f"user:{uid} / preference / food.spicy",
               str(mem.fact_key))
            ok("所有权 user/owner=本人",
               mem.ownership_type == "user" and mem.owner_user_id == uid
               and mem.created_by_user_id == uid)
            ok("index_status=skipped（worker 关显式写）",
               mem.index_status == "skipped", mem.index_status)
            ok("记忆文本不是原文", SOURCE_MARKER not in mem.memory_text)
            ok("occurred_at 已回填", mem.occurred_at is not None)
            n_ev = evidence_count(db, mem.id)
            ok("证据恰 1 条", n_ev == 1, str(n_ev))
            ev = (
                db.query(MemoryAssertionEvidence)
                .filter(MemoryAssertionEvidence.assertion_id == mem.id)
                .first()
            )
            ok("证据=前置 user 消息全跨度",
               ev is not None and ev.source_type == "chat_message"
               and ev.source_id == user_msg.id and ev.offset_codepoint == 0
               and ev.length_codepoint > 0,
               f"{ev.source_type if ev else '-'}/{ev.source_id if ev else '-'}")

        # ---- [3] 崩溃恢复：extraction_saved 不再调模型 ----
        print("\n[3] extraction_saved 恢复：不再调模型")
        # 等价于 [2] commit 前崩溃的断点：任务停在 extraction_saved
        db.query(MemoryPipelineTask).filter(
            MemoryPipelineTask.id == task1.id
        ).update({"state": "extraction_saved"}, synchronize_session=False)
        db.commit()
        _refresh(db, task1)
        claimed2 = memory_pipeline.claim_next_task(db, "test-w1")
        ok("重新 claim 领到同一任务", claimed2 is not None and claimed2.id == task1.id)
        llm2 = FakeLLM(llm_ok.result)  # 若被调用，calls 会变
        if claimed2 is not None:
            memory_pipeline.run_distill(db, claimed2, llm_client=llm2)
        ok("已 extraction_saved → 不调模型", llm2.calls == 0, str(llm2.calls))

        # ---- [4] 断言级幂等重放 ----
        print("\n[4] 断言级幂等（任务退回 extraction_saved 再 commit）")
        db.query(MemoryPipelineTask).filter(
            MemoryPipelineTask.id == task1.id
        ).update({"state": "extraction_saved"}, synchronize_session=False)
        db.commit()
        _refresh(db, task1)
        before_count = (
            db.query(AiMemory).filter(AiMemory.pipeline_task_id == task1.id).count()
        )
        memory_pipeline.commit_assertions(db, task1)
        _refresh(db, task1)
        after_count = (
            db.query(AiMemory).filter(AiMemory.pipeline_task_id == task1.id).count()
        )
        ok("重放 → completed_noop", task1.state == "completed_noop", task1.state)
        ok("重放不写第二条记忆", before_count == after_count == 1,
           f"{before_count}→{after_count}")

        # ---- [5] lease 门 ----
        print("\n[5] lease 到期门（未到期不可重领，到期可重领）")
        task2 = _mk_task(db, rel.id, asst_msgs[1].id, requested_by=uid)
        c1 = memory_pipeline.claim_next_task(db, "test-w1")
        ok("领到任务2", c1 is not None and c1.id == task2.id)
        c2 = memory_pipeline.claim_next_task(db, "test-w2")
        ok("lease 未到期 → 领不到", c2 is None, str(c2 and c2.id))
        db.query(MemoryPipelineTask).filter(
            MemoryPipelineTask.id == task2.id
        ).update(
            {"locked_until": _utcnow() - timedelta(seconds=5)},
            synchronize_session=False,
        )
        db.commit()
        c3 = memory_pipeline.claim_next_task(db, "test-w2")
        ok("lease 过期 → 可重领", c3 is not None and c3.id == task2.id)
        ok("重领后 attempts 累计=2",
           c3 is not None and c3.distill_attempts == 2,
           str(c3 and c3.distill_attempts))

        # ---- [6] noop 出口 ----
        print("\n[6] should_remember=False → completed_noop")
        llm_no = FakeLLM(MemoryDistillOutput(should_remember=False, memory_text=""))
        memory_pipeline.run_distill(db, c3, llm_client=llm_no)
        _refresh(db, task2)
        ok("noop 出口", task2.state == "completed_noop", task2.state)
        ok("noop 不写库",
           db.query(AiMemory).filter(AiMemory.pipeline_task_id == task2.id).count()
           == 0)

        # ---- [7] failed 出口（attempts 耗尽）----
        print("\n[7] LLM 连续失败 → failed(DISTILL_EXHAUSTED)")
        task3 = _mk_task(db, rel.id, asst_msgs[2].id, requested_by=uid)
        llm_err = FakeLLM(error=RuntimeError("模拟模型不可用"))
        last = None
        for _ in range(memory_pipeline.MAX_DISTILL_ATTEMPTS):
            last = memory_pipeline.claim_next_task(db, "test-w1")
            if last is None or last.id != task3.id:
                break
            memory_pipeline.run_distill(db, last, llm_client=llm_err)
            _refresh(db, task3)
            if task3.state == "failed":
                break
            # 清退避门，模拟时间流逝
            db.query(MemoryPipelineTask).filter(
                MemoryPipelineTask.id == task3.id
            ).update(
                {"next_retry_at": None, "locked_until": None},
                synchronize_session=False,
            )
            db.commit()
        _refresh(db, task3)
        ok("重试耗尽 → failed", task3.state == "failed", task3.state)
        ok("error_code=DISTILL_EXHAUSTED",
           task3.last_error_code == "DISTILL_EXHAUSTED",
           str(task3.last_error_code))
        ok("模型共调用 MAX 次", llm_err.calls == memory_pipeline.MAX_DISTILL_ATTEMPTS,
           str(llm_err.calls))

        # ---- [8] skipped 出口 ----
        print("\n[8] relation 非 active → completed_skipped")
        task4 = _mk_task(db, _TEST_RELATION_ID, asst_msgs[3].id, requested_by=uid)
        c4 = memory_pipeline.claim_next_task(db, "test-w1")
        ok("领到任务4", c4 is not None and c4.id == task4.id)
        memory_pipeline.run_distill(db, c4, llm_client=llm_ok)
        _refresh(db, task4)
        ok("skipped 出口", task4.state == "completed_skipped", task4.state)

        # ---- [9] INDEX_WORKER=1 → pending_upsert + indexing ----
        print("\n[9] INDEX_WORKER=1：T4 落 pending_upsert、任务转 indexing")
        app_config.MEMORY_ASSERTION_INDEX_WORKER = True
        task5 = _mk_task(db, rel.id, asst_msgs[4].id, requested_by=uid)
        c5 = memory_pipeline.claim_next_task(db, "test-w1")
        ok("领到任务5", c5 is not None and c5.id == task5.id)
        # 独立记忆文本：避免撞 task1 已落库文本触发跨任务去重 completed_noop
        llm_idx = FakeLLM(MemoryDistillOutput(
            should_remember=True, memory_type="偏好",
            memory_text="对方不吃香菜", predicate="preference",
            object_hint="香菜", subject_role="self",
        ))
        memory_pipeline.run_distill(db, c5, llm_client=llm_idx)
        _refresh(db, task5)
        memory_pipeline.commit_assertions(db, c5)
        _refresh(db, task5)
        ok("任务转 indexing（等待索引 worker）", task5.state == "indexing",
           task5.state)
        mem5 = (
            db.query(AiMemory)
            .filter(AiMemory.pipeline_task_id == task5.id)
            .first()
        )
        if mem5 is not None:
            _MEMORY_IDS.append(mem5.id)
            ok("断言 index_status=pending_upsert",
               mem5.index_status == "pending_upsert", mem5.index_status)
        else:
            ok("断言已落库", False)

        # ---- [10] 核心字段不全 → failed(FIELDS_INCOMPLETE) ----
        print("\n[10] memory_text 空 + attempts 耗尽 → failed(FIELDS_INCOMPLETE)")
        app_config.MEMORY_ASSERTION_INDEX_WORKER = False
        # 独立源：user_msg 充当 source_id（其余任务都用 assistant 消息）
        task6 = _mk_task(db, rel.id, user_msg.id, requested_by=uid)
        db.query(MemoryPipelineTask).filter(
            MemoryPipelineTask.id == task6.id
        ).update(
            {"state": "extraction_saved",
             "distill_attempts": memory_pipeline.MAX_DISTILL_ATTEMPTS,
             "extraction_result": {"should_remember": True, "memory_text": ""}},
            synchronize_session=False,
        )
        db.commit()
        _refresh(db, task6)
        memory_pipeline.commit_assertions(db, task6)
        _refresh(db, task6)
        ok("字段不全 → failed", task6.state == "failed", task6.state)
        ok("error_code=FIELDS_INCOMPLETE",
           task6.last_error_code == "FIELDS_INCOMPLETE",
           str(task6.last_error_code))

        # ---- [11] 军师记忆沉淀开关：执行阶段复查 ----
        print("\n[11] 入队后关沉淀开关 → completed_skipped 且不调模型")
        toggle_msg = AiChatMessage(
            session_id=sess.id, role="assistant", content="回复开关复查",
        )
        db.add(toggle_msg)
        db.flush()
        _MSG_IDS.append(toggle_msg.id)
        db.commit()
        task7 = _mk_task(db, rel.id, toggle_msg.id, requested_by=uid)
        orig_enabled = bool(rel.memory_distill_enabled)
        try:
            rel.memory_distill_enabled = False
            db.commit()
            c7 = memory_pipeline.claim_next_task(db, "test-w1")
            ok("领到任务7", c7 is not None and c7.id == task7.id,
               str(c7 and c7.id))
            llm_switch = FakeLLM(llm_ok.result)
            if c7 is not None:
                memory_pipeline.run_distill(db, c7, llm_client=llm_switch)
            _refresh(db, task7)
            ok("开关关闭（执行阶段）→ completed_skipped",
               task7.state == "completed_skipped", task7.state)
            ok("开关关闭 → 未发起模型调用", llm_switch.calls == 0,
               str(llm_switch.calls))
        finally:
            rel.memory_distill_enabled = orig_enabled
            db.commit()

    finally:
        _cleanup(db)
        end = _baseline(db)
        ok("任务/记忆/证据基线收尾相等",
           base == end, f"{base} vs {end}")
        db.close()
        app_config.MEMORY_ASSERTION_DUAL_WRITE = orig_dual
        app_config.MEMORY_ASSERTION_INDEX_WORKER = orig_worker
        app_config.MEMORY_ASSERTION_REQUIRE_COMPLETE = orig_strict

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
