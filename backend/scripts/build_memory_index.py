"""记忆向量索引构建 / 回填（P0-4 约束②）。

背景：
  create_memory 挂钩只覆盖**新写入**的记忆；ai_memory 里已有的行没有
  couple_memory 向量。若不回填，相关性召回一旦有命中，老记忆就永远
  不可见——比改造前"永远近因 10 条"更差。

用法：
    cd backend
    python scripts/build_memory_index.py --backfill   # 幂等：已有向量的跳过
    python scripts/build_memory_index.py --status     # 只看进度，不写

幂等依据：chroma id == str(ai_memory.id)，逐条查 get(ids=[id])。
与 build_vectorstore.py 的 couple_theory 集合互不干扰（同目录不同 collection）。

v3.2 阶段 A 注意（§8 ③）：
  本脚本只写向量、**不改 index_status**。legacy 行迁移后保持
  index_status='skipped'，索引 worker 不会接管它们；把存量行批量翻成
  pending_upsert 走新 CAS 链路是阶段 B 项，**现在不做**。
  INDEX_WORKER 开启后，本脚本仍可用于缺向量回填 / 孤儿清理（--backfill/--prune）。
"""
from __future__ import annotations

import argparse
import os
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE_DIR)

from app.core.database import SessionLocal  # noqa: E402
from app.models.ai import AiMemory  # noqa: E402
from app.services.memory_retrieval import (  # noqa: E402
    CHROMA_DIR,
    MEMORY_COLLECTION,
    _get_collection,
    reset_store_for_tests,
)


def _get_store_fresh():
    reset_store_for_tests()
    return _get_collection()


def status() -> int:
    db = SessionLocal()
    try:
        db_ids = set(r[0] for r in db.query(AiMemory.id).all())
    finally:
        db.close()
    n_db = len(db_ids)
    store = _get_store_fresh()
    n_vec = 0
    chroma_ids = set()
    if store is not None:
        try:
            got = store.get(include=[])
            chroma_ids = set(int(x) for x in (got.get("ids") or []))
            n_vec = len(chroma_ids)
        except Exception as exc:
            print("读取向量库失败: %s" % exc)
            return 1
    orphans = chroma_ids - db_ids
    missing = db_ids - chroma_ids
    print("ai_memory 行数: %d" % n_db)
    print("couple_memory 向量数: %d" % n_vec)
    # 缺陷三-3：单独打印孤儿，不用 max(0, n_db-n_vec) 掩盖
    print("孤儿向量: %d%s" % (len(orphans), (" ids=%s" % sorted(orphans)) if orphans else ""))
    print("缺向量(待回填): %d" % len(missing))
    if missing:
        print("→ 运行: python scripts/build_memory_index.py --backfill")
    if orphans:
        print("→ 运行: python scripts/build_memory_index.py --prune")
    if n_db != n_vec and not missing and not orphans:
        # 理论上不会到这；防御性提示
        print("→ 行数与向量数不一致但差集为空，请人工检查")
    return 0


def prune() -> int:
    """删除孤儿向量（chroma 有、DB 无）。幂等：第二次删除 0 条。"""
    col = _get_store_fresh()
    if col is None:
        print("无法打开 couple_memory，中止")
        return 1
    db = SessionLocal()
    try:
        db_ids = set(r[0] for r in db.query(AiMemory.id).all())
    finally:
        db.close()
    got = col.get(include=[])
    chroma_ids = set(int(x) for x in (got.get("ids") or []))
    orphans = sorted(chroma_ids - db_ids)
    if not orphans:
        print("删除孤儿 0 条（无孤儿）")
        return 0
    col.delete(ids=[str(i) for i in orphans])
    print("删除孤儿 %d 条（ids=%s）" % (len(orphans), orphans))
    return 0


def backfill(batch_report: int = 20) -> int:
    col = _get_store_fresh()
    if col is None:
        print("无法打开 couple_memory（目录缺失或依赖异常），中止")
        return 1

    from app.services.embedding import embeddings

    db = SessionLocal()
    try:
        rows = (
            db.query(AiMemory.id, AiMemory.memory_text, AiMemory.user_id,
                      AiMemory.relation_id, AiMemory.visibility, AiMemory.memory_type,
                      AiMemory.occurred_at, AiMemory.source, AiMemory.importance)
            .order_by(AiMemory.id)
            .all()
        )
    finally:
        db.close()

    print("扫描 ai_memory %d 行，collection=%s dir=%s" % (len(rows), MEMORY_COLLECTION, CHROMA_DIR))

    def _metadata(row) -> dict:
        """P-C1 §6：向量 metadata 含新 3 键；None 不写（Chroma 不接受）。"""
        meta = {
            "memory_id": int(row.id),
            "user_id": int(row.user_id),
            "relation_id": int(row.relation_id),
            "visibility": row.visibility or "private",
            "memory_type": row.memory_type or "",
            "importance": int(row.importance or 0),
        }
        if row.source:
            meta["source"] = row.source
        if row.occurred_at:
            meta["occurred_at"] = row.occurred_at.isoformat()
        return meta

    added = skipped = failed = 0
    for i, row in enumerate(rows, 1):
        mid = str(row.id)
        text = (row.memory_text or "").strip()
        if not text:
            skipped += 1
            continue
        try:
            existing = col.get(ids=[mid])
            if existing and existing.get("ids"):
                # 存量向量：不同步 embedding 文本（检索口径不变），
                # 只把新 metadata 三键补写上（幂等，重复执行同一结果）
                col.update(ids=[mid], metadatas=[_metadata(row)])
                skipped += 1
                continue
            vec = embeddings.embed_documents([text])[0]
            col.add(
                ids=[mid],
                embeddings=[vec],
                documents=[text],
                metadatas=[_metadata(row)],
            )
            added += 1
        except Exception as exc:
            failed += 1
            print("  [失败] id=%s: %s" % (mid, exc))
        if i % batch_report == 0:
            print("  进度 %d/%d（新增 %d，跳过 %d，失败 %d）"
                  % (i, len(rows), added, skipped, failed))

    print("完成：新增 %d，跳过 %d，失败 %d" % (added, skipped, failed))
    try:
        print("couple_memory 现有向量 %d 条" % col.count())
    except Exception:
        pass
    return 0 if failed == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="记忆向量索引（couple_memory）")
    parser.add_argument("--backfill", action="store_true", help="幂等回填所有无向量的记忆")
    parser.add_argument("--prune", action="store_true", help="删除孤儿向量（chroma 有、DB 无）")
    parser.add_argument("--status", action="store_true", help="只打印进度与孤儿数")
    args = parser.parse_args()

    if args.status:
        return status()
    if args.backfill:
        try:
            return backfill()
        except Exception as exc:
            print("回填失败: %s" % exc)
            return 1
    if args.prune:
        try:
            return prune()
        except Exception as exc:
            print("prune 失败: %s" % exc)
            return 1
    parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
