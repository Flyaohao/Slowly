# -*- coding: utf-8 -*-
"""观点分析 → 丰富画像：端到端（**真调模型**，用户需求 #5）

跑法：`python tests/test_viewpoint_analysis.py`（backend 目录，需本地 MySQL + LLM 可用）

链路与断言：

  1. 造 user / questionnaire / profile（11 维）+ 一条观点（diary_entry）
  2. `ai_service.prepare_viewpoint_analysis` → 消费 SSE 事件，取 `result.structured_output`
  3. 断言结构化字段齐、维度 key 全部落在白名单内（模型自造 key 必须是 0 条）
  4. 模拟「用户点了确认」：把 AI 的建议**原样**交给
     `profile_service.enrich_from_viewpoint`
  5. 断言画像派生新版本、分数按幅度规则移动、证据行落进 explanation

关于稳定性：第 4 步的入参以 AI 输出为准；若这一次模型判 `suggest_enrich=false`
（观点太笼统时会这样），本脚本**不判失败**，而是改用一组等价的方向数据继续验证
「建议 → 写入」的衔接。理由与 `test_generation_stream` 的 F 用例同源——
单样本 LLM 输出会波动，把它的取舍当成断言，测的就是模型的当天心情。

⚠️ 基座收尾的两条硬规则（踩过坑）：

  - 每个自建 session 必须 close。`MysqlHarness.__exit__` 会 DROP 临时库，
    只要有连接被借出未归还，DROP 就会一直等下去。
  - 收尾必须写在 `try/finally` 里。异常路径下不 close，表现为「测试卡死」，
    真正的异常会被 __exit__ 的卡顿完全盖住（2026-09-27 实测，排查花了几轮）。
"""
import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
for p in (BACKEND_DIR, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

from mysql_harness import MysqlHarness, mysql_available  # noqa: E402
from live_llm_guard import live_llm_enabled, skip_reason  # noqa: E402
from app.models.user import User  # noqa: E402
from app.models.questionnaire import Questionnaire  # noqa: E402
from app.models.diary_entry import DiaryEntry  # noqa: E402
from app.repositories import profile_repo  # noqa: E402
from app.services import ai_service, ai_generation_service, profile_service  # noqa: E402
from app.services.profile_service import DIMENSION_DEFINITIONS  # noqa: E402

CHECKS = 0
FAILURES = []


def check(name, cond, detail=""):
    global CHECKS
    CHECKS += 1
    if cond:
        print("  PASS  %s" % name, flush=True)
    else:
        print("  FAIL  %s  %s" % (name, detail), flush=True)
        FAILURES.append(name)


#: 刻意选一条**指向明确、稳定**的观点（而不是含糊的抱怨），
#: 让它有较大概率触发 suggest_enrich=true，从而真正走到写入那一步。
VIEWPOINT_TEXT = (
    "我觉得再忙也应该每天留十分钟好好说说话。"
    "不是报备行程那种，是真的聊聊今天过得怎么样。"
    "要是连这点时间都挤不出来，我会觉得我们只是在搭伙过日子，不是在谈恋爱。"
)

DIMS = {
    "attachment_anxiety": 62.0,
    "attachment_avoidance": 38.0,
    "conflict_pursue": 70.0,
    "conflict_withdraw": 30.0,
    "defensive_response": 55.0,
    "emotional_validation_need": 76.0,
    "factual_explanation_need": 48.0,
    "personal_space_need": 44.0,
    "reassurance_need": 60.0,
    "directness_preference": 52.0,
    "softness_preference": 66.0,
}


def _run_body(h):
    """with 块内的全部逻辑（独立成函数，便于 try/finally 收尾）。"""
    db = h.db()
    try:
        u = User(email="vpa@test.local", password_hash="x")
        q = Questionnaire(title="测试问卷")
        db.add(u)
        db.add(q)
        db.commit()

        p = profile_repo.create_profile(
            db, user_id=u.id, questionnaire_id=q.id, profile_type="secure",
            confidence=0.7, summary="测试画像", version=1, origin="questionnaire",
        )
        profile_repo.add_dimension_scores(
            db, p.id,
            [{"dimension_key": k, "score": v, "explanation": "%s 初始说明" % k}
             for k, v in DIMS.items()],
        )
        entry = DiaryEntry(user_id=u.id, title="关于陪伴的看法", content=VIEWPOINT_TEXT)
        db.add(entry)
        db.commit()
        uid, entry_id = u.id, entry.id

        # v5.0 强制配置（D4）：真调模型，用全局真实配置补种
        from ai_config_seed import ensure_ai_config

        ensure_ai_config(db, uid, real=True)

        # ---- 1) 观点分析（真调模型）----
        prepared = ai_service.prepare_viewpoint_analysis(db, uid, entry_id)
        check("prepare 返回结构化模型",
              prepared["output_model"].__name__ == "ViewpointAnalysisOutput")
        check("prepare 的 target 指向观点",
              prepared["target_type"] == "diary_entry" and prepared["target_id"] == entry_id,
              prepared["target_type"])
        check("单身模式 relation_id 允许为空（观点是个人资产）",
              prepared["relation_id"] is None, prepared["relation_id"])

        structured = None
        result_content = ""
        events = ai_generation_service.stream_generation_events(prepared)
        try:
            for ev in events:
                if ev["event"] == "result":
                    structured = ev["data"].get("structured_output") or {}
                    result_content = ev["data"].get("content") or ""
                elif ev["event"] == "error":
                    check("模型调用未报错", False, ev["data"])
        finally:
            events.close()

        check("拿到结构化结果", bool(structured), structured)
        if not structured:
            return

        check("summary 非空", bool((structured.get("summary") or "").strip()),
              structured.get("summary"))
        check("confidence 在 [0,1]",
              isinstance(structured.get("confidence"), (int, float))
              and 0.0 <= float(structured["confidence"]) <= 1.0,
              structured.get("confidence"))
        check("suggest_enrich 是布尔",
              isinstance(structured.get("suggest_enrich"), bool),
              structured.get("suggest_enrich"))
        check("values 是数组", isinstance(structured.get("values"), list),
              structured.get("values"))
        check("正文段落不含字段名（双出口协议）",
              "summary" not in result_content and "confidence" not in result_content,
              result_content[:80])

        dims = structured.get("dimensions") or []
        bad = [d.get("dimension_key") for d in dims
               if d.get("dimension_key") not in DIMENSION_DEFINITIONS]
        check("维度 key 全部落在白名单内（模型不得自造）", not bad, bad)
        bad_dir = [d.get("direction") for d in dims
                   if d.get("direction") not in ("up", "down")]
        check("direction 只有 up/down", not bad_dir, bad_dir)

        # ---- 2) 模拟用户点「丰富到画像」----
        suggestions = {
            d["dimension_key"]: {
                "direction": d["direction"],
                "strength": d.get("strength") or "mild",
            }
            for d in dims
            if d.get("dimension_key") in DIMENSION_DEFINITIONS
        }
        conf = float(structured.get("confidence") or 0.0)
        summary = structured.get("summary") or ""

        if structured.get("suggest_enrich") and suggestions:
            print("  [模型判为可写入：维度=%s 置信度=%.2f]" % (list(suggestions), conf),
                  flush=True)
            payload = {
                "dimensions": list(suggestions),
                "directions": suggestions,
                "confidence": conf,
                "summary": summary,
            }
        else:
            print("  [模型这次判为不建议写入 → 改用手工等价数据验证写入链路]", flush=True)
            payload = {
                "dimensions": ["values_orientation", "reassurance_need"],
                "directions": {
                    "values_orientation": {"direction": "up", "strength": "moderate"},
                    "reassurance_need": {"direction": "up", "strength": "mild"},
                },
                "confidence": max(conf, 0.75),
                "summary": summary or "希望每天留出固定的交流时间",
            }

        res = profile_service.enrich_from_viewpoint(
            db, uid,
            viewpoint_id=entry_id,
            dimensions=payload["dimensions"],
            summary=payload["summary"],
            directions=payload["directions"],
            confidence=payload["confidence"],
        )
        check("丰富成功并派生新版本", res["version"] == 2, res)
        check("版本来源标为观点补充", res["origin"] == "viewpoint_enrich", res["origin"])

        latest = profile_repo.get_latest_profile(db, uid)
        check("最新版本已切换", latest.version == 2, latest.version)
        check("来源观点已落库", latest.source_viewpoint_id == entry_id,
              latest.source_viewpoint_id)

        scores = {s.dimension_key: s for s in profile_repo.get_dimension_scores(db, latest.id)}
        for key in payload["dimensions"]:
            if key in scores:
                exp = scores[key].explanation or ""
                check("维度 %s 的 explanation 带证据行" % key, "· 你的观点" in exp, exp[:60])

        versions = profile_service.list_versions(db, uid)
        v1 = [x for x in versions if x["version"] == 1][0]
        v1_scores = {s.dimension_key: s.score
                     for s in profile_repo.get_dimension_scores(db, v1["id"])}
        check("v1 分数原样保留（派生而非覆盖）",
              v1_scores["reassurance_need"] == 60.0, v1_scores.get("reassurance_need"))

        # ---- 3) 撤回 ----
        back = profile_service.restore_version(db, uid, v1["id"])
        check("撤回派生 v3", back["version"] == 3, back)
        now = profile_repo.get_latest_profile(db, uid)
        restored = {s.dimension_key: s.score
                    for s in profile_repo.get_dimension_scores(db, now.id)}
        check("撤回后 reassurance_need 回到 60",
              restored.get("reassurance_need") == 60.0, restored.get("reassurance_need"))
        check("撤回后 values_orientation 一并消失（v1 没有它）",
              "values_orientation" not in restored, sorted(restored.keys()))
    finally:
        # 必须收尾：否则基座的 DROP DATABASE 会被这根连接堵住
        db.close()


def main():
    # 真调LLM 的套件默认跳过（烧额度 + 额度耗尽会伪装成回归信号）。
    # 确认真机/真模型验收时：LIVE_LLM_VIEWPOINT=1 python tests/test_viewpoint_analysis.py
    if not live_llm_enabled("viewpoint_analysis"):
        print(skip_reason("viewpoint_analysis"), flush=True)
        return 0

    avail = mysql_available()
    if not avail:
        print("MySQL 不可用，跳过（不伪装通过）", flush=True)
        return 0
    print("MySQL %s / %s" % avail, flush=True)

    try:
        with MysqlHarness("vpa") as h:
            _run_body(h)
    except BaseException:
        # 基座的 __exit__ 里要 DROP DATABASE；异常路径下若有连接未归还，
        # 它会先卡住、把真正的错误盖掉。所以先把 traceback 落盘再让它抛。
        traceback.print_exc()
        sys.stderr.flush()
        raise

    print("\n断言 %d 项" % CHECKS, flush=True)
    if FAILURES:
        print("失败 %d 项：" % len(FAILURES), flush=True)
        for f in FAILURES:
            print("  - %s" % f, flush=True)
        return 1
    print("全部通过", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
