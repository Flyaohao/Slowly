"""P-B §6.1 验收：chat_mode 三档区分度（B1）。

同一输入跑三档 `prepare_chat`（**不调模型**），断言：

  1. 三档组装的 system 文本 hash 两两不同；
  2. 各自的关键指令片段在场、另两档的不在场（互斥）——
     quick「120 字」/ deep「300 字」/ expert「700 字」+「替代解释」；
  3. 估算 token 严格递增（quick < deep < expert）；
  4. 记忆 / 理论 / 历史条数符合 §1.2 差异矩阵
     （quick 0/0/6，deep ≤5/≤3/20，expert ≤10/≤5/40）。

历史条数靠预埋会话做确定性断言：预埋 50 条带唯一标记的消息
（created_at 置于未来，保证预埋段稳定排在追加消息之后），
三档各自取尾部窗口，标记计数必须精确等于 6/20/40。

数据隔离：真库写入全部在 finally 按 id 单行删除，
基线行数 == 收尾行数（session / message / prompt_version / memory）。

运行：cd backend && python tests/test_chat_mode_distinctness.py
"""
import hashlib
import os
import sys
from datetime import datetime, timedelta

# 测试隔离：_preprocess 链路可能触发 distill 后台线程写库
os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []

#: 预埋历史条数（> expert 的 40 窗口，保证三档窗口都落在预埋段内）
SEEDED_HISTORY = 50
#: 输入必须过安全护栏（不 blocked 才走完整链路）
TEST_INPUT = "他今天主动跟我说话了，我有点意外，你说我该不该提早上的事？"


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def _snapshot_ids(db):
    """基线 id 集：收尾时差集 = 本轮新写入的行，逐 id 单行删除。"""
    from app.models.ai import AiChatMessage, AiChatSession, AiMemory, AiPromptVersion

    return {
        "session": {r[0] for r in db.query(AiChatSession.id).all()},
        "message": {r[0] for r in db.query(AiChatMessage.id).all()},
        "prompt_version": {r[0] for r in db.query(AiPromptVersion.id).all()},
        "memory": {r[0] for r in db.query(AiMemory.id).all()},
    }


def _cleanup_extras(db, baseline):
    """删除基线之外本轮新写入的行（逐 id 单行，禁批量递归）。"""
    from app.models.ai import AiChatMessage, AiChatSession, AiMemory, AiPromptVersion

    deleted = {"session": [], "message": [], "prompt_version": [], "memory": []}
    # 先消息后会话，避免孤儿行
    for model, key in (
        (AiChatMessage, "message"),
        (AiPromptVersion, "prompt_version"),
        (AiMemory, "memory"),
        (AiChatSession, "session"),
    ):
        current = {r[0] for r in db.query(model.id).all()}
        for row_id in sorted(current - baseline[key]):
            db.query(model).filter(model.id == row_id).delete()
            deleted[key].append(row_id)
    db.commit()
    return deleted


def case_three_tiers_distinct():
    print("\n[1-3] 三档 system hash 两两不同 + 指令片段互斥 + token 严格递增")
    from app.core.database import SessionLocal
    from app.models.ai import AiChatMessage, AiChatSession
    from app.models.couple_relation import CoupleRelation
    from app.services import ai_service

    db = SessionLocal()
    baseline = _snapshot_ids(db)
    session_id = None
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        check("存在 active 情侣关系", rel is not None)
        if rel is None:
            return

        # 预埋确定性历史：created_at 置于未来 → 预埋段稳定排在
        # _preprocess 追加的 user 消息（now）之后，窗口计数可精确断言。
        from app.repositories import ai_repo

        session = ai_repo.create_session(
            db, rel.user_a_id, rel.id, "private_advisor",
            title="P-B区分度预埋", privacy_level="private",
        )
        session_id = session.id
        seed_base = datetime.now() + timedelta(days=1)
        for i in range(SEEDED_HISTORY):
            db.add(AiChatMessage(
                session_id=session_id,
                role="user" if i % 2 == 0 else "assistant",
                content=f"HISTMARK-{i}",
                created_at=seed_base + timedelta(seconds=i),
            ))
        db.commit()

        systems = {}
        humans = {}
        counts = {}
        for mode in ("quick", "deep", "expert"):
            prepared = ai_service.prepare_chat(
                db, rel.user_a_id, rel.id, session_id,
                "private_advisor", TEST_INPUT, chat_mode=mode,
            )
            check(f"{mode} 未被安全拦截", prepared.get("blocked") is False)
            check(f"{mode} chat_mode 回传", prepared.get("chat_mode") == mode,
                  str(prepared.get("chat_mode")))
            msgs = prepared["stream_messages"]
            systems[mode] = msgs[0]["content"]
            humans[mode] = msgs[1]["content"]
            ev = prepared.get("evidence") or {}
            counts[mode] = {
                "memory": len(ev.get("recalled_memories") or []),
                "rag": prepared.get("rag_hit", 0),
                "history": systems[mode].count("HISTMARK-"),
            }

        # 1. system hash 两两不同。
        # prepare 跑三档时历史窗口会各自追加一条 user 消息，天然就不同——
        # 为排除这个混杂，再用同一份 prompt 参数、只换尾部指令做一次纯组装
        # 对照（无历史增长），保证「差异来自档位指令」这件事本身成立。
        hashes = {m: hashlib.md5(s.encode("utf-8")).hexdigest()
                  for m, s in systems.items()}
        check("quick/deep system hash 不同（prepare）", hashes["quick"] != hashes["deep"],
              f'{hashes["quick"][:8]} vs {hashes["deep"][:8]}')
        check("deep/expert system hash 不同（prepare）", hashes["deep"] != hashes["expert"],
              f'{hashes["deep"][:8]} vs {hashes["expert"][:8]}')
        check("quick/expert system hash 不同（prepare）", hashes["quick"] != hashes["expert"],
              f'{hashes["quick"][:8]} vs {hashes["expert"][:8]}')

        from app.services.prompt_builder import CHAT_MODE_CONFIG
        from app.services.lc_prompt_builder import build_chat_messages

        pure_args = dict(
            scene_key="private_advisor",
            user_profile="【画像】X",
            partner_profile="",
            conflict_pattern="未确定",
            user_input=TEST_INPUT,
            history="user: 固定历史",
            rag_context="",
            memory_context="",
            mode="stream",
        )
        pure = {
            m: build_chat_messages(
                **pure_args, stream_instruction=CHAT_MODE_CONFIG[m]["instruction"]
            )[0]["content"]
            for m in ("quick", "deep", "expert")
        }
        pure_hashes = {m: hashlib.md5(s.encode("utf-8")).hexdigest()
                       for m, s in pure.items()}
        distinct = len(set(pure_hashes.values())) == 3
        check("同输入纯组装三档 hash 两两不同（差异来自指令）", distinct,
              str({m: h[:8] for m, h in pure_hashes.items()}))

        # 2. 指令片段互斥：各自在场、另两档不在场
        FRAGMENTS = {
            "quick": {"present": ["120 字"], "absent": ["300 字", "700 字", "替代解释"]},
            "deep": {"present": ["300 字"], "absent": ["120 字", "700 字", "替代解释"]},
            "expert": {"present": ["700 字", "替代解释"], "absent": ["120 字", "300 字"]},
        }
        for mode, spec in FRAGMENTS.items():
            for frag in spec["present"]:
                check(f"{mode} 含「{frag}」", frag in systems[mode],
                      systems[mode][-300:])
            for frag in spec["absent"]:
                check(f"{mode} 不含「{frag}」", frag not in systems[mode],
                      systems[mode][-300:])

        # 3. 估算 token 严格递增（2 字符 ≈ 1 token 口径）
        est = {m: (len(systems[m]) + len(humans[m])) // 2 for m in systems}
        check("token 估算 quick < deep", est["quick"] < est["deep"],
              f'{est["quick"]} < {est["deep"]}')
        check("token 估算 deep < expert", est["deep"] < est["expert"],
              f'{est["deep"]} < {est["expert"]}')
        print(f"  估算 token：quick={est['quick']} deep={est['deep']} "
              f"expert={est['expert']}")

        # 4. 记忆 / 理论 / 历史条数符合 §1.2 矩阵
        check("quick 记忆 = 0（不查）", counts["quick"]["memory"] == 0,
              str(counts["quick"]["memory"]))
        check("quick 理论 = 0（关闭）", counts["quick"]["rag"] == 0,
              str(counts["quick"]["rag"]))
        check("deep 记忆 ≤ 5", counts["deep"]["memory"] <= 5,
              str(counts["deep"]["memory"]))
        check("deep 理论 ≤ 3", counts["deep"]["rag"] <= 3,
              str(counts["deep"]["rag"]))
        check("expert 记忆 ≤ 10", counts["expert"]["memory"] <= 10,
              str(counts["expert"]["memory"]))
        check("expert 理论 ≤ 5", counts["expert"]["rag"] <= 5,
              str(counts["expert"]["rag"]))
        check("expert 记忆 ≥ deep（同源截断）",
              counts["expert"]["memory"] >= counts["deep"]["memory"],
              f'{counts["expert"]["memory"]} vs {counts["deep"]["memory"]}')
        check("expert 理论 ≥ deep（top_k 超集）",
              counts["expert"]["rag"] >= counts["deep"]["rag"],
              f'{counts["expert"]["rag"]} vs {counts["deep"]["rag"]}')

        # 历史窗口：预埋 50 条标记，三档窗口精确取 6/20/40
        check("quick 历史 = 6 条", counts["quick"]["history"] == 6,
              str(counts["quick"]["history"]))
        check("deep 历史 = 20 条", counts["deep"]["history"] == 20,
              str(counts["deep"]["history"]))
        check("expert 历史 = 40 条", counts["expert"]["history"] == 40,
              str(counts["expert"]["history"]))
        print(f"  条数矩阵：{counts}")
    finally:
        try:
            deleted = _cleanup_extras(db, baseline)
            after = _snapshot_ids(db)
            ok = all(baseline[k] == after[k] for k in baseline)
            check("基线行数 == 收尾行数（无残留）", ok,
                  {k: (len(baseline[k]), len(after[k])) for k in baseline})
            print(f"  已逐 id 清理："
                  f"session={deleted['session']} "
                  f"message={len(deleted['message'])} 条 "
                  f"prompt_version={len(deleted['prompt_version'])} 条")
        finally:
            db.close()


def case_resolve_and_validator():
    print("\n[4] 非法档位回落 deep（resolver + 请求校验器，零外部依赖）")
    from app.schemas.ai_schema import ChatRequest
    from app.services.prompt_builder import resolve_chat_mode

    # 大小写/空白归一后命中白名单；其余（未知/空/None）一律回落 deep
    for raw, expected in (
        (None, "deep"), ("", "deep"), ("  ", "deep"), ("turbo", "deep"),
        ("MODE", "deep"), ("quick", "quick"), ("EXPERT", "expert"),
        (" Deep ", "deep"),
    ):
        resolved = resolve_chat_mode(raw)
        check(f"resolver {raw!r} → {expected}", resolved == expected, resolved)

    check("ChatRequest 缺省 deep", ChatRequest(scene_key="s", message="m").chat_mode == "deep")
    check("ChatRequest 非法值回落 deep",
          ChatRequest(scene_key="s", message="m", chat_mode="turbo").chat_mode == "deep")
    check("ChatRequest 大小写归一",
          ChatRequest(scene_key="s", message="m", chat_mode="EXPERT").chat_mode == "expert")


def main() -> int:
    print("=" * 72)
    print("P-B §6.1 chat_mode 三档区分度（不调模型）")
    print("=" * 72)
    case_three_tiers_distinct()
    case_resolve_and_validator()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
