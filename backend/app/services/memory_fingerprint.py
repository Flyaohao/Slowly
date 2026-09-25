"""两级幂等键（v3.2 附录 §1.5，SHA256 原文口径）。

纯函数模块，无 DB。

1. 任务级：`SHA256(pipeline_version \\n trigger_kind \\n source_type \\n
   source_id \\n source_revision_id)` —— 同源重放只会有一个 task 行。
2. 断言级：`SHA256(evidence source/range + normalized predicate + object_key
   + subject + normalized memory_text)` —— 同任务内重复候选 no-op。

文本归一：只做 `\\r\\n→\\n`、`\\r→\\n` 行结束符归一——**不 strip、不折叠**
（offset/length 必须能映射回归一后原文的 Unicode 码点，§7.2；折叠会错位）。
"""
import hashlib
from typing import Optional

#: Prompt/管线版本——升版即换键（旧任务不与新任务撞）
PIPELINE_VERSION = "v3.2.0"


def normalize_evidence_text(text: str) -> str:
    """行结束符归一（`\\r\\n`→`\\n`、单独 `\\r`→`\\n`），其余逐字保留（§7.2）。"""
    if not text:
        return ""
    return text.replace("\r\n", "\n").replace("\r", "\n")


def sha256_hex(payload: str) -> str:
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def task_idempotency_key(
    trigger_kind: str,
    source_type: str,
    source_id: int,
    source_revision_id: Optional[str] = None,
    pipeline_version: str = PIPELINE_VERSION,
) -> str:
    """任务级幂等键（附录 §1.5 逐字口径）。

    `SHA256(pipeline_version \\n trigger_kind \\n source_type \\n source_id
    \\n source_revision_id)`；revision 为空按空串参与拼接。
    """
    payload = "\n".join(
        [
            pipeline_version or "",
            trigger_kind or "",
            source_type or "",
            str(source_id),
            source_revision_id or "",
        ]
    )
    return sha256_hex(payload)


def item_fingerprint(
    *,
    source_type: str,
    source_id: int,
    source_revision_id: Optional[str],
    offset: int,
    length: int,
    predicate: str,
    object_key: str,
    subject_type: str,
    subject_user_id: Optional[int],
    memory_text: str,
) -> str:
    """断言级指纹（附录 §1.5）：

    `SHA256(evidence source/range + normalized predicate + object_key +
    subject + normalized memory_text)`。
    """
    normalized_text = normalize_evidence_text(memory_text)
    payload = "\n".join(
        [
            # evidence source/range
            "%s:%s:%s@%s+%s"
            % (
                source_type or "",
                source_id,
                source_revision_id or "",
                offset,
                length,
            ),
            (predicate or "").strip().lower(),
            (object_key or "").strip().lower(),
            "%s:%s"
            % ((subject_type or "").strip(), "" if subject_user_id is None else subject_user_id),
            normalized_text,
        ]
    )
    return sha256_hex(payload)
