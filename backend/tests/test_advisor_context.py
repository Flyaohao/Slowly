"""
P0-5 验收（非流式）：/ai/chat 响应含 evidence，三块依据非空。

前置（本地库已具备）：
  - user_id=4 有画像（seed_test_profile）
  - ai_memory 有记录（P0-3 写信验证）
  - ai_knowledge_chunk 有语料（seed_knowledge）

断言：
  a) AdvisorContext.to_display() 结构与纯类型
  b) _preprocess 组装的 evidence：self_profile_card 非空、
     recalled_memories 非空、theory_chunks 非空（有画像+记忆+理论的前提下）
  c) chat() 响应 dict 含 evidence 键（mock 掉 LLM，不花 token）
  d) evidence 与 prompt 用的是同一份画像卡（零新增 LLM 的证据）

运行：cd backend && python tests/test_advisor_context.py
"""
import os
import sys

# 测试隔离：chat() 会触发 distill_in_background 真调模型写库，进程退出后才落
os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def case_display_structure():
    print("\n[1] AdvisorContext.to_display 结构")
    from app.schemas.advisor_context import AdvisorContext

    d = AdvisorContext(
        scene_key="private_advisor",
        self_profile_card="【画像】X",
        partner_profile_card="",
        relationship_pattern="",
        recalled_memories=[{"content": "m1", "source": "偏好", "created_at": "2026-09-24"}],
        theory_chunks=[{"title": "T", "snippet": "s", "score": 0.8}],
        avatar_name="小翻译",
        voice_style="direct",
    ).to_display()
    for key in (
        "scene_key", "self_profile_card", "partner_profile_card",
        "relationship_pattern", "recalled_memories", "theory_chunks",
        "avatar_name", "voice_style", "voice_style_label",
    ):
        check(f"含字段 {key}", key in d)
    check("voice_style_label 映射中文", d["voice_style_label"] == "直接", d["voice_style_label"])
    check("记忆项三键齐全", set(d["recalled_memories"][0]) == {"content", "source", "created_at"})
    check("理论项三键齐全", set(d["theory_chunks"][0]) == {"title", "snippet", "score"})


def case_preprocess_evidence():
    print("\n[2] _preprocess 组装 evidence（真实库）")
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation
    from app.services.ai_service import _preprocess

    db = SessionLocal()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        ctx = _preprocess(
            db, rel.user_a_id, rel.id, None,
            "private_advisor", "他三天没回我消息了，我该怎么办？",
        )
        ev = ctx.get("evidence")
        check("ctx 含 evidence", isinstance(ev, dict), type(ev).__name__)
        if not isinstance(ev, dict):
            return
        check("self_profile_card 非空", bool(ev.get("self_profile_card")),
              ev.get("self_profile_card", "")[:40])
        check("self_profile_card 是中文画像卡",
              ev.get("self_profile_card", "").startswith("【画像】"))
        check("recalled_memories 非空", len(ev.get("recalled_memories") or []) > 0,
              str(len(ev.get("recalled_memories") or [])))
        mem0 = (ev.get("recalled_memories") or [{}])[0]
        check("记忆项含 content", bool(mem0.get("content")))
        check("theory_chunks 非空", len(ev.get("theory_chunks") or []) > 0,
              str(len(ev.get("theory_chunks") or [])))
        th0 = (ev.get("theory_chunks") or [{}])[0]
        check("理论项含 title/snippet", bool(th0.get("title")) or bool(th0.get("snippet")),
              str(th0)[:60])
        check("avatar_name 非空", bool(ev.get("avatar_name")), ev.get("avatar_name"))
        check("voice_style 非空", bool(ev.get("voice_style")), ev.get("voice_style"))

        # 零新增 LLM：evidence.self_profile_card 与 prompt 用的 user_profile 逐字节一致
        # （从留档的 messages 里找不到画像卡——它在 system；直接比对 ctx 里没有单独
        #  存 user_profile。改为：evidence 卡片内容 == 重新调 _format_profile 的结果）
        from app.services.ai_service import _format_profile
        from app.repositories import profile_repo
        up = profile_repo.get_latest_profile(db, rel.user_a_id)
        us = {d.dimension_key: d.score for d in profile_repo.get_dimension_scores(db, up.id)} if up else {}
        check("evidence 画像卡与 prompt 同源",
              ev["self_profile_card"] == _format_profile(up, us))
    finally:
        db.rollback()
        db.close()


def case_chat_response_has_evidence():
    print("\n[3] chat() 响应含 evidence（mock LLM）")
    import app.services.ai_service as ai_service
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation

    # `client=None`：生产侧 `_call_llm` v5.0 起由调用方经
    # `build_chat_client` 传入 `client=`。桩不接该参数会抛
    # `TypeError: unexpected keyword argument 'client'`（2026-10-02 修复）。
    def _fake_call_llm(messages, scene_key, client=None):
        return {
            "raw_text": "测试回复：我理解你的焦虑。",
            "summary": "测试",
            "emotion_validation": "",
            "partner_possible_meaning": "",
            "suggested_reply": "",
            "do_not_say": "",
            "next_step": "",
            "risk_level": "normal",
            "theory_refs": [],
        }

    orig = ai_service._call_llm
    ai_service._call_llm = _fake_call_llm
    db = SessionLocal()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        result = ai_service.chat(
            db, rel.user_a_id, rel.id, None,
            "private_advisor", "他三天没回我消息，我该主动找他吗？",
        )
        check("响应含 evidence 键", "evidence" in result)
        ev = result.get("evidence")
        check("evidence 是 dict 或 None", ev is None or isinstance(ev, dict))
        check("非 blocked 时 evidence 非空", isinstance(ev, dict) and bool(ev),
              str(type(ev)))
        if isinstance(ev, dict):
            check("含三块：画像", bool(ev.get("self_profile_card")))
            check("含三块：记忆", len(ev.get("recalled_memories") or []) > 0)
            check("含三块：理论", len(ev.get("theory_chunks") or []) > 0)
    except Exception as exc:
        check("chat() 不抛异常", False, repr(exc))
    finally:
        ai_service._call_llm = orig
        db.rollback()
        db.close()


def main() -> int:
    print("=" * 72)
    print("P0-5 依据可视化（非流式 evidence）")
    print("=" * 72)
    case_display_structure()
    case_preprocess_evidence()
    case_chat_response_has_evidence()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
