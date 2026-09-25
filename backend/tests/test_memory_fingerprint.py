"""v3.2 附录 §1.5 两级幂等键（纯函数，hermetic）

断言：
  1. PIPELINE_VERSION 固定 "v3.2.0"；
  2. task_idempotency_key：五段 SHA256 拼接口径、同输入稳定、任一段变则键变、
     revision 空按空串；
  3. item_fingerprint：evidence range/predicate/object_key/subject/text 五因子
     全参与、CRLF≡LF 归一后指纹一致、任一因子变则指纹变；
  4. normalize_evidence_text 归一口径。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_fingerprint.py
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def main() -> int:
    print("=" * 72)
    print("附录 §1.5 两级幂等键")
    print("=" * 72)

    from app.services.memory_fingerprint import (
        PIPELINE_VERSION,
        item_fingerprint,
        normalize_evidence_text,
        sha256_hex,
        task_idempotency_key,
    )

    print("\n[1] 版本常量")
    check('PIPELINE_VERSION == "v3.2.0"', PIPELINE_VERSION == "v3.2.0", PIPELINE_VERSION)

    print("\n[2] task_idempotency_key")
    key = task_idempotency_key("chat_turn", "chat_message", 101, None)
    expected = hashlib.sha256(
        "v3.2.0\nchat_turn\nchat_message\n101\n".encode("utf-8")
    ).hexdigest()
    check("五段 SHA256 拼接口径（revision 空串）", key == expected, key)
    check("同输入稳定",
          key == task_idempotency_key("chat_turn", "chat_message", 101, None))
    check("source_id 变 → 键变",
          key != task_idempotency_key("chat_turn", "chat_message", 102, None))
    check("trigger 变 → 键变",
          key != task_idempotency_key("correction", "chat_message", 101, None))
    check("revision 变 → 键变",
          key != task_idempotency_key("chat_turn", "chat_message", 101, "rev2"))
    check("pipeline 升版 → 键变",
          key != task_idempotency_key("chat_turn", "chat_message", 101, None,
                                      pipeline_version="v3.3.0"))

    print("\n[3] item_fingerprint")
    base = dict(
        source_type="chat_message",
        source_id=101,
        source_revision_id="1",
        offset=0,
        length=10,
        predicate="preference",
        object_key="food.spicy",
        subject_type="user",
        subject_user_id=12,
        memory_text="喜欢吃辣",
    )
    fp = item_fingerprint(**base)
    check("同输入稳定", fp == item_fingerprint(**base), fp)
    for field in ("source_id", "offset", "length", "predicate", "object_key",
                  "subject_user_id", "memory_text"):
        mutated = dict(base)
        if field in ("source_id", "offset", "length", "subject_user_id"):
            mutated[field] = (base[field] or 0) + 1
        else:
            mutated[field] = base[field] + "_x"
        check(f"{field} 变 → 指纹变", fp != item_fingerprint(**mutated))
    # 文本归一：只归一行结束符（不 strip/折叠——offset 映射要求）
    crlf_variant = dict(base, memory_text="喜欢吃辣\r\n")
    crlf_fp = item_fingerprint(**crlf_variant)
    lf_variant = dict(base, memory_text="喜欢吃辣\n")
    check("CRLF≡LF 归一后指纹一致", crlf_fp == item_fingerprint(**lf_variant),
          f"{crlf_fp} vs {item_fingerprint(**lf_variant)}")
    check("首尾空白参与指纹（不 strip，保 offset 映射）",
          fp != item_fingerprint(**dict(base, memory_text="  喜欢吃辣  ")))
    diff_variant = dict(base, memory_text="喜欢吃辣的")
    check("真实不同文本 → 指纹变", fp != item_fingerprint(**diff_variant))
    check("大小写不敏感（predicate 小写化）",
          item_fingerprint(**dict(base, predicate="PREFERENCE")) == fp)

    print("\n[4] normalize_evidence_text（只归一行结束符）")
    check("\\r\\n → \\n", normalize_evidence_text("a\r\nb") == "a\nb",
          repr(normalize_evidence_text("a\r\nb")))
    check("单独 \\r → \\n", normalize_evidence_text("a\rb") == "a\nb",
          repr(normalize_evidence_text("a\rb")))
    check("不 strip（offset 映射要求）",
          normalize_evidence_text("  hi  ") == "  hi  ",
          repr(normalize_evidence_text("  hi  ")))
    check("空串安全", normalize_evidence_text("") == "")

    print("\n[5] sha256_hex 基元")
    check("UTF-8 SHA256", sha256_hex("a") ==
          hashlib.sha256("a".encode("utf-8")).hexdigest())

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
