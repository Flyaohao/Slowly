"""信件「AI 帮我理解」流式链路验证

验证内容：
    A. 切分层：分隔符跨 chunk 的边界处理、无分隔符时的降级、代码块包裹的 JSON
    B. Prompt 层：流式变体含双段输出协议，且不再要求「请以 JSON 格式回复」
    C. 协议层：真实 TCP 下拿到完整 meta → thinking* / delta* → notice → result → done
    D. 持久化：「AI 理解」落 ai_generation，且能通过回读端点取回
    E. 可中断：调用取消端点后流迅速收敛为 interrupted，服务端不再继续拉模型

运行：
    cd backend
    python tests/test_letter_stream.py

注意：本脚本会真调大模型（约 1~2 分钟），并临时创建一封测试信件，结束时清理。
"""

import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx  # noqa: E402
from sqlalchemy import text  # noqa: E402

from live_llm_guard import live_llm_enabled, skip_reason  # noqa: E402

from app.core.database import SessionLocal  # noqa: E402
from app.main import app  # noqa: E402
from app.models.ai_generation import AiGeneration  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.models.letter import Letter  # noqa: E402
from app.schemas.ai_output import LetterAnalysisOutput  # noqa: E402
from app.security.jwt import create_access_token  # noqa: E402
from app.services.letter_ai_service import (  # noqa: E402
    LETTER_UNDERSTAND_PROMPT,
    UNDERSTAND_PRINCIPLES_RECEIVER,
    VIEWER_NOTE_RECEIVER,
    _understand_prompt,
)
from app.services.llm_client import llm  # noqa: E402
from app.services.prompt_builder import build_structured_stream_prompt  # noqa: E402
from app.services.structured_stream import (  # noqa: E402
    STRUCTURED_MARKER,
    StructuredStreamSplitter,
    parse_structured_payload,
)

LOCAL_PORT = 18081
STREAM_PATH = "/api/v1/couple/ai/understand-letter/stream"
READ_PATH = "/api/v1/couple/ai/generations/letter_analysis"
CANCEL_PATH = "/api/v1/couple/ai/generations/%d/cancel"

TEST_LETTER_TITLE = "[自动测试] 流式解读用信件"
TEST_LETTER_CONTENT = (
    "不该冷暴力你，但是感觉很气。你上次答应我的事情又忘了，"
    "我等你等到十一点，你连一条消息都没有。我不想每次都主动问你在哪。"
)

PROMPT_ARGS = dict(
    # 方向说明与指导原则自 2026-10-01 起也走模板参数（信件方向错位修复）。
    # 这里填收信人视角，以保留下方「逐段分析信件内容」的历史断言；
    # 写信人视角另有专门的互斥检查（见 check_prompt）。
    viewer_note=VIEWER_NOTE_RECEIVER,
    principles=UNDERSTAND_PRINCIPLES_RECEIVER,
    sender_profile="依恋类型: 焦虑依恋型, 置信度: 0.82",
    letter_title=TEST_LETTER_TITLE,
    letter_content=TEST_LETTER_CONTENT,
)


# ---------------------------------------------------------------------- #
# A. 切分层（纯逻辑，不依赖网络）
# ---------------------------------------------------------------------- #
def check_splitter() -> bool:
    print("\n" + "=" * 72)
    print("A. 结构化流切分")
    print("=" * 72)

    checks = []

    # A1 分隔符被拆在两个 chunk 里：中间的半个分隔符绝不能漏给用户
    sp = StructuredStreamSplitter()
    visible = "".join(
        sp.feed(chunk)
        for chunk in ["这是正文", "<<<STRUC", "TURED>>>", '{"summary": "ok"}']
    )
    content, structured = sp.finish()
    checks.append(("分隔符被拆开时不漏字符", visible == "这是正文" and content == "这是正文"))
    checks.append(("分隔符后的 JSON 正确解析", structured == {"summary": "ok"}))

    # A2 完全没有分隔符：尾部全部当作正文（降级路径）
    sp = StructuredStreamSplitter()
    visible = "".join(sp.feed(c) for c in ["只有正文", "没有分隔符", "<<<"])
    content, structured = sp.finish()
    checks.append(("无分隔符时全量当正文", content == "只有正文没有分隔符<<<" and structured is None))

    # A3 模型把 JSON 包进 ``` 代码块
    sp = StructuredStreamSplitter()
    visible = "".join(
        sp.feed(c) for c in ["正文", STRUCTURED_MARKER, "```json\n", '{"a": 1}\n', "```"]
    )
    content, structured = sp.finish()
    checks.append(("代码块包裹的 JSON 能被剥出来", visible == "正文" and structured == {"a": 1}))

    # A4 空增量不应产生任何可见输出（真实流里空 delta 很常见）
    sp = StructuredStreamSplitter()
    checks.append(("空增量不产出内容", sp.feed("") == "" and sp.feed("x") == "x"))

    for name, passed in checks:
        print("  %s %s" % ("✅" if passed else "❌", name))
    return all(p for _, p in checks)


# ---------------------------------------------------------------------- #
# B. Prompt 层（纯逻辑）
# ---------------------------------------------------------------------- #
def check_prompt() -> bool:
    print("\n" + "=" * 72)
    print("B. Prompt 层：同步变体 vs 双出口流式变体")
    print("=" * 72)

    base = LETTER_UNDERSTAND_PROMPT.format(**PROMPT_ARGS)
    streamed = build_structured_stream_prompt(base, LetterAnalysisOutput)

    # 方向互斥（2026-10-01 修复回归）：同一封信，两种视角的提示词不能混。
    # 修之前只有收信人一种，用户打开自己发出的信会被按「TA 写来的」解读。
    class _LetterStub:
        sender_id = 7
        title = TEST_LETTER_TITLE
        content = TEST_LETTER_CONTENT

    mine = _understand_prompt(_LetterStub(), 7, "我的画像")
    theirs = _understand_prompt(_LetterStub(), 9, "TA 的画像")

    checks = [
        ("流式版保留原 prompt 的分析要求", "逐段分析信件内容" in streamed),
        ("写信人视角：声明这封信是用户自己写的", "用户自己" in mine),
        ("写信人视角：不出现收信人口吻", "用户是**收信人**" not in mine),
        ("写信人视角：不给回信建议", "不需要回自己的信" in mine),
        ("收信人视角：声明用户是收信人", "用户是**收信人**" in theirs),
        ("收信人视角：不出现写信人口吻", "用户自己" not in theirs),
        (
            "两种视角的指导原则互不相同",
            "逐段分析信件内容" in theirs and "逐段分析信件内容" not in mine,
        ),
        ("流式版保留信件原文", TEST_LETTER_CONTENT in streamed),
        ("流式版保留发件人画像", "焦虑依恋型" in streamed),
        ("流式版含分隔符", STRUCTURED_MARKER in streamed),
        ("流式版含 Pydantic 生成的字段名", "key_concerns" in streamed and "reply_suggestions" in streamed),
        ("流式版不再直接要求「请以 JSON 格式回复」", "请以 JSON 格式回复" not in streamed),
        ("流式版明确要求先输出正文", "第一段" in streamed and "第二段" in streamed),
    ]

    for name, passed in checks:
        print("  %s %s" % ("✅" if passed else "❌", name))

    print("\n  同步版长度: %d 字 / 流式版长度: %d 字" % (len(base), len(streamed)))
    # 分隔符后的 JSON 反解一遍，确认 Schema 里没有 $ref 残留
    parsed = parse_structured_payload(streamed.split(STRUCTURED_MARKER, 1)[1])
    schema_ok = isinstance(parsed, dict) and "$ref" not in json.dumps(parsed)
    print("  %s Schema 已内联（无 $ref）" % ("✅" if schema_ok else "❌"))
    return all(p for _, p in checks) and schema_ok


# ---------------------------------------------------------------------- #
# 测试数据准备
# ---------------------------------------------------------------------- #
def pick_active_relation():
    db = SessionLocal()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        if not rel:
            raise RuntimeError("数据库里没有 active 的情侣关系，请先造一条测试数据")
        return rel.id, rel.user_a_id
    finally:
        db.close()


def ensure_test_letter(relation_id: int, user_id: int) -> int:
    db = SessionLocal()
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


def cleanup(relation_id: int, letter_id: int) -> None:
    db = SessionLocal()
    try:
        db.query(AiGeneration).filter(
            AiGeneration.target_type == "letter", AiGeneration.target_id == letter_id
        ).delete(synchronize_session=False)
        db.query(Letter).filter(Letter.id == letter_id, Letter.title == TEST_LETTER_TITLE).delete(
            synchronize_session=False
        )
        db.commit()
    except Exception as exc:  # noqa: BLE001 —— 清理失败不该让测试结论失效
        db.rollback()
        print("  ⚠️ 清理失败：%s" % exc)
    finally:
        db.close()


# ---------------------------------------------------------------------- #
# SSE 解析
# ---------------------------------------------------------------------- #
def iter_sse(resp, clock_start):
    current = None
    events = []
    for line in resp.iter_lines():
        now = time.time() - clock_start
        if line.startswith("event:"):
            current = line[6:].strip()
        elif line.startswith("data:"):
            body = line[5:].strip()
            events.append(
                {"event": current, "data": json.loads(body) if body else None, "at": now}
            )
        elif line.startswith(":"):
            events.append({"event": "_heartbeat", "data": None, "at": now})
    return events


def summarize(events):
    return {
        "names": [e["event"] for e in events],
        "thinking": [e for e in events if e["event"] == "thinking"],
        "delta": [e for e in events if e["event"] == "delta"],
        "meta": next((e for e in events if e["event"] == "meta"), None),
        "result": next((e for e in events if e["event"] == "result"), None),
        "done": next((e for e in events if e["event"] == "done"), None),
        "error": next((e for e in events if e["event"] == "error"), None),
        "heartbeat": len([e for e in events if e["event"] == "_heartbeat"]),
    }


# ---------------------------------------------------------------------- #
# C / D. 正常流 + 落库 + 回读
# ---------------------------------------------------------------------- #
def run_normal_stream(base_url: str, user_id: int, letter_id: int) -> dict:
    print("\n" + "=" * 72)
    print("C. 协议层（真实 TCP）")
    print("=" * 72)

    headers = {
        "Authorization": "Bearer %s" % create_access_token(user_id),
        "Content-Type": "application/json",
    }
    client = httpx.Client(timeout=180.0)
    started = time.time()
    try:
        with client.stream(
            "POST", base_url + STREAM_PATH, json={"letter_id": letter_id}, headers=headers
        ) as resp:
            print("  HTTP 状态: %s" % resp.status_code)
            print("  Content-Type: %s" % resp.headers.get("content-type"))
            if resp.status_code != 200:
                print("  ❌ 请求失败: %s" % resp.read()[:300])
                return {"ok": False}
            events = iter_sse(resp, started)
    finally:
        client.close()

    total = time.time() - started
    s = summarize(events)
    content = "".join((e["data"] or {}).get("content", "") for e in s["delta"])
    thinking = "".join((e["data"] or {}).get("content", "") for e in s["thinking"])
    # 用户真正感知到「有东西了」的时刻：thinking 帧也算，它 0.5s 就到，
    # 而正文首字在推理模型上要 18s+。这个指标才反映空屏时长。
    visible_times = [e["at"] for e in (s["thinking"] + s["delta"])]
    first_visible = min(visible_times) if visible_times else None

    print("\n  事件序列: %s" % " → ".join(s["names"][:8]) + (" → ..." if len(s["names"]) > 8 else ""))
    print(
        "  事件计数: total=%d meta=%d thinking=%d delta=%d notice=%d result=%d done=%d error=%d 心跳=%d"
        % (
            len(events), s["names"].count("meta"), len(s["thinking"]), len(s["delta"]),
            s["names"].count("notice"), s["names"].count("result"),
            s["names"].count("done"), s["names"].count("error"), s["heartbeat"],
        )
    )
    print("  generation_id: %s" % (s["meta"]["data"].get("generation_id") if s["meta"] else None))
    print("  首个可见反馈: %s" % ("%.2fs" % first_visible if first_visible else "N/A"))
    print("  整体耗时: %.2fs" % total)
    print("  思考过程: %d 帧 / %d 字" % (len(s["thinking"]), len(thinking)))
    print("  正文 (%d 字):" % len(content))
    print("  " + content[:220].replace("\n", " ") + ("..." if len(content) > 220 else ""))

    structured = (s["done"]["data"].get("structured_output") if s["done"] else None) or {}
    print("  结构化字段: %s" % ", ".join(sorted(structured.keys()))[:200])

    checks = [
        ("事件以 meta 开头", s["names"][:1] == ["meta"]),
        ("事件以 done 结尾", s["names"][-1:] == ["done"]),
        ("delta 帧 >= 5（确实是逐字下发）", len(s["delta"]) >= 5),
        ("没有 error 帧", s["error"] is None),
        ("meta 带回 generation_id", bool(s["meta"] and s["meta"]["data"].get("generation_id"))),
        ("正文长度 > 50", len(content) > 50),
        ("正文里不含分隔符残留", STRUCTURED_MARKER not in content),
        ("正文里不含 JSON 片段", '"summary"' not in content and "key_concerns" not in content),
        ("done 标记未中断", bool(s["done"] and s["done"]["data"].get("interrupted") is False)),
        ("结构化字段解析成功", bool(structured.get("emotion") or structured.get("key_concerns"))),
        ("首个可见反馈早于整体 60%", bool(first_visible and total and first_visible / total < 0.6)),
    ]
    print()
    for name, passed in checks:
        print("  %s %s" % ("✅" if passed else "❌", name))

    if structured:
        print("\n  解读摘要: %s" % structured.get("summary", "")[:120])
        print("  对方情绪: %s" % structured.get("emotion", "")[:120])

    return {"ok": all(p for _, p in checks), "events": events, "content": content, "structured": structured}


def check_persistence(base_url: str, user_id: int, letter_id: int, streamed_content: str) -> bool:
    print("\n" + "=" * 72)
    print("D. 持久化与回读")
    print("=" * 72)

    db = SessionLocal()
    try:
        row = (
            db.query(AiGeneration)
            .filter(
                AiGeneration.generation_kind == "letter_analysis",
                AiGeneration.target_type == "letter",
                AiGeneration.target_id == letter_id,
            )
            .order_by(AiGeneration.id.desc())
            .first()
        )
        row_status = row.status if row else None
        row_len = len(row.content or "") if row else 0
        row_thinking = len(row.thinking or "") if row else 0
        row_structured = (row.structured_output or {}) if row else {}
    finally:
        db.close()

    headers = {"Authorization": "Bearer %s" % create_access_token(user_id)}
    client = httpx.Client(timeout=30.0)
    try:
        resp = client.get(
            base_url + READ_PATH,
            params={"target_type": "letter", "target_id": letter_id},
            headers=headers,
        )
        payload = (resp.json() or {}).get("data")
    finally:
        client.close()

    print("  落库 status: %s / 正文 %d 字 / 思考 %d 字" % (row_status, row_len, row_thinking))
    print("  回读 status: %s / 正文 %d 字" % (
        (payload or {}).get("status"), len((payload or {}).get("content") or "")
    ))

    checks = [
        ("ai_generation 有记录", row_status is not None),
        ("status = done", row_status == "done"),
        ("落库正文与推送一致", row_len == len(streamed_content.strip())),
        ("落库了思考过程", row_thinking > 0),
        ("落库了结构化字段", bool(row_structured.get("emotion") or row_structured.get("key_concerns"))),
        ("回读端点返回记录", payload is not None),
        ("回读正文与落库一致", bool(payload and payload.get("content") == streamed_content.strip())),
        ("回读带回 generation_id", bool(payload and payload.get("generation_id"))),
    ]
    print()
    for name, passed in checks:
        print("  %s %s" % ("✅" if passed else "❌", name))
    return all(p for _, p in checks)


# ---------------------------------------------------------------------- #
# E. 可中断
# ---------------------------------------------------------------------- #
def check_cancel(base_url: str, user_id: int, letter_id: int) -> bool:
    print("\n" + "=" * 72)
    print("E. 可中断（取消端点）")
    print("=" * 72)

    headers = {
        "Authorization": "Bearer %s" % create_access_token(user_id),
        "Content-Type": "application/json",
    }
    stream_client = httpx.Client(timeout=180.0)
    ctrl_client = httpx.Client(timeout=30.0)

    generation_id = None
    cancel_at = None
    done_at = None
    events = []
    try:
        started = time.time()
        with stream_client.stream(
            "POST", base_url + STREAM_PATH, json={"letter_id": letter_id}, headers=headers
        ) as resp:
            if resp.status_code != 200:
                print("  ❌ 请求失败: %s" % resp.read()[:300])
                return False

            current = None
            for line in resp.iter_lines():
                now = time.time() - started
                if line.startswith("event:"):
                    current = line[6:].strip()
                    continue
                if not line.startswith("data:"):
                    continue
                body = line[5:].strip()
                data = json.loads(body) if body else None
                events.append({"event": current, "data": data, "at": now})

                if current == "meta" and data:
                    generation_id = data.get("generation_id")

                # 拿到第一个正文增量就按「停止生成」——模拟用户看到字之后改主意
                if current == "delta" and cancel_at is None and generation_id:
                    cancel_at = now
                    ctl = ctrl_client.post(
                        base_url + (CANCEL_PATH % generation_id), headers=headers
                    )
                    print("  在 %.2fs 发出取消请求 → HTTP %s %s" % (now, ctl.status_code, ctl.text[:80].strip()))

                if current == "done":
                    done_at = now
                    break
    finally:
        stream_client.close()
        ctrl_client.close()

    s = summarize(events)
    content = "".join((e["data"] or {}).get("content", "") for e in s["delta"])
    stop_latency = (done_at - cancel_at) if (done_at and cancel_at) else None
    done_data = (s["done"] or {}).get("data") or {}

    print("  取消前已收到正文: %d 字 / %d 帧" % (len(content), len(s["delta"])))
    print("  取消 → 流结束耗时: %s" % ("%.2fs" % stop_latency if stop_latency is not None else "未结束"))

    db = SessionLocal()
    try:
        row = (
            db.query(AiGeneration)
            .filter(
                AiGeneration.generation_kind == "letter_analysis",
                AiGeneration.target_type == "letter",
                AiGeneration.target_id == letter_id,
            )
            .order_by(AiGeneration.id.desc())
            .first()
        )
        row_status = row.status if row else None
    finally:
        db.close()
    print("  落库 status: %s（中断后应保留半成品）" % row_status)

    checks = [
        ("meta 带回 generation_id", generation_id is not None),
        ("取消请求成功", cancel_at is not None),
        ("取消后流迅速结束（< 8s）", stop_latency is not None and stop_latency < 8.0),
        ("done 标记 interrupted", done_data.get("interrupted") is True),
        ("保留中断前的正文", len(content) > 0),
        ("落库 status = interrupted", row_status == "interrupted"),
    ]
    print()
    for name, passed in checks:
        print("  %s %s" % ("✅" if passed else "❌", name))
    return all(p for _, p in checks)


# ---------------------------------------------------------------------- #
# 本地真实 uvicorn
# ---------------------------------------------------------------------- #
def start_local_server():
    import uvicorn

    server = uvicorn.Server(
        uvicorn.Config(app, host="127.0.0.1", port=LOCAL_PORT, log_level="error")
    )
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()

    base = "http://127.0.0.1:%d" % LOCAL_PORT
    for _ in range(50):
        try:
            httpx.get(base + "/docs", timeout=1.0)
            return server, base
        except Exception:
            time.sleep(0.2)
    raise RuntimeError("本地 uvicorn 启动超时")


def main() -> int:
    print("=" * 72)
    print("信件「AI 帮我理解」流式链路验证")
    print("=" * 72)

    if not live_llm_enabled("letter_stream"):
        print(skip_reason("letter_stream"))
        return 0

    if not llm.api_key:
        print("❌ 未配置 AI_API_KEY，无法进行端到端验证")
        return 1
    print("使用模型: %s（降级链: %s）" % (llm.model, " → ".join(llm._candidates())))

    results = []
    results.append(("A. 结构化流切分", check_splitter()))
    results.append(("B. Prompt 层", check_prompt()))

    relation_id, user_id = pick_active_relation()
    letter_id = ensure_test_letter(relation_id, user_id)
    print("测试数据: relation=%d user=%d letter=%d" % (relation_id, user_id, letter_id))

    server = None
    try:
        server, base = start_local_server()
        normal = run_normal_stream(base, user_id, letter_id)
        results.append(("C. 协议层", normal.get("ok", False)))
        if normal.get("content"):
            results.append(
                ("D. 持久化与回读", check_persistence(base, user_id, letter_id, normal["content"]))
            )
        else:
            results.append(("D. 持久化与回读", False))
        results.append(("E. 可中断", check_cancel(base, user_id, letter_id)))
    finally:
        if server is not None:
            server.should_exit = True
            time.sleep(0.5)
        cleanup(relation_id, letter_id)
        print("\n  已清理测试信件与生成记录")

    print("\n" + "=" * 72)
    print("汇总")
    print("=" * 72)
    for name, passed in results:
        print("  %s %s" % ("✅" if passed else "❌", name))
    all_ok = all(p for _, p in results)
    print("\n%s" % ("✅ 信件流式链路全部通过" if all_ok else "❌ 存在未通过项，见上方明细"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
