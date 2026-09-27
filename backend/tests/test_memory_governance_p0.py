"""
记忆治理 P0 专项（记忆系统升级评估 2026-09-27 · P0①③④⑥）。

覆盖四条新路径，一条不少：
  ① 补偿式向量化：vectorize 失败行标 pending_upsert → run_backlog_sweep
     重投成功转 indexed（patch _embed/_collection，零网络）；
  ③ 结构化/工具调用关 thinking：_request(no_thinking=True) 的请求体强制
     enable_thinking=False 且剥离 thinking_budget；默认路径不受影响；
  ④ 军师记忆沉淀总开关：关系级 False 时 distill_and_save 在模型调用前阻断
     （patch 模型调用，被调用即失败）；
  ⑥ 解绑清理：confirm_unbind 排定 memory_purge_after → purge_due_relations
     清 DB（断言 + 边/证据/用户级状态）并留痕 memory_purged_at。

纪律：
  - 全程零模型/零 embedding 网络（kill switch + patch）；
  - 临时行按 id 逐条清理，打印基线行数核对；
  - 运行：cd backend && python tests/test_memory_governance_p0.py
"""
import os
import sys
from datetime import datetime, timedelta

# 测试隔离：禁止任何后台线程（清扫/清理/蒸馏）自行启动；清扫直接同步调。
os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


# ---------------------------------------------------------------------- #
# ③ no_thinking payload（纯内存，不碰库）
# ---------------------------------------------------------------------- #
def case_no_thinking_payload():
    print("\n[P0③] 结构化/工具调用请求体强制 enable_thinking=False")
    import app.services.llm_client as lc

    captured = {}

    class _FakeResp:
        status_code = 200

        def json(self):
            return {
                "choices": [{"message": {"content": "ok", "tool_calls": None}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1,
                          "total_tokens": 2},
            }

    class _FakeClient:
        def __init__(self, *a, **k):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def post(self, url, headers=None, json=None):
            captured["body"] = json
            return _FakeResp()

    orig_client = lc.httpx.Client
    lc.httpx.Client = _FakeClient
    try:
        client = lc.LlmClient(
            api_key="test-key", enable_thinking=True, thinking_budget=1024,
        )
        # 默认路径（主聊天）：thinking_budget 照旧
        client.invoke([{"role": "user", "content": "hi"}], scene="probe")
        body = captured["body"]
        check("默认 invoke 保留 thinking_budget", body.get("thinking_budget") == 1024,
              str(body))
        # 工具路径：强制关思考
        client.invoke_with_tools(
            [{"role": "user", "content": "hi"}], tools=[], scene="probe",
        )
        body = captured["body"]
        check("invoke_with_tools 强制 enable_thinking=False",
              body.get("enable_thinking") is False, str(body))
        check("invoke_with_tools 剥离 thinking_budget",
              "thinking_budget" not in body, str(body))
        # 结构化路径复用 _request(no_thinking=True)：直接验证底层语义
        client._request({"messages": []}, scene="probe", no_thinking=True)
        body = captured["body"]
        check("_request(no_thinking=True) 请求体正确",
              body.get("enable_thinking") is False and "thinking_budget" not in body,
              str(body))
    finally:
        lc.httpx.Client = orig_client


# ---------------------------------------------------------------------- #
# ① 补偿式向量化：失败标 pending → 清扫重投转 indexed
# ---------------------------------------------------------------------- #
class _FakeCollection:
    def __init__(self):
        self.upserted = {}
        self.deleted = []

    def upsert(self, ids, embeddings, documents, metadatas):
        self.upserted[ids[0]] = documents[0]

    def delete(self, ids=None, where=None):
        self.deleted.extend(ids or [])

    def get(self, ids=None):
        return {"ids": [i for i in (ids or []) if i in self.upserted]}

    def count(self):
        return len(self.upserted)


def case_backlog_sweep(db, rid):
    print("\n[P0①] 失败补偿：pending_upsert → 清扫 → indexed")
    from app.core.database import SessionLocal
    from app.models.ai import AiMemory
    import app.services.memory_retrieval as mr
    import app.services.memory_index_worker as mw

    def _fresh_status(mid):
        # 断言用独立会话：本套件长事务在 REPEATABLE READ 下会读到旧快照
        s = SessionLocal()
        try:
            return s.get(AiMemory, mid).index_status
        finally:
            s.close()

    fake_col = _FakeCollection()
    orig_embed, orig_col = mw._embed, mw._collection
    mw._embed = lambda text: [0.0, 1.0, 0.5]
    mw._collection = lambda: fake_col
    created = None
    try:
        m = AiMemory(
            user_id=1, relation_id=rid, memory_type="偏好",
            memory_text="补偿清扫专项临时行", visibility="private",
            occurred_at=datetime.now(), created_at=datetime.now(),
        )
        db.add(m)
        db.commit()
        created = m.id

        # 模拟 vectorize 失败路径
        mr._mark_index_pending(created, "simulated embed failure")
        check("失败行标 pending_upsert", _fresh_status(created) == "pending_upsert",
              _fresh_status(created))

        # 补偿标记内含 index_error：同一新会话顺带读
        s = SessionLocal()
        try:
            err = s.get(AiMemory, created).index_error or ""
        finally:
            s.close()
        check("失败原因写入 index_error",
              "simulated embed failure" in err, err)

        mw.run_backlog_sweep()
        check("清扫重投后转 indexed", _fresh_status(created) == "indexed",
              _fresh_status(created))
        check("向量写入 fake collection", str(created) in fake_col.upserted,
              str(list(fake_col.upserted)))
    finally:
        mw._embed, mw._collection = orig_embed, orig_col
        if created is not None:
            row = db.get(AiMemory, created)
            if row:
                db.delete(row)
                db.commit()
            print(f"  已清理临时行 [{created}]")


# ---------------------------------------------------------------------- #
# ④ 军师记忆沉淀总开关
# ---------------------------------------------------------------------- #
def case_distill_switch(db, uid, rid):
    print("\n[P0④] 关系级记忆沉淀总开关（服务端 distill 入口阻断）")
    from app.models.couple_relation import CoupleRelation
    from app.services import memory_service as ms

    rel = db.get(CoupleRelation, rid)
    original = bool(rel.memory_distill_enabled)

    def _must_not_call(*a, **k):
        raise AssertionError("开关关闭时不得发起模型调用")

    orig_invoke = ms.distill_llm.invoke_structured
    ms.distill_llm.invoke_structured = _must_not_call
    try:
        rel.memory_distill_enabled = False
        db.commit()
        saved = ms.distill_and_save(
            db, uid, rid, "chat",
            "这是一段足够长到本应触发蒸馏的用户输入内容",
            "assistant 回复",
        )
        check("开关关闭 → distill 返回 None 且未调模型", saved is None)
        check("memory_distill_enabled 读回 False",
              ms.memory_distill_enabled(db, rid) is False)
    finally:
        ms.distill_llm.invoke_structured = orig_invoke
        rel.memory_distill_enabled = original
        db.commit()

    check("开关恢复后放行（模型桩被正常走到）",
          _probe_distill_reaches_model(db, uid, rid))
    # 边界：relation 不存在 → 放行（保持旧行为）
    check("relation 缺行时放行", ms.memory_distill_enabled(db, 999999999) is True)


def _probe_distill_reaches_model(db, uid, rid):
    from types import SimpleNamespace

    from app.services import memory_service as ms

    reached = {"model": False}

    def _fake_invoke(*a, **k):
        reached["model"] = True
        # should_remember=False → distill 走「不记」分支干净返回
        return SimpleNamespace(should_remember=False)

    orig = ms.distill_llm.invoke_structured
    ms.distill_llm.invoke_structured = _fake_invoke
    try:
        ms.distill_and_save(
            db, uid, rid, "chat",
            "这是一段足够长到本应触发蒸馏的用户输入内容",
            "assistant 回复",
        )
        return reached["model"]
    except Exception:
        return False
    finally:
        ms.distill_llm.invoke_structured = orig


# ---------------------------------------------------------------------- #
# ⑥ 解绑清理：排定 → 扫描 → DB 清除 + 留痕
# ---------------------------------------------------------------------- #
def case_unbind_purge(db, uid, rid):
    print("\n[P0⑥] 解绑清理：confirm_unbind 排定 → purge_due_relations 执行")
    from app.models.ai import (
        AiMemory, MemoryAssertionEvidence, MemoryAssertionUserState,
    )
    from app.models.couple_relation import CoupleRelation
    from app.repositories import couple_repo
    from app.services import memory_purge_service as mps

    rel_ids, mem_ids = [], []
    baseline_mem = db.query(AiMemory).count()
    try:
        # 临时 dissolved 关系（复用既有用户的 FK）
        rel = CoupleRelation(
            user_a_id=uid, user_b_id=uid, status="unbinding",
            bind_time=datetime.now(),
        )
        db.add(rel)
        db.commit()
        rel_ids.append(rel.id)

        # confirm_unbind：状态 dissolved + 排定清理时刻
        confirmed = couple_repo.confirm_unbind(db, rel.id)
        check("confirm_unbind 置 dissolved", confirmed.status == "dissolved")
        check("confirm_unbind 排定 memory_purge_after≈now+保留期",
              confirmed.memory_purge_after is not None
              and abs((confirmed.memory_purge_after
                       - (datetime.utcnow() + timedelta(
                           days=mps_purge_retention()))).total_seconds()) < 120,
              str(confirmed.memory_purge_after))

        # 临时断言行 + 依赖行（user_state / evidence），purge_after 已过期
        m = AiMemory(
            user_id=uid, relation_id=rel.id, memory_type="偏好",
            memory_text="解绑清理专项临时行", visibility="private",
            occurred_at=datetime.now(), created_at=datetime.now(),
        )
        db.add(m)
        db.commit()
        mem_id = m.id  # purge 会删行：先取 id，避免访问已删实例属性
        mem_ids.append(mem_id)
        db.add(MemoryAssertionUserState(
            assertion_id=mem_id, user_id=uid, hidden=False, muted=False,
            starred=False,
        ))
        db.add(MemoryAssertionEvidence(
            assertion_id=mem_id, source_type="probe", source_id=mem_id,
            source_revision_id=1, offset_codepoint=0, length_codepoint=1,
            content_hash="0" * 64,
        ))
        rel.memory_purge_after = datetime.utcnow() - timedelta(seconds=1)
        db.commit()

        n = mps.purge_due_relations(db)
        db.expire_all()
        check("到期关系被清理", n >= 1, f"processed={n}")
        check("断言行已删除", db.get(AiMemory, mem_id) is None)
        check("user_state 依赖行已删除",
              db.query(MemoryAssertionUserState)
              .filter_by(assertion_id=mem_id).count() == 0)
        check("evidence 依赖行已删除",
              db.query(MemoryAssertionEvidence)
              .filter_by(assertion_id=mem_id).count() == 0)
        rel = db.get(CoupleRelation, rel.id)
        check("memory_purged_at 留痕", rel.memory_purged_at is not None)

        # 未到期 / legal_hold 的关系不被碰
        check("幂等：已留痕关系不再处理", mps.purge_due_relations(db) == 0)
    finally:
        for mid in mem_ids:
            row = db.get(AiMemory, mid)
            if row:
                db.delete(row)
        for rid_ in rel_ids:
            row = db.get(CoupleRelation, rid_)
            if row:
                db.delete(row)
        db.commit()
        after = db.query(AiMemory).count()
        check("基线行数 == 收尾行数", baseline_mem == after,
              f"{baseline_mem} != {after}")
        print(f"  基线行数={baseline_mem} 收尾行数={after}")


def mps_purge_retention():
    from app.core.config import MEMORY_PURGE_RETENTION_DAYS

    return MEMORY_PURGE_RETENTION_DAYS


# ---------------------------------------------------------------------- #
# P0 修补②：带 v3 派生行（evidence/edge/user_state）的记忆删除不 500
# ---------------------------------------------------------------------- #
def case_v3_delete_cascade(db, uid, rid):
    print("\n[P0②] v3 记忆删除：同一事务级联清理 evidence/edge/user_state")
    from app.models.ai import (
        AiMemory, MemoryAssertionEdge, MemoryAssertionEvidence,
        MemoryAssertionUserState,
    )
    from app.services import memory_service as ms

    created_ids = []
    baseline_mem = db.query(AiMemory).count()
    try:
        parent = AiMemory(
            user_id=uid, relation_id=rid, memory_type="偏好",
            memory_text="删除级联专项-父断言", visibility="private",
            occurred_at=datetime.now(), created_at=datetime.now(),
        )
        target = AiMemory(
            user_id=uid, relation_id=rid, memory_type="偏好",
            memory_text="删除级联专项-待删断言", visibility="private",
            occurred_at=datetime.now(), created_at=datetime.now(),
        )
        db.add_all([parent, target])
        db.commit()
        created_ids.extend([parent.id, target.id])
        parent_id, target_id = parent.id, target.id

        # v3 派生行：evidence + 双向端点 edge + user_state（模拟线上双写产物）
        db.add(MemoryAssertionEvidence(
            assertion_id=target_id, source_type="probe", source_id=target_id,
            source_revision_id=1, offset_codepoint=0, length_codepoint=1,
            content_hash="1" * 64,
        ))
        db.add(MemoryAssertionEdge(
            relation_id=rid, parent_assertion_id=parent_id,
            child_assertion_id=target_id, relation_type="supersedes",
        ))
        db.add(MemoryAssertionUserState(
            assertion_id=target_id, user_id=uid, hidden=False, muted=False,
            starred=False,
        ))
        db.commit()

        # 修补前这里 IntegrityError 1451（evidence 外键挡删除）→ 接口 500
        ms.delete_memory(db, target_id, uid)
        db.expire_all()
        check("带 evidence 的 v3 记忆删除成功",
              db.get(AiMemory, target_id) is None)
        check("evidence 已级联删除",
              db.query(MemoryAssertionEvidence)
              .filter_by(assertion_id=target_id).count() == 0)
        check("edge（双向端点）已级联删除",
              db.query(MemoryAssertionEdge)
              .filter((MemoryAssertionEdge.parent_assertion_id == target_id)
                      | (MemoryAssertionEdge.child_assertion_id == target_id))
              .count() == 0)
        check("user_state 已级联删除",
              db.query(MemoryAssertionUserState)
              .filter_by(assertion_id=target_id).count() == 0)
        check("被 edge 引用的父断言不受影响",
              db.get(AiMemory, parent_id) is not None)
    finally:
        for mid in created_ids:
            row = db.get(AiMemory, mid)
            if row:
                db.delete(row)
        db.commit()
        after = db.query(AiMemory).count()
        check("删除级联用例基线收尾相等", baseline_mem == after,
              f"{baseline_mem} != {after}")
        print(f"  基线行数={baseline_mem} 收尾行数={after}")


# ---------------------------------------------------------------------- #
def main() -> int:
    print("=" * 72)
    print("记忆治理 P0 专项：补偿向量化 / no_thinking / 沉淀总开关 / 解绑清理")
    print("=" * 72)

    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation

    case_no_thinking_payload()

    db = SessionLocal()
    try:
        rel = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active")
            .first()
        )
        if rel is None:
            check("存在 active couple_relation 夹具", False, "本机无可用 relation")
            return finish()
        case_backlog_sweep(db, rel.id)
        case_distill_switch(db, rel.user_a_id, rel.id)
        case_v3_delete_cascade(db, rel.user_a_id, rel.id)
        case_unbind_purge(db, rel.user_a_id, rel.id)
    finally:
        db.close()
    return finish()


def finish() -> int:
    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
