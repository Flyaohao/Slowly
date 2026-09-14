#!/bin/sh
# 容器启动脚本：迁移 -> （首次）建向量库 -> 起服务
set -e

: "${CHROMA_DIR:=/app/data/chroma}"

if [ "${RUN_MIGRATIONS:-1}" = "1" ]; then
    echo "[entrypoint] alembic upgrade head"
    # 迁移脚本本身写成了幂等的（建表/加列前先判断存在性），重复执行安全。
    # MySQL 不支持事务性 DDL，所以这里的幂等性比任何回滚机制都重要。
    alembic upgrade head
else
    echo "[entrypoint] 跳过迁移（RUN_MIGRATIONS=0）"
fi

if [ "${SEED_ON_START:-0}" = "1" ]; then
    # 种子脚本同样做了存在性判断，可重复执行。
    # 失败不阻断启动：线上更应该先让服务起来，再单独补数据。
    for s in seed_questionnaire seed_ai_scenes seed_knowledge seed_practices seed_avatar_assets; do
        echo "[entrypoint] 灌种子数据：$s"
        python "scripts/$s.py" || echo "[entrypoint] $s 执行失败，已跳过"
    done
fi

if [ ! -d "$CHROMA_DIR" ]; then
    echo "[entrypoint] 向量库不存在（$CHROMA_DIR），开始构建"
    # 构建失败只降级不阻断：rag_service 检测不到向量库会自动退回关键词检索
    python scripts/build_vectorstore.py \
        || echo "[entrypoint] 向量库构建失败，RAG 将降级为关键词检索"
else
    echo "[entrypoint] 向量库已存在，跳过构建"
fi

echo "[entrypoint] 启动 uvicorn"
exec uvicorn app.main:app --host 0.0.0.0 --port 8000
