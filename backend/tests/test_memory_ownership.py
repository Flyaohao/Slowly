"""v3.2 §6 所有权 / §5.3 来源认识论默认（纯函数，hermetic）

断言：
  1. OWNERSHIP_BY_SOURCE：user/relation/system 三档归类；
  2. default_ownership 三元组（owner, creator, type）；
  3. EPISTEMIC_BY_SOURCE：anniversary/questionnaire/AI 蒸馏/chat_summary 默认；
  4. can_revoke_share 权限矩阵（§5.3 逐格）；
  5. can_hide_for_user：本人可操作、单方不牵连、非成员拒绝。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_ownership.py
"""
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
    print("v3.2 §6 所有权 / §5.3 授权与认识论默认")
    print("=" * 72)

    from app.services.memory_ownership import (
        DEFAULT_EPISTEMIC,
        EPISTEMIC_BY_SOURCE,
        OWNERSHIP_BY_SOURCE,
        can_hide_for_user,
        can_revoke_share,
        default_ownership,
        epistemic_default,
    )

    print("\n[1] OWNERSHIP_BY_SOURCE 归类")
    for src in ("letter", "diary", "chat_summary", "museum"):
        check(f"{src} → user", OWNERSHIP_BY_SOURCE[src] == "user")
    for src in ("dual", "questionnaire", "anniversary"):
        check(f"{src} → relation", OWNERSHIP_BY_SOURCE[src] == "relation")
    check("system_event → system", OWNERSHIP_BY_SOURCE["system_event"] == "system")

    print("\n[2] default_ownership 三元组")
    owner, creator, otype = default_ownership("diary", user_id=7)
    check("diary：owner=creator=7, type=user",
          (owner, creator, otype) == (7, 7, "user"), f"{owner},{creator},{otype}")
    owner, creator, otype = default_ownership("questionnaire", user_id=7)
    check("questionnaire：owner=NULL, creator=7, type=relation",
          (owner, creator, otype) == (None, 7, "relation"), f"{owner},{creator},{otype}")
    owner, creator, otype = default_ownership("system_event", user_id=7)
    check("system_event：全 NULL, type=system",
          (owner, creator, otype) == (None, None, "system"), f"{owner},{creator},{otype}")

    print("\n[3] EPISTEMIC_BY_SOURCE 默认")
    check("anniversary → self_report/business_event",
          epistemic_default("anniversary") == ("self_report", "business_event"),
          str(epistemic_default("anniversary")))
    check("questionnaire → interpretation/inference",
          epistemic_default("questionnaire") == ("interpretation", "inference"),
          str(epistemic_default("questionnaire")))
    for src in ("diary", "letter", "museum"):
        check(f"{src} → unknown/user_message（unknown 不撒谎）",
              epistemic_default(src) == ("unknown", "user_message"),
              str(epistemic_default(src)))
    check("chat_summary → summary/inference",
          epistemic_default("chat_summary") == ("summary", "inference"),
          str(epistemic_default("chat_summary")))
    check("未知源 → DEFAULT_EPISTEMIC", epistemic_default("???") == DEFAULT_EPISTEMIC)

    print("\n[4] can_revoke_share 矩阵（§5.3）")
    check("user 源：owner 本人可 revoke",
          can_revoke_share("user", 7, 7, actor_user_id=7) is True)
    check("user 源：他人不可 revoke",
          can_revoke_share("user", 7, 7, actor_user_id=8) is False)
    check("relation 源：creator 可 revoke",
          can_revoke_share("relation", None, 7, actor_user_id=7) is True)
    check("relation 源：非 creator 不可 revoke",
          can_revoke_share("relation", None, 7, actor_user_id=8) is False)
    check("system 源：无人可 revoke",
          can_revoke_share("system", None, None, actor_user_id=7) is False)

    print("\n[5] can_hide_for_user（单方开关不牵连）")
    members = (7, 8)
    check("本人（成员）可 hide 自己的视图",
          can_hide_for_user(7, actor_user_id=7, relation_member_ids=members) is True)
    check("另一方不可操作我的开关",
          can_hide_for_user(7, actor_user_id=8, relation_member_ids=members) is False)
    check("非关系成员被拒",
          can_hide_for_user(9, actor_user_id=9, relation_member_ids=members) is False)

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
