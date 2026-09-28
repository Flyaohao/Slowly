"""人格画像页「性格辅助信息」的只读组装层。

2026-09-28 用户拍板：星盘/MBTI 此前只作为军师 prompt 的辅助块
（astrology_service.build_personality_block），画像页不展示；本次在
「人格画像」页新增「性格辅助信息」展示区，数据统一由本服务在后端算好，
客户端零计算、拿不到生日原值（伴侣侧隐私最小化，只给星座结论）。

产品口径不变（astrology_service 模块 docstring）：
问卷画像为主，MBTI 次之，星座/星盘最弱——本服务不改 12 维度评分体系，
不碰 enrich/版本链路，也不触 LLM。
"""
from typing import Optional

from app.repositories import couple_repo, user_repo
from app.services.astrology_service import (
    SIGN_TRAITS,
    get_mbti_label,
    get_moon_sign,
    get_rising_sign,
    get_zodiac,
)

#: 展示版口径（人格画像页用）。与 prompt 注入用的 REFERENCE_WEIGHT_NOTE 是同一
#: 事实，但措辞不同：页面上刚展示完 MBTI/星座，紧跟一句「不得作为专业结论」
#: 读起来自相矛盾（2026-09-28 用户反馈），展示处换成中性说明。
REFERENCE_NOTE_DISPLAY = (
    "性格参考说明：军师的判断以问卷画像为主，这里的 MBTI 与星座/星盘仅作辅助参考。"
)


def _zodiac_interpretation(
    zodiac: Optional[str],
    moon_sign: Optional[str],
    rising_sign: Optional[str],
) -> Optional[str]:
    """星座 → 分角色解读（太阳=做事风格 / 月亮=情绪需求 / 上升=第一印象）。

    2026-09-28 用户反馈：只列三个星座名「不够全面」，每个角色补一句行为化
    解读（astrology_service.SIGN_TRAITS）。缺哪个角色就不写哪行，不编数据。
    """
    lines = []
    for role, key, sign in (
        ("太阳", "sun", zodiac),
        ("月亮", "moon", moon_sign),
        ("上升", "rising", rising_sign),
    ):
        if sign and sign in SIGN_TRAITS:
            lines.append(f"{role} {sign}座——{SIGN_TRAITS[sign][key]}。")
    return "\n".join(lines) if lines else None


def build_personality_entry(
    mbti: Optional[str],
    birthday,
    birth_hour: Optional[int],
    birth_place: Optional[str],
) -> dict:
    """user_profile 原始字段 → 展示用 dict（纯函数，不碰 DB）。

    任何一项缺失都如实返回 null（不硬塞默认值）；filled 供客户端走
    「去资料页补充」引导分支。不返回 birthday 原值。
    """
    label = get_mbti_label(mbti)
    mbti_upper = mbti.strip().upper() if mbti and label else None
    zodiac = get_zodiac(birthday)
    moon_sign = get_moon_sign(birthday) if zodiac else None
    rising_sign = get_rising_sign(birthday, birth_hour, birth_place)
    return {
        "mbti": mbti_upper,
        "mbti_name": label[0] if label else None,
        "mbti_description": label[1] if label else None,
        "zodiac": zodiac,
        "moon_sign": moon_sign,
        "rising_sign": rising_sign,
        "zodiac_interpretation": _zodiac_interpretation(zodiac, moon_sign, rising_sign),
        "filled": bool(label or birthday is not None),
    }


def _entry_for(db, user_id: int) -> dict:
    profile = user_repo.get_profile_by_user_id(db, user_id)
    if profile is None:
        return {
            "mbti": None,
            "mbti_name": None,
            "mbti_description": None,
            "zodiac": None,
            "moon_sign": None,
            "rising_sign": None,
            "filled": False,
        }
    return build_personality_entry(
        profile.mbti,
        profile.birthday,
        profile.birth_hour,
        profile.birth_place,
    )


def get_personality_info(db, user_id: int) -> dict:
    """自己 + 伴侣的性格辅助信息，一次返回；未绑定伴侣 partner=None。"""
    partner_entry: Optional[dict] = None
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if relation is not None:
        partner_id = (
            relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id
        )
        partner_entry = _entry_for(db, partner_id)
    return {
        "reference_note": REFERENCE_NOTE_DISPLAY,
        "me": _entry_for(db, user_id),
        "partner": partner_entry,
    }
