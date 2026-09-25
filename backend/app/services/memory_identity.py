"""身份分配与校验（v3.2 §1：身份与转述分离，reported ≠ attributed 强制 attributed_report）。

纯函数模块，无 DB。核心铁则（§1）：**reported ≠ attributed ⇒ epistemic 必须是
attributed_report**——模型给的 epistemic_hint 与此冲突时，assign 以流程图为准。

四条不变量（附录 §1.1，逐字）：
  1. self_report：reported NOT NULL AND reported = attributed
  2. attributed_report：两者 NOT NULL AND reported ≠ attributed
  3. observation：reported NOT NULL, attributed NULL, subject_user_id NOT NULL
  4. system_event：两者 NULL AND origin = 'business_event'
"""
from dataclasses import dataclass
from typing import Optional


class IdentityViolation(ValueError):
    """身份不变量违规（enforce 无法纠正时抛出，绝不静默写入）。"""


@dataclass(frozen=True)
class Identity:
    """四元组：谁报告、归于谁、认识论类型、证据来源。"""

    reported_by_user_id: Optional[int]
    attributed_to_user_id: Optional[int]
    epistemic_type: str
    assertion_origin: str

    def as_dict(self):
        return {
            "reported_by_user_id": self.reported_by_user_id,
            "attributed_to_user_id": self.attributed_to_user_id,
            "epistemic_type": self.epistemic_type,
            "assertion_origin": self.assertion_origin,
        }


def assign_identity(
    *,
    evidence_author_user_id: Optional[int],
    claimed_speaker_user_id: Optional[int] = None,
    subject_user_id: Optional[int] = None,
    assertion_origin: str = "user_message",
    epistemic_hint: Optional[str] = None,
) -> Identity:
    """按 §1 流程图分配身份四元组。

    分支：
      - 业务事件（origin='business_event' 或无证据作者）→ reported=NULL、
        attributed=NULL、epistemic=system_event、origin 强制 business_event。
      - 证据作者 == 声称说话人（或未给声称人且作者即主体）→ self_report。
      - 证据作者 ≠ 声称说话人（我转述伴侣 / 伴侣转述我）→ **强制**
        attributed_report，无视 epistemic_hint。
      - 有作者无声称人且 subject 非作者 → observation（attributed=NULL，
        subject 必填）。
    `epistemic_hint` 仅为调用方透传位：**流程图优先，hint 永不覆盖铁则**。
    """
    # 分支 1：业务事件 / 系统来源
    if assertion_origin == "business_event" or evidence_author_user_id is None:
        return Identity(
            reported_by_user_id=None,
            attributed_to_user_id=None,
            epistemic_type="system_event",
            assertion_origin="business_event",
        )

    reported = evidence_author_user_id

    # 分支 2：作者本人说的话
    if claimed_speaker_user_id is not None and claimed_speaker_user_id != reported:
        # 分支 3：转述他人 → 强制 attributed_report（铁则，hint 不得覆盖）
        return Identity(
            reported_by_user_id=reported,
            attributed_to_user_id=claimed_speaker_user_id,
            epistemic_type="attributed_report",
            assertion_origin=assertion_origin,
        )

    # 分支 2a：未指名他人，但观察对象是别人（subject 非作者本人）
    # → observation：reported=作者、attributed=NULL、subject 必填
    if claimed_speaker_user_id is None and subject_user_id is not None and (
        subject_user_id != reported
    ):
        return Identity(
            reported_by_user_id=reported,
            attributed_to_user_id=None,
            epistemic_type="observation",
            assertion_origin=assertion_origin,
        )

    # 分支 2b：自述 / 作者即主体 → self_report
    return Identity(
        reported_by_user_id=reported,
        attributed_to_user_id=reported,
        epistemic_type="self_report",
        assertion_origin=assertion_origin,
    )


def validate_identity(
    *,
    reported_by_user_id: Optional[int],
    attributed_to_user_id: Optional[int],
    epistemic_type: str,
    assertion_origin: str,
    subject_user_id: Optional[int] = None,
) -> Optional[str]:
    """四条不变量（附录 §1.1 逐字）。合规返回 None，违规返回原因字符串。"""
    if epistemic_type == "self_report":
        if reported_by_user_id is None:
            return "self_report: reported must NOT NULL"
        if reported_by_user_id != attributed_to_user_id:
            return "self_report: reported must equal attributed"
        if assertion_origin == "business_event":
            return "self_report: origin must not be business_event"
        return None
    if epistemic_type == "attributed_report":
        if reported_by_user_id is None or attributed_to_user_id is None:
            return "attributed_report: both reported and attributed must NOT NULL"
        if reported_by_user_id == attributed_to_user_id:
            return "attributed_report: reported must differ from attributed"
        return None
    if epistemic_type == "observation":
        if reported_by_user_id is None:
            return "observation: reported must NOT NULL"
        if attributed_to_user_id is not None:
            return "observation: attributed must NULL"
        if subject_user_id is None:
            return "observation: subject_user_id must NOT NULL"
        return None
    if epistemic_type == "system_event":
        if reported_by_user_id is not None or attributed_to_user_id is not None:
            return "system_event: reported and attributed must both NULL"
        if assertion_origin != "business_event":
            return "system_event: origin must be business_event"
        return None
    # 四不变量之外的合法认识论型（列注释 enum）：无 §1.1 不变量约束
    if epistemic_type in ("interpretation", "summary", "unknown"):
        return None
    return "unknown epistemic_type: %r" % (epistemic_type,)


def enforce_identity(identity: Identity, *, subject_user_id: Optional[int] = None) -> Identity:
    """先纠正后报错——永不静默写违规行。

    可纠正（derive）的场景：
      - origin=business_event 但 reported 非空 → 纠正为 system_event 四元组；
      - reported == attributed 但标了 attributed_report → 纠正 self_report；
      - reported ≠ attributed 但标了 self_report → 纠正 attributed_report；
      - observation 缺 subject → 报错（无法凭空补主体）；
      - 其余违规 → IdentityViolation。
    """
    # 业务事件优先：origin 说了算
    if identity.assertion_origin == "business_event":
        fixed = Identity(None, None, "system_event", "business_event")
        return fixed

    err = validate_identity(
        reported_by_user_id=identity.reported_by_user_id,
        attributed_to_user_id=identity.attributed_to_user_id,
        epistemic_type=identity.epistemic_type,
        assertion_origin=identity.assertion_origin,
        subject_user_id=subject_user_id,
    )
    if err is None:
        return identity

    # 可纠正 1：两者相等却被标 attributed_report
    if (
        identity.epistemic_type == "attributed_report"
        and identity.reported_by_user_id is not None
        and identity.reported_by_user_id == identity.attributed_to_user_id
    ):
        return Identity(
            identity.reported_by_user_id,
            identity.reported_by_user_id,
            "self_report",
            identity.assertion_origin,
        )
    # 可纠正 2：两者不等却被标 self_report
    if (
        identity.epistemic_type == "self_report"
        and identity.reported_by_user_id is not None
        and identity.attributed_to_user_id is not None
        and identity.reported_by_user_id != identity.attributed_to_user_id
    ):
        return Identity(
            identity.reported_by_user_id,
            identity.attributed_to_user_id,
            "attributed_report",
            identity.assertion_origin,
        )
    raise IdentityViolation(err)
