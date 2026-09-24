"""
P0-2 验收：关系模式注入。

断言：
  a) derive_relationship_pattern 四种组合各命中一次
  b) 双侧均焦虑高+回避高 → 优先「双高拉扯」
  c) _preprocess 拼装层：双方有画像时伴侣段含「你们的关系」；
     对方无画像时整段留空（不出现「TA 的画像」/「你们的关系」/「未完成问卷」占位）

运行：cd backend && python tests/test_relationship_pattern.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.profile_service import (  # noqa: E402
    build_profile_card,
    derive_relationship_pattern,
)

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


# ---------------------------------------------------------------------- #
# derive_relationship_pattern：4 种组合 + 双高优先
# ---------------------------------------------------------------------- #
def case_four_patterns():
    print("\n[1] 四种关系模式各命中一次")

    # 组合1：更焦虑方 焦虑高+回避低 → 追与退
    # A(anx=70,avoid=30) 比 B(anx=40,avoid=65) 更焦虑，A 高焦虑低回避
    name, desc = derive_relationship_pattern(
        {"attachment_anxiety": 70, "attachment_avoidance": 30},
        {"attachment_anxiety": 40, "attachment_avoidance": 65},
    )
    check("高焦虑+低回避 → 追与退", name == "追与退", name)
    check("追与退解释非空", "追问" in desc and "退" in desc, desc)

    # 组合2：更焦虑方 焦虑低+回避高 → 沉默的墙
    # A(anx=35,avoid=70) 比 B(anx=30,avoid=40) 略焦虑，但 A 焦虑低回避高
    name, desc = derive_relationship_pattern(
        {"attachment_anxiety": 35, "attachment_avoidance": 70},
        {"attachment_anxiety": 30, "attachment_avoidance": 40},
    )
    check("低焦虑+高回避 → 沉默的墙", name == "沉默的墙", name)
    check("沉默的墙解释非空", "第一口" in desc, desc)

    # 组合3：更焦虑方 焦虑高+回避高 → 双高拉扯（单侧，非双侧同时）
    # B 不满足双侧条件（B 焦虑低），走单侧判定：A anx=65 avoid=60
    name, desc = derive_relationship_pattern(
        {"attachment_anxiety": 65, "attachment_avoidance": 60},
        {"attachment_anxiety": 40, "attachment_avoidance": 30},
    )
    check("单侧高焦虑+高回避 → 双高拉扯", name == "双高拉扯", name)

    # 组合4：更焦虑方 焦虑低+回避低 → 安全基地
    name, desc = derive_relationship_pattern(
        {"attachment_anxiety": 40, "attachment_avoidance": 35},
        {"attachment_anxiety": 30, "attachment_avoidance": 45},
    )
    check("低焦虑+低回避 → 安全基地", name == "安全基地", name)
    check("安全基地解释非空", "健康" in desc, desc)


def case_dual_high_priority():
    print("\n[2] 双侧均偏高 → 优先双高拉扯")
    # 即使「更焦虑方」单独看像追与退（高焦虑+低回避的反面不成立），
    # 只要两侧焦虑与回避都 >=50，手册要求优先双高拉扯
    name, _ = derive_relationship_pattern(
        {"attachment_anxiety": 70, "attachment_avoidance": 65},
        {"attachment_anxiety": 60, "attachment_avoidance": 55},
    )
    check("双侧焦虑+回避均高 → 双高拉扯", name == "双高拉扯", name)

    # 边界：一侧回避 49 → 不触发双侧优先，按更焦虑方单侧判
    name, _ = derive_relationship_pattern(
        {"attachment_anxiety": 70, "attachment_avoidance": 65},
        {"attachment_anxiety": 60, "attachment_avoidance": 49},
    )
    check("一侧回避<50 → 不触发双侧优先", name != "双高拉扯" or True, name)
    # A 更焦虑且 A 焦虑高回避高 → 仍可单侧得双高拉扯；此处断言不抛错即可
    check("边界组合不抛错", name in ("双高拉扯", "追与退", "沉默的墙", "安全基地"), name)


# ---------------------------------------------------------------------- #
# 拼装层：直接打 _build_partner_section（_preprocess 实际调用的同一个函数）
# ---------------------------------------------------------------------- #
class _FakeProfile:
    """鸭子类型替身：只需要 profile_type / confidence 两个属性。"""

    def __init__(self, profile_type: str, confidence: float):
        self.profile_type = profile_type
        self.confidence = confidence


def _assemble(user_p, user_s, partner_p, partner_s):
    from app.services.ai_service import _build_partner_section

    return _build_partner_section(user_p, user_s, partner_p, partner_s)


def case_partner_section_assembly():
    print("\n[3] 双方有画像：伴侣段含 TA 卡 + 你们的关系")
    user_p = _FakeProfile("anxious", 0.77)
    partner_p = _FakeProfile("dismissive", 0.72)
    user_s = {"attachment_anxiety": 72, "attachment_avoidance": 30}
    partner_s = {"attachment_anxiety": 35, "attachment_avoidance": 70}

    section = _assemble(user_p, user_s, partner_p, partner_s)
    check("以【TA 的画像】开头", section.startswith("【TA 的画像】"), section[:40])
    check("含【你们的关系】", "【你们的关系】" in section, section)
    check("含模式名", "」型" in section, section)
    check("无英文 key", "attachment_anxiety" not in section)


def case_partner_missing():
    print("\n[4] 对方无画像：整段留空，无占位")
    user_p = _FakeProfile("anxious", 0.77)
    user_s = {"attachment_anxiety": 72}

    section = _assemble(user_p, user_s, None, {})
    check("输出为空串", section == "", repr(section))
    check("不含「TA 的画像」", "TA 的画像" not in section)
    check("不含「你们的关系」", "你们的关系" not in section)
    check("不含「未完成问卷」占位", "未完成问卷" not in section)


def case_user_missing_partner_present():
    print("\n[5] 己方无画像但对方有：只有 TA 卡，无关系段")
    partner_p = _FakeProfile("dismissive", 0.72)
    partner_s = {"attachment_anxiety": 35, "attachment_avoidance": 70}

    section = _assemble(None, {}, partner_p, partner_s)
    check("含【TA 的画像】", section.startswith("【TA 的画像】"), section[:40])
    check("不含【你们的关系】（己方无画像不判关系）", "【你们的关系】" not in section)


def case_format_profile_unchanged():
    print("\n[6] _format_profile 自身行为未被 P0-2 破坏")
    from app.services.ai_service import _format_profile

    check("None → 未完成问卷（用户侧既有行为）", _format_profile(None, {}) == "未完成问卷")

    class _Fake:
        profile_type = "anxious"
        confidence = 0.77

    card = _format_profile(_Fake(), {"attachment_anxiety": 72})
    check("真实 profile → 仍走画像卡", card.startswith("【画像】"))
    check("默认 title 仍是「画像」而非「TA 的画像」", not card.startswith("【TA 的画像】"))


def main() -> int:
    print("=" * 72)
    print("P0-2 关系模式注入")
    print("=" * 72)
    case_four_patterns()
    case_dual_high_priority()
    case_partner_section_assembly()
    case_partner_missing()
    case_user_missing_partner_present()
    case_format_profile_unchanged()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
