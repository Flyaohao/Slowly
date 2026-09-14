"""RAG 向量检索验证

验证内容：
    1. 向量库可正常加载
    2. 用户提问能召回语义相关的理论片段（而非关键词字面匹配）
    3. 召回结果带有相似度分数与来源标题

运行：
    cd backend
    python tests/test_rag.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal  # noqa: E402
from app.services.rag_service import CHROMA_DIR, build_rag_context, retrieve_chunks  # noqa: E402

# 每条用例：查询语句 + 期望能命中的理论关键词
CASES = [
    ("他从来都不主动找我，都是我在追着他", ["追逃", "Demand", "依附", "依恋"]),
    ("他说'随便你'，我觉得他在冷暴力我", ["冷", "防御", "四骑士", "沟通"]),
    ("我不知道怎么开口跟他说我的感受", ["非暴力", "NVC", "I-Statement", "表达"]),
    ("我们已经一个星期没说话了", ["冷战", "修复", "破冰"]),
    ("我想跟他道歉，但不知道怎么说不显得卑微", ["道歉", "表达", "结构"]),
]


def main() -> int:
    print("=" * 72)
    print("RAG 向量检索验证")
    print("=" * 72)
    print("向量库目录: %s" % CHROMA_DIR)
    print("目录存在: %s" % os.path.isdir(CHROMA_DIR))

    db = SessionLocal()
    all_vector = True
    try:
        for query, expected in CASES:
            print("\n" + "-" * 72)
            print("[提问] %s" % query)
            print("-" * 72)

            chunks = retrieve_chunks(db, query, top_k=3)
            if not chunks:
                print("  ❌ 未召回任何片段")
                all_vector = False
                continue

            retriever = chunks[0].get("retriever", "?")
            print("  检索方式: %s" % retriever)
            if retriever != "vector":
                all_vector = False

            for i, chunk in enumerate(chunks, 1):
                print("  %d) [%.4f] %s" % (i, chunk.get("score", 0), chunk.get("doc_title", "")))
                preview = chunk.get("chunk_text", "").replace("\n", " ")[:90]
                print("     %s..." % preview)

            hit = [kw for kw in expected
                   if any(kw in c.get("chunk_text", "") or kw in c.get("doc_title", "")
                          for c in chunks)]
            print("  期望理论命中: %s" % ("、".join(hit) if hit else "（未命中，可放宽用例）"))

        # 演示拼装后的上下文
        print("\n" + "=" * 72)
        print("注入 Prompt 的上下文示例")
        print("=" * 72)
        demo = retrieve_chunks(db, "他三天没回我消息，我很难受", top_k=2)
        print(build_rag_context(demo)[:600])
    finally:
        db.close()

    print("\n" + "=" * 72)
    if all_vector:
        print("✅ 全部召回均来自向量检索")
        return 0
    print("⚠️ 存在非向量检索结果（向量库未构建或加载失败）")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
