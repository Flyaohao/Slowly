"""把心理学理论文档切片、向量化，写入 ChromaDB 向量库

数据来源
    scripts/seed_knowledge.py 的 KNOWLEDGE_DOCS（9 篇理论文档：
    依恋理论 / Gottman 冲突四骑士 / 追逃模式 / 非暴力沟通 / I-Statement 等）

处理流程
    读取文档 → RecursiveCharacterTextSplitter 切片 → DashScope Embedding 向量化
             → 写入 ChromaDB（持久化到 backend/data/chroma）

运行
    cd backend
    python scripts/build_vectorstore.py           # 增量构建（已存在则跳过）
    python scripts/build_vectorstore.py --force   # 删除并重建
"""

import argparse
import importlib.util
import os
import shutil
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE_DIR)

from app.services.embedding import embeddings  # noqa: E402

#: 向量库持久化目录
CHROMA_DIR = os.path.join(BASE_DIR, "data", "chroma")
COLLECTION_NAME = "couple_theory"

#: 中文友好的切片配置（面试要点：中文不能只按空格切）
CHUNK_SIZE = 500
CHUNK_OVERLAP = 50
CHINESE_SEPARATORS = ["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""]


def load_knowledge_docs():
    """从 seed_knowledge.py 读取理论文档，避免内容重复维护。"""
    path = os.path.join(BASE_DIR, "scripts", "seed_knowledge.py")
    spec = importlib.util.spec_from_file_location("seed_knowledge", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.KNOWLEDGE_DOCS


def split_documents(docs):
    """按递归字符切分器切片，保留标题与来源元数据。"""
    from langchain_text_splitters import RecursiveCharacterTextSplitter

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=CHUNK_SIZE,
        chunk_overlap=CHUNK_OVERLAP,
        separators=CHINESE_SEPARATORS,
        length_function=len,
    )

    texts, metadatas, ids = [], [], []
    for doc_index, doc in enumerate(docs):
        chunks = splitter.split_text(doc["content"])
        for chunk_index, chunk in enumerate(chunks):
            texts.append(chunk)
            metadatas.append({
                "doc_title": doc["title"],
                "source": doc.get("source", ""),
                "doc_index": doc_index,
                "chunk_index": chunk_index,
            })
            ids.append("doc%d-chunk%d" % (doc_index, chunk_index))
    return texts, metadatas, ids


def build(force: bool = False) -> int:
    from langchain_chroma import Chroma

    if force and os.path.isdir(CHROMA_DIR):
        shutil.rmtree(CHROMA_DIR)
        print("[清理] 已删除旧向量库 %s" % CHROMA_DIR)

    docs = load_knowledge_docs()
    print("[读取] 理论文档 %d 篇" % len(docs))

    texts, metadatas, ids = split_documents(docs)
    print("[切片] 生成 %d 个 chunk（chunk_size=%d, overlap=%d）"
          % (len(texts), CHUNK_SIZE, CHUNK_OVERLAP))

    os.makedirs(CHROMA_DIR, exist_ok=True)
    vectorstore = Chroma(
        collection_name=COLLECTION_NAME,
        embedding_function=embeddings,
        persist_directory=CHROMA_DIR,
        # 文本检索用余弦距离，比默认 L2 更贴合语义相似度
        collection_metadata={"hnsw:space": "cosine"},
    )

    existing = vectorstore.get(include=[])["ids"]
    if existing and not force:
        print("[跳过] 向量库已有 %d 条记录，如需重建请加 --force" % len(existing))
        return len(existing)

    print("[向量化] 调用 %s ..." % embeddings.model)
    vectorstore.add_texts(texts=texts, metadatas=metadatas, ids=ids)
    print("[完成] 已写入 %d 条向量到 %s" % (len(texts), CHROMA_DIR))
    return len(texts)


def main() -> int:
    parser = argparse.ArgumentParser(description="构建心理学理论向量库")
    parser.add_argument("--force", action="store_true", help="删除并重建向量库")
    args = parser.parse_args()

    try:
        count = build(force=args.force)
    except Exception as exc:
        print("构建失败: %s" % exc)
        return 1
    print("向量库就绪，共 %d 条。" % count)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
