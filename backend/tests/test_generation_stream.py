"""单次触发型 AI 生成全面流式化验证（v2.2）

v2.2 把信件改写 / AI 回信 / 表达改写 / 画像报告全部切到与「信件解读」
同一套 ai_generation + SSE 基建。本脚本验证：

    A. Prompt 层（纯逻辑）：三个结构化场景的流式变体含双出口协议，
       字段契约与 Pydantic 模型一致
    B. 协议层：/rewrite-letter/stream 真实 TCP 拿到完整事件序列
    C. 协议层：/rewrite/stream（表达改写）真实 TCP
    D. 持久化与回读：结果落 ai_generation，GET /generations/{kind} 取回
    E. 画像报告：无问卷时返回 400/40001（不真调模型）

运行：
    cd backend
    python tests/test_generation_stream.py

注意：B/C 会真调大模型（每个约 1~2 分钟），并临时创建一封测试信件，结束时清理。
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx  # noqa: E402
from sqlalchemy import text  # noqa: E402

from app.main import app  # noqa: E402
from app.models.ai_generation import AiGeneration  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.models.letter import Letter  # noqa: E402
from app.schemas.ai_output import (  # noqa: E402
    LetterAnalysisOutput,
    LetterReplyOutput,
    LetterRewriteOutput,
    RewriteOutput,
)
from app.security.jwt import create_access_token  # noqa: E402
from app.services.ai_service import prepare_profile_report  # noqa: E402
from app.services.letter_ai_service import (  # noqa: E402
    LETTER_REPLY_PROMPT,
    LETTER_REWRITE_PROMPT,
    LETTER_UNDERSTAND_PROMPT,
)
from app.services.prompt_builder import (  # noqa: E402
    STRUCTURED_MARKER,
    build_structured_stream_prompt,
)
from app.services.structured_stream import parse_structured_payload  # noqa: E402

LOCAL_PORT = 18082
BASE = "http://127.0.0.1:%d" % LOCAL_PORT

STREAM_REWRITE_LETTER = "/api/v1/couple/ai/rewrite-letter/stream"
STREAM_REWRITE = "/api/v1/couple/ai/rewrite/stream"
STREAM_PROFILE = "/api/v1/couple/ai/profile-report/stream"
READ_PATH = "/api/v1/couple/ai/generations/%s"

TEST_LETTER_TITLE = "[自动测试] 流式改写用信件"
TEST_LETTER_CONTENT = (
    "我不想再吵了。你每次都说我无理取闹，可我只是想让你多陪陪我。"
    "上次我生病你在加班，我一个人去的医院。"
)


# ---------------------------------------------------------------------- #
# A. Prompt 层（纯逻辑，不依赖网络）
# ---------------------------------------------------------------------- #
PROMPT_CASES = [
    (
        "信件改写",
        LETTER_REWRITE_PROMPT.format(
            partner_profile="依恋类型: 疏离回避型",
            letter_title=TEST_LETTER_TITLE,
            letter_content=TEST_LETTER_CONTENT,
            style="温柔一点",
        ),
        LetterRewriteOutput,
        ["rewritten_title", "rewritten_content", "changes"],
    ),
    (
        "AI 回信",
        LETTER_REPLY_PROMPT.format(
            sender_profile="依恋类型: 焦虑依恋型",
            letter_title=TEST_LETTER_TITLE,
            letter_content=TEST_LETTER_CONTENT,
        ),
        LetterReplyOutput,
        ["replies", "do_not_say"],
    ),
    (
        "表达改写",
        (
            "你是一位专业的沟通顾问。请将以下原始表达改写为5种不同风格的版本。\n\n"
            "## 原始表达\n你又忘了我们的约定\n\n## 输出格式\n"
            "请以JSON格式输出，包含 rewrites 等字段"
        ),
        RewriteOutput,
        ["rewrites", "risk_level"],
    ),
]


def check_prompts() -> bool:
    print("\n" + "=" * 72)
    print("A. Prompt 层：新场景的流式变体")
    print("=" * 72)

    all_ok = True
    for name, base, model, fields in PROMPT_CASES:
        streamed = build_structured_stream_prompt(
            base, model, content_instruction="测试正文指令"
        )
        checks = [
            ("保留原文要素", "原始表达" in streamed or TEST_LETTER_CONTENT in streamed),
            ("含分隔符", STRUCTURED_MARKER in streamed),
            ("含 Pydantic 字段名", all(f in streamed for f in fields)),
            ("不再要求「请以 JSON 格式回复」", "请以 JSON 格式回复" not in streamed),
            ("含正文指令", "测试正文指令" in streamed),
        ]
        # 分隔符后的 JSON 反解，确认 Schema 无 $ref 残留
        parsed = parse_structured_payload(streamed.split(STRUCTURED_MARKER, 1)[1])
        checks.append(("Schema 已内联（无 $ref）", isinstance(parsed, dict) and "$ref" not in json.dumps(parsed)))

        ok = all(p for _, p in checks)
        all_ok = all_ok and ok
        print("\n  [%s]" % name)
        for label, passed in checks:
            print("    %s %s" % ("✅" if passed else "❌", label))

    return all_ok


# ---------------------------------------------------------------------- #
# 测试数据
# ---------------------------------------------------------------------- #
def pick_active_relation():
    db = SessionLocal_()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        if not rel:
            raise RuntimeError("数据库里没有 active 的情侣关系，请先造一条测试数据")
        return rel.id, rel.user_a_id
    finally:
        db.close()


def SessionLocal_():
    from app.core.database import SessionLocal
    return SessionLocal()


def ensure_test_letter(relation_id: int, user_id: int) -> int:
    db = SessionLocal_()
    try:
        existing = (
            db.query(Letter)
            .filter(Letter.relation_id == relation_id, Letter.title == TEST_LETTER_TITLE)
            .first()
        )
        if existing:
            return existing.id
        letter = Letter(
            relation_id=relation_id,
            sender_id=user_id,
            receiver_id=user_id,
            title=TEST_LETTER_TITLE,
            content=TEST_LETTER_CONTENT,
            letter_type="normal",
            status="sent",
        )
        db.add(letter)
        db.commit()
        return letter.id
    finally:
        db.close()


def cleanup(letter_id: int) -> None:
    db = SessionLocal_()
    try:
        db.query(AiGeneration).filter(
            AiGeneration.target_type == "letter", AiGeneration.target_id == letter_id
        ).delete(synchronize_session=False)
        db.query(AiGeneration).filter(
            AiGeneration.generation_kind.in_(["expression_rewrite", "profile_report"])
        ).delete(synchronize_session=False)
        db.query(Letter).filter(Letter.id == letter_id, Letter.title == TEST_LETTER_TITLE).delete(
            synchronize_session=False
        )
        db.commit()
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        print("  ⚠️ 清理失败：%s" % exc)
    finally:
        db.close()


# ---------------------------------------------------------------------- #
# SSE 解析（与 test_letter_stream 同一套）
# ---------------------------------------------------------------------- #
def stream_once(path: str, body: dict | None, headers: dict) -> dict:
    client = httpx.Client(timeout=240.0)
    started = time.time()
    try:
        with client.stream("POST", BASE + path, json=body, headers=headers) as resp:
            if resp.status_code != 200:
                return {"ok": False, "status": resp.status_code, "body": resp.read()[:300]}
            names, meta, done, error = [], None, None, None
            delta_chars = 0
            for line in resp.iter_lines():
                if line.startswith("event:"):
                    names.append(line[6:].strip())
                elif line.startswith("data:") and names:
                    payload = json.loads(line[5:].strip() or "null")
                    last = names[-1]
                    if last == "meta":
                        meta = payload
                    elif last == "delta":
                        delta_chars += len((payload or {}).get("content", ""))
                    elif last == "done":
                        done = payload
                    elif last == "error":
                        error = payload
    finally:
        client.close()
    return {
        "ok": True,
        "names": names,
        "meta": meta,
        "done": done,
        "error": error,
        "delta_chars": delta_chars,
        "seconds": time.time() - started,
    }


def print_stream_result(tag: str, r: dict, extra_checks: list) -> bool:
    print("\n" + "=" * 72)
    print("%s（真实 TCP）" % tag)
    print("=" * 72)
    if not r["ok"]:
        print("  ❌ HTTP %s: %s" % (r.get("status"), r.get("body")))
        return False
    names = r["names"]
    print("  事件: %s → ...（共 %d 帧）" % (" → ".join(names[:6]), len(names)))
    print("  delta 总字数: %d / 耗时: %.1fs" % (r["delta_chars"], r["seconds"]))
    structured = (r["done"] or {}).get("structured_output") or {}
    print("  结构化字段: %s" % ", ".join(sorted(structured.keys()))[:160])
    checks = [
        ("事件以 meta 开头", names[:1] == ["meta"]),
        ("事件以 done 结尾", names[-1:] == ["done"]),
        ("delta 帧 >= 5", names.count("delta") >= 5),
        ("没有 error 帧", r["error"] is None),
        ("meta 带回 generation_id", bool(r["meta"] and r["meta"].get("generation_id"))),
        ("正文 > 50 字", r["delta_chars"] > 50),
        ("done 未中断", bool(r["done"] and r["done"].get("interrupted") is False)),
    ] + extra_checks
    for label, passed in checks:
        print("  %s %s" % ("✅" if passed else "❌", label))
    return all(p for _, p in checks)


# ---------------------------------------------------------------------- #
# 主流程
# ---------------------------------------------------------------------- #
def main() -> int:
    print("\n流式端点清单（OpenAPI）：")
    for p in sorted(app.openapi()["paths"]):
        if "/stream" in p:
            print("  ", p)

    results = []
    results.append(("A. Prompt 层", check_prompts()))

    relation_id, user_id = pick_active_relation()
    letter_id = ensure_test_letter(relation_id, user_id)
    headers = {
        "Authorization": "Bearer %s" % create_access_token(user_id),
        "Content-Type": "application/json",
    }

    try:
        # B. 信件改写流式
        r = stream_once(STREAM_REWRITE_LETTER, {"letter_id": letter_id, "style": "更温柔但不卑微"}, headers)
        ok_b = print_stream_result(
            "B. /rewrite-letter/stream", r,
            [("结构化含 rewritten_content", bool((r.get("done") or {}).get("structured_output", {}).get("rewritten_content")))],
        )
        results.append(("B. 信件改写流式", ok_b))

        # C. 表达改写流式
        r = stream_once(STREAM_REWRITE, {"text": "你又忘了我们的约定，我真的很失望。"}, headers)
        ok_c = print_stream_result(
            "C. /rewrite/stream", r,
            [("结构化含 rewrites 数组", bool((r.get("done") or {}).get("structured_output", {}).get("rewrites")))],
        )
        results.append(("C. 表达改写流式", ok_c))

        # D. 落库回读
        print("\n" + "=" * 72)
        print("D. 持久化与回读")
        print("=" * 72)
        client = httpx.Client(timeout=30.0)
        try:
            resp = client.get(
                BASE + READ_PATH % "letter_rewrite",
                params={"target_type": "letter", "target_id": letter_id},
                headers=headers,
            )
            payload = (resp.json() or {}).get("data") or {}
        finally:
            client.close()
        ok_d = bool(payload.get("content")) and payload.get("generation_kind") == "letter_rewrite"
        print("  %s 回读 letter_rewrite（content %d 字）" % ("✅" if ok_d else "❌", len(payload.get("content", ""))))
        results.append(("D. 落库回读", ok_d))

        # E. 画像报告：有问卷则真跑，无问卷应 40001
        print("\n" + "=" * 72)
        print("E. /profile-report/stream")
        print("=" * 72)
        r = stream_once(STREAM_PROFILE, None, headers)
        if r["ok"]:
            ok_e = r["names"][-1:] == ["done"] and r["delta_chars"] > 100
            print("  有问卷，真实生成：delta %d 字，%s" % (r["delta_chars"], "✅" if ok_e else "❌"))
        else:
            ok_e = r["status"] == 400
            print("  无问卷，预期拒绝：HTTP %s %s" % (r["status"], "✅" if ok_e else "❌"))
        results.append(("E. 画像报告", ok_e))
    finally:
        cleanup(letter_id)

    print("\n" + "=" * 72)
    print("总结")
    print("=" * 72)
    failed = 0
    for name, ok in results:
        print("  %s %s" % ("✅" if ok else "❌", name))
        failed += 0 if ok else 1
    return 1 if failed else 0


if __name__ == "__main__":
    import uvicorn
    import threading

    config = uvicorn.Config(app, host="127.0.0.1", port=LOCAL_PORT, log_level="warning")
    server = uvicorn.Server(config)
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    time.sleep(2.0)
    try:
        code = main()
    finally:
        server.should_exit = True
    sys.exit(code)
