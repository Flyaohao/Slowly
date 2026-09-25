"""v3.2 §1 身份分配 / 四不变量校验 / enforce（纯函数，hermetic）

断言：
  1. assign_identity：业务事件 / 自述 / 转述（强制 attributed_report，
     hint 不得覆盖）/ observation 四分支；
  2. validate_identity：附录 §1.1 四条不变量逐字（合规 None、违规给原因）；
  3. enforce_identity：先纠正后报错——可纠正两例 + 不可纠正抛
     IdentityViolation。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_identity.py
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
    print("v3.2 §1 身份分配与不变量")
    print("=" * 72)

    from app.services.memory_identity import (
        Identity,
        IdentityViolation,
        assign_identity,
        enforce_identity,
        validate_identity,
    )

    print("\n[1] assign_identity 流程图")
    ident = assign_identity(evidence_author_user_id=None, assertion_origin="business_event")
    check("业务事件 → reported/attributed NULL + system_event",
          (ident.reported_by_user_id, ident.attributed_to_user_id,
           ident.epistemic_type, ident.assertion_origin)
          == (None, None, "system_event", "business_event"), str(ident))

    ident = assign_identity(evidence_author_user_id=1, claimed_speaker_user_id=1)
    check("作者=说话人 → self_report",
          (ident.reported_by_user_id, ident.attributed_to_user_id, ident.epistemic_type)
          == (1, 1, "self_report"), str(ident))

    ident = assign_identity(evidence_author_user_id=1, claimed_speaker_user_id=2)
    check("转述伴侣 → reported=1 attributed=2",
          (ident.reported_by_user_id, ident.attributed_to_user_id) == (1, 2), str(ident))
    check("转述 → 强制 attributed_report", ident.epistemic_type == "attributed_report")

    # 铁则：hint 说 self_report 也不许覆盖
    ident = assign_identity(evidence_author_user_id=1, claimed_speaker_user_id=2,
                            epistemic_hint="self_report")
    check("hint=self_report 不得覆盖铁则",
          ident.epistemic_type == "attributed_report", str(ident))

    ident = assign_identity(evidence_author_user_id=1, subject_user_id=2)
    check("观察对象非作者 → observation (attributed NULL)",
          (ident.epistemic_type, ident.attributed_to_user_id)
          == ("observation", None), str(ident))

    ident = assign_identity(evidence_author_user_id=1)
    check("无声称人/无外部主体 → self_report",
          ident.epistemic_type == "self_report", str(ident))

    print("\n[2] validate_identity（§1.1 四不变量逐字）")
    check("self_report 合规",
          validate_identity(reported_by_user_id=1, attributed_to_user_id=1,
                            epistemic_type="self_report", assertion_origin="user_message")
          is None)
    check("self_report reported≠attributed 违规",
          validate_identity(reported_by_user_id=1, attributed_to_user_id=2,
                            epistemic_type="self_report", assertion_origin="user_message")
          is not None)
    check("self_report reported NULL 违规",
          validate_identity(reported_by_user_id=None, attributed_to_user_id=None,
                            epistemic_type="self_report", assertion_origin="user_message")
          is not None)
    check("attributed_report 合规",
          validate_identity(reported_by_user_id=1, attributed_to_user_id=2,
                            epistemic_type="attributed_report", assertion_origin="user_message")
          is None)
    check("attributed_report 两者相等违规",
          validate_identity(reported_by_user_id=2, attributed_to_user_id=2,
                            epistemic_type="attributed_report", assertion_origin="user_message")
          is not None)
    check("observation 缺 subject 违规",
          validate_identity(reported_by_user_id=1, attributed_to_user_id=None,
                            epistemic_type="observation", assertion_origin="user_message",
                            subject_user_id=None) is not None)
    check("observation attributed 非空违规",
          validate_identity(reported_by_user_id=1, attributed_to_user_id=2,
                            epistemic_type="observation", assertion_origin="user_message",
                            subject_user_id=2) is not None)
    check("system_event 合规",
          validate_identity(reported_by_user_id=None, attributed_to_user_id=None,
                            epistemic_type="system_event", assertion_origin="business_event")
          is None)
    check("system_event reported 非空违规",
          validate_identity(reported_by_user_id=1, attributed_to_user_id=None,
                            epistemic_type="system_event", assertion_origin="business_event")
          is not None)
    check("system_event origin≠business_event 违规",
          validate_identity(reported_by_user_id=None, attributed_to_user_id=None,
                            epistemic_type="system_event", assertion_origin="user_message")
          is not None)

    print("\n[3] enforce_identity：先纠正后报错")
    fixed = enforce_identity(Identity(5, 5, "attributed_report", "user_message"))
    check("纠正：标错的 attributed_report(5,5) → self_report",
          (fixed.epistemic_type, fixed.reported_by_user_id) == ("self_report", 5),
          str(fixed))
    fixed = enforce_identity(Identity(1, 2, "self_report", "user_message"))
    check("纠正：标错的 self_report(1,2) → attributed_report",
          fixed.epistemic_type == "attributed_report", str(fixed))
    fixed = enforce_identity(Identity(1, 2, "self_report", "business_event"))
    check("纠正：origin=business_event → system_event 四元组",
          (fixed.reported_by_user_id, fixed.attributed_to_user_id,
           fixed.epistemic_type) == (None, None, "system_event"), str(fixed))
    fixed = enforce_identity(Identity(1, None, "observation", "user_message"),
                             subject_user_id=2)
    check("合规 observation 原样返回", fixed.epistemic_type == "observation")
    try:
        enforce_identity(Identity(1, None, "observation", "user_message"),
                         subject_user_id=None)
        check("缺 subject 的 observation → IdentityViolation", False)
    except IdentityViolation:
        check("缺 subject 的 observation → IdentityViolation", True)
    try:
        enforce_identity(Identity(None, 1, "self_report", "user_message"))
        check("reported NULL 自述 → IdentityViolation", False)
    except IdentityViolation:
        check("reported NULL 自述 → IdentityViolation", True)

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
