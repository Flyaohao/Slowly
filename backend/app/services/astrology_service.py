"""生日/时辰/出生地/MBTI → 可注入 prompt 的性格辅助块。

产品口径（用户拍板）：
  1. 生日 → 星座；出生时辰（到小时）+ 出生地 → 星盘；MBTI 由用户自选 16 型；
  2. 三者与问卷画像**并列**进入军师的了解渠道——知道得越多，人格描摹越贴切；
  3. 但星座/MBTI 属大众流行参考，**专业性弱于问卷画像**，块尾必须写明参考权重，
     避免模型拿星座去盖过 ECR-R 问卷维度的结论；
  4. 落点是「军师的记忆/prompt」，由 ai_service._format_profile 拼进画像卡。

精度分两档（都如实标注，不冒充星历精算）：
  - **有出生地且命中城市表** → 上升点按地方恒星时精算（太阳=星座日期分界、
    月亮=平均黄经不变）。近似点：时辰只到整点（±30min）、时区取标准时不计
    夏令时、坐标为城市中心约值——出生在城市边界或夏令时期间会有小幅偏差；
  - **无出生地 / 城市未收录** → 退回简化推算（日出 6 点起每 2 小时进一宫），
    输出里显式标注「非按经纬度精算」。
"""
import math
from datetime import date
from typing import Dict, List, Optional, Tuple

from app.services.birthplace_service import lookup_birth_place

#: 黄道十二宫（0 = 白羊）。太阳/月亮/上升共用同一索引表。
SIGNS: Tuple[str, ...] = (
    "白羊", "金牛", "双子", "巨蟹", "狮子", "处女",
    "天秤", "天蝎", "射手", "摩羯", "水瓶", "双鱼",
)

#: 星座分界（月, 日）升序排列：该日期起进入该星座，直到下一个分界前一天。
#: 未被任何分界覆盖的 12-22 ~ 01-19 归摩羯（见 get_zodiac 兜底）。
_ZODIAC_CUTOFFS: Tuple[Tuple[str, Tuple[int, int]], ...] = (
    ("水瓶", (1, 20)),
    ("双鱼", (2, 19)),
    ("白羊", (3, 21)),
    ("金牛", (4, 20)),
    ("双子", (5, 21)),
    ("巨蟹", (6, 22)),
    ("狮子", (7, 23)),
    ("处女", (8, 23)),
    ("天秤", (9, 23)),
    ("天蝎", (10, 24)),
    ("射手", (11, 23)),
    ("摩羯", (12, 22)),
)

#: MBTI 16 型：代号 → (中文别称, 一句话性格解读)。文案面向「军师怎么跟这人说话」，
#: 不写刻板断言，与画像卡 bands 的行为化写法保持一致。
MBTI_TYPES: Dict[str, Tuple[str, str]] = {
    "INTJ": ("建筑师", "独立有远见，先定框架再执行；讲逻辑和效率，讨厌被无谓打扰"),
    "INTP": ("逻辑学家", "好奇心重，爱拆解原理；重事实轻人情，讨论时先要「为什么」"),
    "ENTJ": ("指挥官", "果断善统筹，习惯主导推进；吃直球，反感拖沓和含糊"),
    "ENTP": ("辩论家", "点子多爱交锋，享受把想法辩清楚；怕被压制，不等于不讲理"),
    "INFJ": ("提倡者", "共情深、有原则，默默照顾双方感受；被忽略会内收而不是吵"),
    "INFP": ("调停者", "内心价值感强，重视被理解；语气比内容重要，硬碰硬会关门"),
    "ENFJ": ("主人公", "热心带动气氛，主动照顾客人；需要回应，冷场会让 TA 自我怀疑"),
    "ENFP": ("竞选者", "热情外放、联想快；需要新鲜感和肯定，被泼冷水容易泄气"),
    "ISTJ": ("物流师", "务实守信，按规矩和事实说话；要「怎么做、何时完成」的确切答案"),
    "ISFJ": ("守卫者", "体贴细致，默默付出记在心里；付出被当理所当然时最受伤"),
    "ESTJ": ("总经理", "讲秩序和执行，就事论事；先给结论和步骤，别绕情绪铺垫"),
    "ESFJ": ("执政官", "重视和谐与关系氛围，主动张罗；怕被排斥，需要明确的「我们是一起的」"),
    "ISTP": ("鉴赏家", "动手型问题解决者，冷静省话；冲突时先抽离，不是不在乎"),
    "ISFP": ("探险家", "温和随性，用行动表达在乎；讨厌被命令，喜欢自己节奏"),
    "ESTP": ("企业家", "行动快、反应直接，先做再说；耐不下长篇道理，要具体做法"),
    "ESFP": ("表演者", "外向爱热闹，情绪写在脸上；需要即时回应和陪伴感"),
}

#: 参考权重提示——三处使用方（画像卡 / 记忆关键词 / agent 工具）共用同一句口径。
REFERENCE_WEIGHT_NOTE = (
    "参考权重：问卷画像为主，MBTI 次之，星座/星盘最弱——后两者是大众流行参考，"
    "只用于补充性格倾向，不得作为专业结论，也不得盖过问卷维度的判断。"
)

#: 月亮平均黄经参数（J2000.0：2000-01-01 12:00 TT）。纯平均值，无摄动项。
_MOON_J2000_MEAN_LONGITUDE = 218.316
_MOON_MEAN_MOTION_DEG = 13.176396
_J2000_DATE = date(2000, 1, 1)

#: 简化上升星座：默认日出 06:00，约每 2 小时升一个星座（12 宫 / 24 小时）。
_DEFAULT_SUNRISE_HOUR = 6
_HOURS_PER_SIGN = 2

#: J2000.0 儒略日（2000-01-01 12:00 TT）。
_JD_J2000 = 2451545.0


def get_zodiac(birthday: Optional[date]) -> Optional[str]:
    """太阳星座（精确到日的分界，与通行占星表一致）。取不到生日返回 None。"""
    if birthday is None:
        return None
    key = (birthday.month, birthday.day)
    sign = "摩羯"  # 12-22 ~ 01-19 落在所有分界之前
    for candidate, cutoff in _ZODIAC_CUTOFFS:
        if key >= cutoff:
            sign = candidate
    return sign


def _sign_index_from_longitude(deg: float) -> int:
    return int(deg % 360 // 30)


def get_moon_sign(birthday: Optional[date]) -> Optional[str]:
    """月亮星座（按平均黄经推算，误差可达数度，可能跨界差一宫）。"""
    if birthday is None:
        return None
    days = (birthday - _J2000_DATE).days
    longitude = _MOON_J2000_MEAN_LONGITUDE + _MOON_MEAN_MOTION_DEG * days
    return SIGNS[_sign_index_from_longitude(longitude)]


def _julian_day_ut(year: int, month: int, day: int, hour_ut: float) -> float:
    """格里历日期 + UT 小时 → 儒略日。hour_ut 可为负/超 24（自动跨日）。"""
    a = (14 - month) // 12
    y = year + 4800 - a
    m = month + 12 * a - 3
    jdn = day + (153 * m + 2) // 5 + 365 * y + y // 4 - y // 100 + y // 400 - 32045
    return jdn - 0.5 + hour_ut / 24.0


def _ascendant_longitude(
    birthday: date, birth_hour: int, latitude: float, longitude_east: float, utc_offset: float
) -> Optional[float]:
    """出生时刻 + 经纬度 → 上升点黄经（度）。失败返回 None 由调用方降级。

    低精度星历：儒略日 → GMST（含 T² 项）→ 地方恒星时 → 标准上升点公式
    atan2(cos θ, -(sin θ·cos ε + tan φ·sin ε))。atan2 两支相差 180°，
    用时角落在 (180°,360°)（东方地平、正在升起）选出上升点那支。
    近似点：出生时辰只到整点、时区取标准时不计夏令时、坐标为城市中心值。
    """
    try:
        jd = _julian_day_ut(
            birthday.year, birthday.month, birthday.day, birth_hour - utc_offset
        )
        d = jd - _JD_J2000
        t = d / 36525.0
        gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t
        theta = math.radians((gmst + longitude_east) % 360.0)
        eps = math.radians(23.439291 - 0.0130042 * t)
        phi = math.radians(latitude)

        asc = math.degrees(
            math.atan2(
                math.cos(theta),
                -(math.sin(theta) * math.cos(eps) + math.tan(phi) * math.sin(eps)),
            )
        ) % 360.0

        # 选「升起的那支」：把候选点换算成时角，东边（正在升起）时角 ∈ (180°,360°)
        sin_asc = math.sin(math.radians(asc))
        ra = math.degrees(math.atan2(sin_asc * math.cos(eps), math.cos(math.radians(asc)))) % 360.0
        if ((math.degrees(theta) - ra) % 360.0) < 180.0:
            asc = (asc + 180.0) % 360.0
        return asc
    except (ValueError, OverflowError, ZeroDivisionError):
        return None


def get_rising_sign(
    birthday: Optional[date],
    birth_hour: Optional[int],
    birth_place: Optional[str] = None,
) -> Optional[str]:
    """上升星座。

    有出生地且命中城市表 → 按地方恒星时精算；未命中/未填 → 简化推算
    （太阳星座为 06:00 上升位，每 2 小时进一宫）。出生时辰缺失时一律返回
    None——不拿正午当默认值去编一个上升。
    """
    if birthday is None or birth_hour is None:
        return None

    place = lookup_birth_place(birth_place)
    if place is not None:
        lon_deg = _ascendant_longitude(
            birthday, birth_hour, place.latitude, place.longitude, place.utc_offset
        )
        if lon_deg is not None:
            return SIGNS[_sign_index_from_longitude(lon_deg)]

    zodiac = get_zodiac(birthday)
    if zodiac is None:
        return None
    sun_index = SIGNS.index(zodiac)
    steps = ((birth_hour - _DEFAULT_SUNRISE_HOUR) % 24) // _HOURS_PER_SIGN
    return SIGNS[(sun_index + steps) % 12]


def get_mbti_label(mbti: Optional[str]) -> Optional[Tuple[str, str]]:
    """MBTI → (中文别称, 一句话解读)。空值/未知代号返回 None（不硬塞默认型）。"""
    if not mbti:
        return None
    return MBTI_TYPES.get(mbti.strip().upper())


def build_personality_block(
    birthday: Optional[date],
    birth_hour: Optional[int],
    mbti: Optional[str],
    birth_place: Optional[str] = None,
) -> str:
    """拼出画像卡尾部的性格辅助块；一样都没有 → 返回空串（调用方不注入）。"""
    lines: List[str] = []
    place_text = (birth_place or "").strip()
    place_resolved = lookup_birth_place(place_text) if place_text else None

    zodiac = get_zodiac(birthday)
    if zodiac:
        astro = [f"太阳 {zodiac}座"]
        moon = get_moon_sign(birthday)
        if moon:
            astro.append(f"月亮 {moon}座")
        rising = get_rising_sign(birthday, birth_hour, birth_place)
        if rising:
            astro.append(f"上升 {rising}座")
        line = "、".join(astro)
        if rising and birth_hour is not None:
            if place_resolved is not None:
                line += f"（出生 {birth_hour:02d}:00 · {place_resolved.name}，按出生地经纬度推算）"
            elif place_text:
                line += f"（出生 {birth_hour:02d}:00 · {place_text}，出生地未收录，简化推算，非按经纬度精算）"
            else:
                line += f"（出生 {birth_hour:02d}:00，简化推算，非按经纬度精算）"
        lines.append(f"- 星座·星盘：{line}")

    if place_text:
        lines.append(f"- 出生地：{place_text}")

    label = get_mbti_label(mbti)
    if label:
        name, desc = label
        lines.append(f"- MBTI：{mbti.strip().upper()}「{name}」——{desc}")

    if not lines:
        return ""
    if zodiac or label:  # 只有出生地时无占星/MBTI 结论，不必强调参考权重
        lines.append(f"- {REFERENCE_WEIGHT_NOTE}")
    return "【性格辅助信息】\n" + "\n".join(lines)
