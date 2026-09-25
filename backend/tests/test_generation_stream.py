"""单次触发型 AI 生成全面流式化验证（v2.2）

v2.2 把信件改写 / AI 回信 / 表达改写 / 画像报告 / 量表分析全部切到与
「信件解读」同一套 ai_generation + SSE 基建。本脚本验证：

    A. Prompt 层（纯逻辑）：结构化场景的流式变体含双出口协议，
       字段契约与 Pydantic 模型一致
    A2. 归一化层（纯逻辑）：量表分析的分数一律以系统算出的为准
    A3. 源码级：所有 StreamingResponse 都过了 sse_encode（防 500）
    B. 协议层：/rewrite-letter/stream 真实 TCP 拿到完整事件序列
    C. 协议层：/rewrite/stream（表达改写）真实 TCP
    D. 持久化与回读：结果落 ai_generation，GET /generations/{kind} 取回
    E. 画像报告：无问卷时返回 400/40001（不真调模型）
    F. 量表分析：/questionnaires/{id}/analyze/stream，无画像时 400/40004

运行：
    cd backend
    python tests/test_generation_stream.py

注意：B/C/E/F 会真调大模型（每个约 1~2 分钟），并临时创建一封测试信件，结束时清理。

单样本 LLM 波动（P-C3 §6.3）：F 用例的结构化 JSON 由模型一次生成，
偶发校验不过（或被 max_tokens 截断）→ done.structured_output={} → 维度 0 条。
这是单样本波动、不是回归——**允许一次复跑**（用例内自动做，最多 2 次），
`bool(dims)` 护栏保留（它抓「维度整块为空」）。不要把复跑当回归去查产品代码。
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
    QuestionnaireAnalysisOutput,
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
from app.services.questionnaire_analysis_service import (  # noqa: E402
    ANALYSIS_PROMPT,
    _normalize_analysis,
)
from app.services.structured_stream import parse_structured_payload  # noqa: E402

LOCAL_PORT = 18082
BASE = "http://127.0.0.1:%d" % LOCAL_PORT

STREAM_REWRITE_LETTER = "/api/v1/couple/ai/rewrite-letter/stream"
STREAM_REWRITE = "/api/v1/couple/ai/rewrite/stream"
STREAM_PROFILE = "/api/v1/couple/ai/profile-report/stream"
STREAM_ANALYSIS = "/api/v1/questionnaires/%s/analyze/stream"
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
        TEST_LETTER_CONTENT,
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
        TEST_LETTER_CONTENT,
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
        "原始表达",
    ),
    (
        "量表分析",
        ANALYSIS_PROMPT.format(
            profile_label="焦虑依恋型",
            confidence=0.82,
            summary="渴望亲密又怕被冷落",
            dimension_text="- 依恋焦虑（attachment_anxiety）: 82分, 高\n- 独处冷静需求（personal_space_need）: 31分, 低",
        ),
        QuestionnaireAnalysisOutput,
        ["profile_analysis", "dimension_analyses", "strengths", "growth_tips", "communication_guide"],
        "## 测评结果",
    ),
]


def check_normalize() -> bool:
    """A2. 归一化层：模型编的分数必须被系统分数覆盖，维度必须齐全。

    这是量表分析最容易出错的地方——模型偶尔会自作主张改分数，于是同一个页面
    上的雷达图（读系统分数）和文字（读模型分数）就打架。归一化是唯一的防线。
    """
    print("\n" + "=" * 72)
    print("A2. 归一化层：分数以系统为准")
    print("=" * 72)

    dimension_scores = {"attachment_anxiety": 82.0, "personal_space_need": 31.0}
    model_output = {
        "profile_analysis": "你很在意关系里的确定感。",
        # 模型编了个不存在的维度，还给已有维度报了假分数
        "dimension_analyses": [
            {"key": "attachment_anxiety", "label": "依恋焦虑", "score": 999, "level": "极高",
             "analysis": "你会反复确认对方还在不在意你。"},
            {"key": "made_up_dimension", "label": "编的维度", "score": 88, "level": "高",
             "analysis": "不该出现在结果里。"},
        ],
        "strengths": "你很善于觉察自己的情绪。",
        "growth_tips": ["试着直接说出需求"],
        "communication_guide": "先说感受，再说诉求。",
    }

    result = _normalize_analysis(
        model_output,
        profile_type="anxious",
        profile_label="焦虑依恋型",
        confidence=0.82,
        dimension_scores=dimension_scores,
    )
    dims = result["dimension_analyses"]
    by_key = {d["key"]: d for d in dims}

    checks = [
        ("分数用系统值而非模型值", by_key["attachment_anxiety"]["score"] == 82.0),
        ("等级按分数重算", by_key["attachment_anxiety"]["level"] == "高"),
        ("模型多编的维度被丢弃", "made_up_dimension" not in by_key),
        ("模型漏掉的维度被补齐", "personal_space_need" in by_key),
        ("补齐的维度带兜底解读", bool(by_key["personal_space_need"]["analysis"])),
        ("按分数降序排列", [d["key"] for d in dims] == ["attachment_anxiety", "personal_space_need"]),
        ("正文与结构化字段都保留", bool(result["profile_analysis"] and result["growth_tips"])),
    ]
    for label, passed in checks:
        print("  %s %s" % ("✅" if passed else "❌", label))

    # 模型把「一段文字」输出成数组时的兜底。
    # 线上真跑踩过：communication_guide 被输出成 ["建议一", "建议二"]，
    # 一处类型不符就让整份结构化结果作废，卡片/维度/建议全丢。
    lenient = QuestionnaireAnalysisOutput.model_validate(
        {
            "profile_analysis": ["第一段", "第二段"],
            "communication_guide": ["建议一", "建议二"],
            "strengths": [],
            "dimension_analyses": [
                {"key": "attachment_anxiety", "analysis": ["你很容易担心被冷落。", "需要更多确认。"]}
            ],
        }
    )
    lenient_checks = [
        ("数组版 profile_analysis 被压成多行文本", "\n" in lenient.profile_analysis),
        ("数组版 communication_guide 被压成多行文本", lenient.communication_guide == "建议一\n建议二"),
        ("空数组不报错", lenient.strengths == ""),
        ("维度解读同样兜住", "\n" in lenient.dimension_analyses[0].analysis),
    ]
    for label, passed in lenient_checks:
        print("  %s %s" % ("✅" if passed else "❌", label))
    return all(p for _, p in checks) and all(p for _, p in lenient_checks)


def check_prompts() -> bool:
    print("\n" + "=" * 72)
    print("A. Prompt 层：各场景的流式变体")
    print("=" * 72)

    all_ok = True
    for name, base, model, fields, keep_marker in PROMPT_CASES:
        streamed = build_structured_stream_prompt(
            base, model, content_instruction="测试正文指令"
        )
        checks = [
            ("保留原文要素", keep_marker in streamed),
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


def check_stream_wrapping() -> bool:
    """A3. 源码级：每个 StreamingResponse 都必须把生成器过一遍 sse_encode。

    这条检查是被真实事故逼出来的：量表分析端点直接 yield 事件字典，
    少了 sse_encode 那层，Starlette 拿 dict 去 encode，第一个 chunk 就
    `AttributeError: 'dict' object has no attribute 'encode'`，HTTP 500。
    在 CI/离线阶段就能发现，不必等真跑一次模型。
    """
    print("\n" + "=" * 72)
    print("A3. 流式响应组装：都过了 sse_encode")
    print("=" * 72)

    import pathlib
    import re

    root = pathlib.Path(__file__).resolve().parent.parent / "app"
    total, bad = 0, []
    for path in root.rglob("*.py"):
        src = path.read_text(encoding="utf-8")
        for m in re.finditer(r"StreamingResponse\(", src):
            total += 1
            window = src[m.end(): m.end() + 300]
            if "sse_encode" not in window:
                bad.append("%s: %s" % (path.relative_to(root), window.split("\n")[0].strip()[:50]))

    print("  扫描到 %d 处 StreamingResponse" % total)
    for item in bad:
        print("  ❌ 未包 sse_encode：%s" % item)
    if not bad:
        print("  ✅ 全部已包装")
    return total > 0 and not bad


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
            AiGeneration.generation_kind.in_(
                ["expression_rewrite", "profile_report", "questionnaire_analysis"]
            )
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


def active_questionnaire_id():
    from app.repositories import questionnaire_repo
    db = SessionLocal_()
    try:
        q = questionnaire_repo.get_active_questionnaire(db)
        return q.id if q else None
    finally:
        db.close()


def load_dimension_scores(user_id: int) -> dict:
    """取库里这个人最新的维度分数，用于核对归一化是否真的生效。"""
    from app.repositories import profile_repo
    db = SessionLocal_()
    try:
        profile = profile_repo.get_latest_profile(db, user_id)
        if not profile:
            return {}
        return {s.dimension_key: s.score for s in profile_repo.get_dimension_scores(db, profile.id)}
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
    results.append(("A2. 归一化层", check_normalize()))
    results.append(("A3. 流式组装", check_stream_wrapping()))

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

        # F. 量表分析流式（有画像则真跑，无画像应 400/40004）
        print("\n" + "=" * 72)
        print("F. /questionnaires/{id}/analyze/stream")
        print("=" * 72)
        qid = active_questionnaire_id()
        if qid is None:
            print("  ⚠️ 库里没有启用的问卷，跳过")
            results.append(("F. 量表分析流式", True))
        else:
            # P-C3 §6.3 结论（读码核实）：done 帧的 structured_output 是
            # **归一化后**的——ai_generation_service 里
            # _validate_structured → _apply_finalizer 都发生在 result/done
            # yield 之前，模型原始 JSON 只进 warning 日志（structured_raw）。
            # 「维度 0 条」= 模型 JSON 校验不过/被截断 → structured=None →
            # done 携带 {}，不是「原始输出泄漏到断言」。
            # 处置：单样本波动允许一次复跑（最多 2 次），bool(dims) 护栏保留。
            evidence_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "..", ".workbuddy", "evidence", "pc3", "generation_stream_F_done.jsonl",
            )
            ok_f = False
            for attempt in (1, 2):
                r = stream_once(STREAM_ANALYSIS % qid, None, headers)
                if not r["ok"]:
                    ok_f = r["status"] == 400
                    print("  无画像，预期拒绝：HTTP %s %s" % (r["status"], "✅" if ok_f else "❌"))
                    break
                structured = (r["done"] or {}).get("structured_output") or {}
                dims = structured.get("dimension_analyses") or []
                # done 帧原始 JSON 落盘（§6.3 证据：归一化后形态）
                try:
                    with open(evidence_path, "a", encoding="utf-8") as f:
                        f.write(json.dumps({
                            "attempt": attempt,
                            "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                            "done": r["done"],
                        }, ensure_ascii=False) + "\n")
                except OSError:
                    pass
                attempt_ok = (
                    r["names"][-1:] == ["done"]
                    and r["delta_chars"] > 100
                    and bool(dims)
                )
                print("  第 %d 次 有画像，真实生成：delta %d 字，维度 %d 条"
                      % (attempt, r["delta_chars"], len(dims)))

                # 归一化生效的硬证据：结果里的分数必须等于库里的维度分
                db_scores = load_dimension_scores(user_id)
                if db_scores and dims:
                    same = all(
                        abs(float(d.get("score", -1)) - float(db_scores[d["key"]])) < 0.01
                        for d in dims
                        if d.get("key") in db_scores
                    )
                    attempt_ok = attempt_ok and same
                    print("  %s 分数与库内一致（归一化生效）" % ("✅" if same else "❌"))
                print("  %s 整体（第 %d 次）" % ("✅" if attempt_ok else "❌", attempt))
                ok_f = attempt_ok
                # 复跑只对「维度整块为空」的波动开；维度非空还失败 = 真问题，不掩盖
                if attempt_ok or bool(dims):
                    break
                print("  ⚠️ 维度整块为空（LLM 单样本波动），按 §6.3 允许复跑一次")
            results.append(("F. 量表分析流式", ok_f))
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
