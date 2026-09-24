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
        n_db = db.query(AiMemory).count()
    finally:
        db.close()
    store = _get_store_fresh()
    n_vec = 0
    if store is not None:
        try:
            n_vec = store.count()
        except Exception as exc:
            print("读取向量库失败: %s" % exc)
            return 1
    print("ai_memory 行数: %d" % n_db)
    print("couple_memory 向量数: %d" % n_vec)
    print("待回填: %d" % max(0, n_db - n_vec))
    if n_db > n_vec:
        print("→ 运行: python scripts/build_memory_index.py --backfill")
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
                      AiMemory.relation_id, AiMemory.visibility, AiMemory.memory_type)
            .order_by(AiMemory.id)
            .all()
        )
    finally:
        db.close()

    print("扫描 ai_memory %d 行，collection=%s dir=%s" % (len(rows), MEMORY_COLLECTION, CHROMA_DIR))

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
                skipped += 1
                continue
            vec = embeddings.embed_documents([text])[0]
            col.add(
                ids=[mid],
                embeddings=[vec],
                documents=[text],
                metadatas=[{
                    "memory_id": int(row.id),
                    "user_id": int(row.user_id),
                    "relation_id": int(row.relation_id),
                    "visibility": row.visibility or "private",
                    "memory_type": row.memory_type or "",
                }],
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
    parser.add_argument("--status", action="store_true", help="只打印进度")
    args = parser.parse_args()

    if args.status:
        return status()
    if args.backfill:
        try:
            return backfill()
        except Exception as exc:
            print("回填失败: %s" % exc)
            return 1
    parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
