"""索引接线 generation CAS 用例（真库；附录 §4.3 前 4 类 + 同 id 幂等重放）。

覆盖：
  [1] 删除（archive）发生在 embedding 期间 → 最终无向量、行 removed
  [2] 删除发生在 upsert 后、最终复查前 → 复查撤下向量、行 removed
  [3] 两个 index worker 同时领取 → 仅一个进入 indexing
  [4] CAS 影响 0 行（refresh 后、CAS 前被并发改写）→ 立即删向量，不标 indexed
  [5] 同 id 幂等重放：重放同一 work 为 no-op；回到 pending_upsert 重跑仍单向量

注入：patch `memory_index_worker._collection` / `memory_index_worker._embed`
为进程内 fake；[4] 用 engine 级 before_cursor_execute 在 CAS 语句执行前
从**另一会话** archive 行，制造 rowcount==0 窗口。

数据隔离：每个用例在用例开始时才建 (task, memory)（保证 claim 的
全局 pending 队列里只有当前用例行）；finally 按 id 删行，基线首尾相等；
开跑前守卫——库里不得有非本测试的 pending_upsert/pending_remove 行。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_index_cas.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []

_TASK_IDS = []
_MEM_IDS = []


def ok(name: str, passed: bool, detail: str = ""):
    mark = "PASS" if passed else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


class FakeCollection:
    """chroma 桩：upsert 覆盖写（同 id 单向量）、delete 幂等；可挂 on_upsert 钩子。"""

    def __init__(self):
        self.vectors = {}  # str id -> embedding
        self.upsert_ids = []
        self.delete_ids = []
        self.on_upsert = None

    def upsert(self, ids, embeddings, documents, metadatas):
        for vid, vec in zip(ids, embeddings):
            self.vectors[str(vid)] = vec
            self.upsert_ids.append(str(vid))
        if self.on_upsert is not None:
            hook, self.on_upsert = self.on_upsert, None  # 只触发一次
            hook()

    def delete(self, ids):
        for vid in ids:
            self.vectors.pop(str(vid), None)
            self.delete_ids.append(str(vid))


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
    from app.models.ai import AiMemory, MemoryPipelineTask

    if _MEM_IDS:
        db.query(AiMemory).filter(AiMemory.id.in_(_MEM_IDS)).delete(
            synchronize_session=False
        )
    if _TASK_IDS:
        db.query(MemoryPipelineTask).filter(
            MemoryPipelineTask.id.in_(_TASK_IDS)
        ).delete(synchronize_session=False)
    db.commit()


def _archive_in_fresh_session(mem_id: int) -> None:
    """另一会话执行断言离场（§2.4 联动：generation+1、index_status→pending_remove）。"""
    from app.core.database import SessionLocal
    from app.models.ai import AiMemory
    from app.services.memory_status_service import transition_assertion

    dbx = SessionLocal()
    try:
        row = dbx.get(AiMemory, mem_id)
        if row is not None and row.status == "active":
            transition_assertion(dbx, row, "archived", reason="cas_test")
            dbx.commit()
    finally:
        dbx.close()


def main() -> int:
    print("=" * 72)
    print("索引接线 generation CAS（附录 §4.3）")
    print("=" * 72)

    import secrets

    from sqlalchemy import event

    from app.core.database import SessionLocal, engine
    from app.models.ai import AiMemory, MemoryPipelineTask
    from app.models.couple_relation import CoupleRelation
    from app.services import memory_index_worker
    from app.services.memory_status_service import transition_index

    fake = FakeCollection()
    orig_collection = memory_index_worker._collection
    orig_embed = memory_index_worker._embed
    #: embed 钩子（用例 [1]：embedding 期间另一会话 archive）
    embed_hook = {"fn": None}

    def _fake_embed(text: str):
        if embed_hook["fn"] is not None:
            return embed_hook["fn"](text)
        return [0.5, 0.5, 0.5, 0.5]

    memory_index_worker._collection = lambda: fake
    memory_index_worker._embed = _fake_embed

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
        uid = rel.user_a_id

        def _mk_case(i: int) -> dict:
            """按需创建用例 i 的 (task, memory)，pending_upsert；保证同一时刻
            全局 pending 队列里只有当前用例行（claim 无法按 relation 过滤）。"""
            task = MemoryPipelineTask(
                relation_id=rel.id,
                requested_by_user_id=uid,
                trigger_kind="chat_turn",
                source_type="chat_message",
                source_id=990000 + i,  # 无 FK，测试专用
                pipeline_version="v3.2.0",
                idempotency_key=secrets.token_hex(16),
                state="indexing",  # T4 已完成，等待索引
            )
            db.add(task)
            db.flush()
            _TASK_IDS.append(task.id)
            mem = AiMemory(
                user_id=uid,
                relation_id=rel.id,
                memory_type="偏好",
                memory_text=f"CAS索引用例{i}独有文本",
                visibility="private",
                index_status="pending_upsert",
                pipeline_task_id=task.id,
            )
            db.add(mem)
            db.flush()
            _MEM_IDS.append(mem.id)
            db.commit()
            return {"task_id": task.id, "mem_id": mem.id}

        # claim 是全局领取：库里若有别人的 pending 行会被误领
        foreign = [
            r[0]
            for r in db.query(AiMemory.id)
            .filter(AiMemory.index_status.in_(("pending_upsert", "pending_remove")))
            .all()
        ]
        if foreign:
            print("库里有非本测试的待索引行，无法安全领取：%s" % foreign)
            return 1

        def _row(mem_id):
            # 先结束事务：MySQL REPEATABLE READ 下旧快照会读不到
            # 其他会话（如 [3] 的 db1）刚提交的状态
            db.commit()
            db.expire_all()
            return db.get(AiMemory, mem_id)

        def _task(task_id):
            db.commit()
            db.expire_all()
            return db.get(MemoryPipelineTask, task_id)

        # ---- [1] archive 发生在 embedding 期间 ----
        print("\n[1] archive 发生在 embedding 期间 → 最终无向量、removed")
        case1 = _mk_case(1)

        def _embed_mid_archive(text):
            embed_hook["fn"] = None  # 只触发一次
            _archive_in_fresh_session(case1["mem_id"])
            return [0.5, 0.5, 0.5, 0.5]

        embed_hook["fn"] = _embed_mid_archive
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("领到用例1", len(works) == 1 and works[0]["id"] == case1["mem_id"],
           str(works))
        # `works` 可能为空（领取失败）：直接取works[0] 会抛 IndexError，
        # 整个套件在 finally 清理之前崩溃 → 残留脏任务/脏行，
        # 进而污染后续套件（如 test_memory_retrieval 召回不到向量）。
        # 这里记失败并跳到下一个用例，保证清理一定执行。
        if not works:
            ok("用例1 领取索引工作（跳过：无可领任务）", False, str(works))
            return
        memory_index_worker.process_index_work(db, works[0])
        row1 = _row(case1["mem_id"])
        ok("复查发现离场 → 向量已撤下（最终无向量）",
           str(case1["mem_id"]) not in fake.vectors)
        ok("行转 pending_remove（archive 联动）且 archived",
           row1.index_status == "pending_remove" and row1.status == "archived",
           f"{row1.index_status}/{row1.status}")
        # 删除任务收尾
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("领取删除工作", len(works) == 1 and works[0]["kind"] == "remove",
           str(works))
        if not works:
            ok("领取删除工作（跳过：无可领任务）", False, str(works))
            return
        memory_index_worker.process_index_work(db, works[0])
        row1 = _row(case1["mem_id"])
        ok("行 removed、无向量",
           row1.index_status == "removed"
           and str(case1["mem_id"]) not in fake.vectors,
           row1.index_status)
        ok("任务终结 completed", _task(case1["task_id"]).state == "completed",
           _task(case1["task_id"]).state)

        # ---- [2] archive 发生在 upsert 后、最终复查前 ----
        print("\n[2] archive 发生在 upsert 后、最终复查前 → 复查删除向量")
        case2 = _mk_case(2)
        fake.on_upsert = lambda: _archive_in_fresh_session(case2["mem_id"])
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("领到用例2", len(works) == 1 and works[0]["id"] == case2["mem_id"],
           str(works))
        memory_index_worker.process_index_work(db, works[0])
        row2 = _row(case2["mem_id"])
        ok("向量确已写入过（upsert 被调用）",
           str(case2["mem_id"]) in fake.upsert_ids)
        ok("最终复查撤下向量", str(case2["mem_id"]) not in fake.vectors)
        ok("未标 indexed，行 pending_remove/archived",
           row2.index_status == "pending_remove" and row2.status == "archived",
           f"{row2.index_status}/{row2.status}")
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("领取删除工作",
           len(works) == 1 and works[0]["kind"] == "remove"
           and works[0]["id"] == case2["mem_id"],
           str(works))
        memory_index_worker.process_index_work(db, works[0])
        row2 = _row(case2["mem_id"])
        ok("行 removed、任务 completed",
           row2.index_status == "removed"
           and _task(case2["task_id"]).state == "completed",
           f"{row2.index_status}/{_task(case2['task_id']).state}")

        # ---- [3] 双 worker 竞争 ----
        print("\n[3] 两个 index worker 同时领取 → 仅一个进入 indexing")
        case3 = _mk_case(3)
        db1 = SessionLocal()
        db2 = SessionLocal()
        try:
            w1 = memory_index_worker.claim_index_work(db1, limit=1)
            w2 = memory_index_worker.claim_index_work(db2, limit=1)
            ok("worker1 领到用例3",
               len(w1) == 1 and w1[0]["id"] == case3["mem_id"], str(w1))
            ok("worker2 领不到（乐观 UPDATE 落空）", w2 == [], str(w2))
            row3 = _row(case3["mem_id"])
            ok("行已进 indexing", row3.index_status == "indexing",
               row3.index_status)
            memory_index_worker.process_index_work(db1, w1[0])
            row3 = _row(case3["mem_id"])
            ok("胜者完成 indexed、任务 completed",
               row3.index_status == "indexed"
               and _task(case3["task_id"]).state == "completed",
               f"{row3.index_status}/{_task(case3['task_id']).state}")
            ok("向量在库", str(case3["mem_id"]) in fake.vectors)
        finally:
            db1.close()
            db2.close()

        # ---- [4] CAS rowcount==0 → 立即删向量 ----
        print("\n[4] CAS 前被并发改写 → rowcount 0 → 删向量、不标 indexed")
        case4 = _mk_case(4)
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("领到用例4", len(works) == 1 and works[0]["id"] == case4["mem_id"],
           str(works))

        # engine 级注入：CAS UPDATE 执行前一刻，另一会话 archive（gen+1）
        armed = {"on": True}

        def _hook(conn, cursor, statement, parameters, context, executemany):
            if "indexed_generation" not in statement:
                return
            if armed["on"]:
                armed["on"] = False  # 先撤防，避免嵌套语句重入
                _archive_in_fresh_session(case4["mem_id"])

        event.listen(engine, "before_cursor_execute", _hook)
        try:
            memory_index_worker.process_index_work(db, works[0])
        finally:
            event.remove(engine, "before_cursor_execute", _hook)

        row4 = _row(case4["mem_id"])
        ok("并发改写发生在 refresh 之后 → 未标 indexed",
           row4.index_status != "indexed", row4.index_status)
        ok("CAS 落空 → 本次向量已立即删除",
           str(case4["mem_id"]) not in fake.vectors)
        ok("行 pending_remove/archived（注入的离场）",
           row4.index_status == "pending_remove" and row4.status == "archived",
           f"{row4.index_status}/{row4.status}")
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("领取删除工作",
           len(works) == 1 and works[0]["kind"] == "remove"
           and works[0]["id"] == case4["mem_id"],
           str(works))
        memory_index_worker.process_index_work(db, works[0])
        row4 = _row(case4["mem_id"])
        ok("行 removed、任务 completed",
           row4.index_status == "removed"
           and _task(case4["task_id"]).state == "completed",
           f"{row4.index_status}/{_task(case4['task_id']).state}")

        # ---- [5] 同 id 幂等重放 ----
        print("\n[5] 同 id 幂等：work 重放 no-op；回 pending_upsert 重跑单向量")
        case5 = _mk_case(5)
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("领到用例5", len(works) == 1 and works[0]["id"] == case5["mem_id"],
           str(works))
        memory_index_worker.process_index_work(db, works[0])
        row5 = _row(case5["mem_id"])
        ok("首跑 indexed、indexed_generation 对齐",
           row5.index_status == "indexed"
           and row5.indexed_generation == row5.index_generation,
           f"{row5.index_status}/{row5.indexed_generation}/"
           f"{row5.index_generation}")
        before_vec = dict(fake.vectors)

        # 同 work 重放（模拟 upsert 后 kill、状态已推进的重入）
        memory_index_worker.process_index_work(db, dict(works[0]))
        row5 = _row(case5["mem_id"])
        ok("重放同 work → no-op 且状态不变",
           row5.index_status == "indexed"
           and fake.vectors == before_vec,
           row5.index_status)

        # 回到 pending_upsert（indexed→pending_upsert 自带 gen+1）再跑一遍
        db.expire_all()
        transition_index(db, db.get(AiMemory, case5["mem_id"]), "pending_upsert")
        db.commit()
        works = memory_index_worker.claim_index_work(db, limit=1)
        ok("重领成功", len(works) == 1 and works[0]["id"] == case5["mem_id"],
           str(works))
        memory_index_worker.process_index_work(db, works[0])
        row5 = _row(case5["mem_id"])
        ok("重跑 indexed 且仅 1 个向量（同 id 覆盖写）",
           row5.index_status == "indexed"
           and sum(1 for k in fake.vectors if k == str(case5["mem_id"])) == 1,
           row5.index_status)
        ok("任务终结 completed", _task(case5["task_id"]).state == "completed",
           _task(case5["task_id"]).state)

    finally:
        memory_index_worker._collection = orig_collection
        memory_index_worker._embed = orig_embed
        embed_hook["fn"] = None
        fake.on_upsert = None
        _cleanup(db)
        end = _baseline(db)
        ok("任务/记忆基线收尾相等", base == end, f"{base} vs {end}")
        db.close()

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
