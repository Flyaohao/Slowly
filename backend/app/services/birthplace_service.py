"""出生地 → 经纬度/时区 查找（喂给 astrology_service 的上升星座精算）。

产品口径：出生地由用户自由填写（"杭州" / "浙江省杭州市" 都认），这里做
**本地城市表**匹配——不调外部地理编码服务（慢、要 key、且出生地是敏感
个人信息，不出网）。匹配不到就返回 None，由调用方退回日出 6 点的简化
推算，不硬编坐标。

坐标为城市中心约值（误差 <0.5°，对上升星座影响远小于 1 宫），时区为该地
**标准时区**（未计夏令时）——中国全境 +8 不受夏令时影响；海外记录若出生在
夏令时期间会有约 1 小时偏差，属已知近似。
"""
from typing import NamedTuple, Optional

from app.services.city_coords import CITY_COORDS


class BirthPlace(NamedTuple):
    """匹配结果：name = 命中的规范城市名（可直接展示/进 prompt）。"""

    name: str
    latitude: float  # 南纬为负
    longitude: float  # 西经为负
    utc_offset: float  # 标准时区，如 +8.0 / -5.0 / +5.5


def lookup_birth_place(text: Optional[str]) -> Optional[BirthPlace]:
    """自由文本 → 出生地坐标。匹配策略（都不要求精确全等）：

    1. 去空白后精确命中城市名；
    2. 城市名是输入的子串（"浙江省杭州市" 含 "杭州"）或反向
       （输入 ≥2 字且是城市名的子串）——**取最长命中**，避免 "杭"
       抢走 "杭州"。
    都匹配不到返回 None（调用方降级，不猜坐标）。
    """
    if not text:
        return None
    raw = text.strip()
    if not raw:
        return None

    hit = CITY_COORDS.get(raw)
    if hit is not None:
        return BirthPlace(raw, *hit)

    best_key: Optional[str] = None
    for key in CITY_COORDS:
        if len(key) < 2:
            continue
        if key in raw or (len(raw) >= 2 and raw in key):
            if best_key is None or len(key) > len(best_key):
                best_key = key
    if best_key is None:
        return None
    return BirthPlace(best_key, *CITY_COORDS[best_key])
