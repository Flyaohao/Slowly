"""文本向量化（Embedding）封装

选型说明
--------
使用 DashScope `text-embedding-v4`（1024 维）而非本地模型：
    1. 服务器为单机部署、内存紧张，本地 BGE 模型加载需数百 MB 且首次下载慢；
    2. 云端 Embedding 无冷启动，中文语义表现好；
    3. 按 token 计费，知识库规模小（9 篇文档），成本可忽略。

接口兼容
--------
本类实现 `embed_documents` / `embed_query` 两个方法，与 LangChain 的
`Embeddings` 接口一致，因此可以**直接传给 langchain_chroma.Chroma 使用**，
无需额外适配层。
"""

from __future__ import annotations

import logging
import time
from typing import List

import httpx

from app.core.config import AI_API_KEY, AI_BASE_URL

try:  # 有 LangChain 时继承其抽象基类，便于直接接入 LangChain 生态
    from langchain_core.embeddings import Embeddings as _LangChainEmbeddings
except ImportError:  # pragma: no cover - 未安装 LangChain 时退化为普通类
    class _LangChainEmbeddings(object):
        pass

logger = logging.getLogger("couple.embedding")

#: 模型与维度。v4 支持自定义维度，1024 在效果与存储成本间较均衡
EMBEDDING_MODEL = "text-embedding-v4"
EMBEDDING_DIM = 1024

#: 单次请求的文本条数上限（DashScope 限制较宽松，取 10 保守处理）
_BATCH_SIZE = 10
_TIMEOUT = 30.0
_MAX_RETRIES = 2


class DashScopeEmbeddings(_LangChainEmbeddings):
    """DashScope 文本向量化客户端。模块级单例见文件底部 `embeddings`。"""

    def __init__(
        self,
        api_key: str = None,
        base_url: str = None,
        model: str = EMBEDDING_MODEL,
        dim: int = EMBEDDING_DIM,
        batch_size: int = _BATCH_SIZE,
    ) -> None:
        self.api_key = api_key or AI_API_KEY
        self.base_url = (base_url or AI_BASE_URL).rstrip("/")
        self.model = model
        self.dim = dim
        self.batch_size = batch_size

    # ------------------------------------------------------------------ #
    # LangChain Embeddings 接口
    # ------------------------------------------------------------------ #
    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        """批量向量化（自动分批）。"""
        vectors: List[List[float]] = []
        for start in range(0, len(texts), self.batch_size):
            batch = texts[start:start + self.batch_size]
            vectors.extend(self._embed(batch))
        return vectors

    def embed_query(self, text: str) -> List[float]:
        """单条查询向量化。"""
        return self._embed([text])[0]

    # ------------------------------------------------------------------ #
    # 内部实现
    # ------------------------------------------------------------------ #
    def _embed(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []
        if not self.api_key:
            raise RuntimeError("未配置 AI_API_KEY，无法进行向量化")

        payload = {"model": self.model, "input": texts, "dimensions": self.dim}
        headers = {
            "Authorization": "Bearer %s" % self.api_key,
            "Content-Type": "application/json",
        }
        url = "%s/embeddings" % self.base_url

        last_error = None
        for attempt in range(_MAX_RETRIES + 1):
            try:
                started = time.time()
                with httpx.Client(timeout=_TIMEOUT) as client:
                    resp = client.post(url, headers=headers, json=payload)
                if resp.status_code == 200:
                    data = resp.json()
                    logger.info(
                        "[EMBED] model=%s n=%d tokens=%s cost=%.2fs",
                        self.model, len(texts),
                        (data.get("usage") or {}).get("total_tokens"),
                        time.time() - started,
                    )
                    # 按 index 排序，保证与入参顺序一致
                    items = sorted(data["data"], key=lambda x: x.get("index", 0))
                    return [item["embedding"] for item in items]

                last_error = "HTTP %s: %s" % (resp.status_code, resp.text[:200])
                logger.warning("[EMBED] 第 %d 次失败: %s", attempt + 1, last_error)
            except httpx.HTTPError as exc:
                last_error = str(exc)
                logger.warning("[EMBED] 第 %d 次请求异常: %s", attempt + 1, exc)

        raise RuntimeError("向量化失败: %s" % last_error)


#: 模块级单例
embeddings = DashScopeEmbeddings()
