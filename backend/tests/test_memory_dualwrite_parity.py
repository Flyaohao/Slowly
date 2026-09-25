"""v3.2 §8 双写平价（flag 关 = v1 字节平价；flag 开 = v3 契约列齐）

用例：
  (a) DUAL_WRITE=0：`_to_dict` 键集 == 冻结基线；schema_version 保持 NULL；
      旧向量化钩子照常调用；
  (b) DUAL_WRITE=1 + INDEX_WORKER=1：schema_version=v3、谓词/身份/fact_key/
      所有权齐、index_status=pending_upsert（显式，不靠 DB 默认）、
      旧钩子**不**调用（单一索引属主）；
  (c) DUAL_WRITE=1 + INDEX_WORKER=0：index_status=skipped、旧钩子照常调用；
  (d) 推导失败（非法 v3_identity）→ 仍落库、schema_version=legacy_pending；
  (e) anniversary 源 → enforce 后归 system_event 四元组。

数据隔离：finally 按 id 删行，基线计数首尾相等。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_dualwrite_parity.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []
_CREATED_IDS = []
_HOOK_CALLS = []

#: 冻结基线：v1 `_to_dict` 键集与顺序（改它 = 改客户端契约）
BASELINE_KEYS = [
    "id", "user_id", "relation_id", "memory_type", "memory_text",
    "visibility", "created_at", "occurred_at", "source", "source_id",
    "importance",
]


def ok(name: str, passed: bool, detail: str = ""):
    mark = "PASS" if passed else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not passed else ""))
    if not passed:
        FAILURES.append(name)


def _baseline() -> int:
    from sqlalchemy import text
    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        return db.execute(text("SELECT COUNT(*) FROM ai_memory")).scalar()
    finally:
        db.close()


def _cleanup(db):
    from app.models.ai import AiMemory

    for row_id in list(_CREATED_IDS):
        db.query(AiMemory).filter(AiMemory.id == row_id).delete(
            synchronize_session=False
        )
        db.commit()
    _CREATED_IDS.clear()


def _get_row(db, memory_id: int):
    from app.models.ai import AiMemory

    return db.query(AiMemory).filter(AiMemory.id == memory_id).first()


def main() -> int:
    print("=" * 72)
    print("v3.2 §8 双写平价")
    print("=" * 72)
    base_count = _baseline()

    from app.core import config as app_config
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation
    from app.services import memory_retrieval
    from app.services import memory_service
    from app.services.memory_identity import Identity

    # 钩子 spy：任何向量化调用都被记录且不真正执行
    def _spy(saved):
        _HOOK_CALLS.append(saved.get("id"))

    orig_hook = memory_retrieval.vectorize_memory_async
    orig_dual = app_config.MEMORY_ASSERTION_DUAL_WRITE
    orig_worker = app_config.MEMORY_ASSERTION_INDEX_WORKER
    db = SessionLocal()
    try:
        memory_retrieval.vectorize_memory_async = _spy
        rel = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active")
            .order_by(CoupleRelation.id.asc())
            .first()
        )
        if rel is None:
            raise RuntimeError("库里没有 active couple_relation")
        uid = rel.user_a_id

        print("\n(a) flag 关 = v1 平价")
        app_config.MEMORY_ASSERTION_DUAL_WRITE = False
        app_config.MEMORY_ASSERTION_INDEX_WORKER = False
        _HOOK_CALLS.clear()
        saved = memory_service.create_memory(
            db, uid, rel.id, "关系事实", "dualwrite parity off row",
            visibility="couple", source="diary",
        )
        _CREATED_IDS.append(saved["id"])
        ok("_to_dict 键集 == 冻结基线",
           list(saved.keys()) == BASELINE_KEYS, str(list(saved.keys())))
        row = _get_row(db, saved["id"])
        ok("schema_version 保持 NULL", row.schema_version is None, str(row.schema_version))
        ok("v3 身份列不被写入",
           row.predicate_code is None and row.fact_key is None
           and row.ownership_type is None, str(row.predicate_code))
        ok("旧向量化钩子照常调用", _HOOK_CALLS == [saved["id"]], str(_HOOK_CALLS))
        ok("index_status 为 DB 默认 skipped", row.index_status == "skipped",
           row.index_status)

        print("\n(b) DUAL_WRITE=1 + INDEX_WORKER=1")
        app_config.MEMORY_ASSERTION_DUAL_WRITE = True
        app_config.MEMORY_ASSERTION_INDEX_WORKER = True
        _HOOK_CALLS.clear()
        saved = memory_service.create_memory(
            db, uid, rel.id, "偏好", "dualwrite parity on row",
            visibility="couple", source="diary", importance=1,
        )
        _CREATED_IDS.append(saved["id"])
        ok("DTO 键集不变（客户端契约不随 flag 变）",
           list(saved.keys()) == BASELINE_KEYS, str(list(saved.keys())))
        row = _get_row(db, saved["id"])
        ok("schema_version=v3", row.schema_version == "v3", str(row.schema_version))
        ok("谓词粗映射：偏好→preference", row.predicate_code == "preference",
           str(row.predicate_code))
        ok("object_key=other + registry_miss（无词典输入）",
           row.object_key == "other" and row.registry_miss is True,
           f"{row.object_key}/{row.registry_miss}")
        ok("fact_key 格式 subject / predicate / object_key",
           row.fact_key == f"user:{uid} / preference / other", str(row.fact_key))
        ok("cardinality=single", row.cardinality == "single", str(row.cardinality))
        ok("所有权：diary→user/owner=本人",
           row.ownership_type == "user" and row.owner_user_id == uid
           and row.created_by_user_id == uid,
           f"{row.ownership_type}/{row.owner_user_id}/{row.created_by_user_id}")
        ok("认识论：diary 蒸馏→unknown/user_message（unknown 不撒谎）",
           row.epistemic_type == "unknown" and row.assertion_origin == "user_message",
           f"{row.epistemic_type}/{row.assertion_origin}")
        ok("身份：reported=attributed=本人",
           row.reported_by_user_id == uid and row.attributed_to_user_id == uid)
        ok("index_status 显式 pending_upsert（不靠 DB 默认）",
           row.index_status == "pending_upsert", row.index_status)
        ok("pipeline_version 落库", row.pipeline_version == "v3.2.0",
           str(row.pipeline_version))
        ok("旧向量化钩子不调用（单一索引属主）", _HOOK_CALLS == [], str(_HOOK_CALLS))

        print("\n(c) DUAL_WRITE=1 + INDEX_WORKER=0")
        app_config.MEMORY_ASSERTION_INDEX_WORKER = False
        _HOOK_CALLS.clear()
        saved = memory_service.create_memory(
            db, uid, rel.id, "关系事实", "dualwrite worker-off row",
            visibility="couple", source="diary",
        )
        _CREATED_IDS.append(saved["id"])
        row = _get_row(db, saved["id"])
        ok("index_status 显式 skipped", row.index_status == "skipped",
           row.index_status)
        ok("旧向量化钩子照常调用", _HOOK_CALLS == [saved["id"]], str(_HOOK_CALLS))

        print("\n(d) 推导失败 → legacy_pending（仍落库）")
        app_config.MEMORY_ASSERTION_INDEX_WORKER = False
        bad_identity = Identity(None, None, "bogus_epistemic", "user_message")
        saved = memory_service.create_memory(
            db, uid, rel.id, "关系事实", "dualwrite legacy_pending row",
            visibility="couple", source="diary", v3_identity=bad_identity,
        )
        _CREATED_IDS.append(saved["id"])
        row = _get_row(db, saved["id"])
        ok("落库成功（推导失败不阻塞）", row is not None)
        ok("schema_version=legacy_pending", row.schema_version == "legacy_pending",
           str(row.schema_version))

        print("\n(e) anniversary 源 → enforce 后 system_event")
        saved = memory_service.create_memory(
            db, uid, rel.id, "事件", "dualwrite anniversary row",
            visibility="couple", source="anniversary",
        )
        _CREATED_IDS.append(saved["id"])
        row = _get_row(db, saved["id"])
        ok("identity=system_event 四元组",
           row.epistemic_type == "system_event"
           and row.assertion_origin == "business_event"
           and row.reported_by_user_id is None
           and row.attributed_to_user_id is None,
           f"{row.epistemic_type}/{row.assertion_origin}")
        ok("anniversary 所有权→relation",
           row.ownership_type == "relation" and row.owner_user_id is None,
           f"{row.ownership_type}/{row.owner_user_id}")
    finally:
        _cleanup(db)
        db.close()
        memory_retrieval.vectorize_memory_async = orig_hook
        app_config.MEMORY_ASSERTION_DUAL_WRITE = orig_dual
        app_config.MEMORY_ASSERTION_INDEX_WORKER = orig_worker

    end_count = _baseline()
    ok("基线行数收尾相等", base_count == end_count,
       f"{base_count} vs {end_count}")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
