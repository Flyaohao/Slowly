from typing import Optional, List


SCENE_CONFIGS = {
    "private_advisor": {
        "name": "私人军师",
        "privacy_level": "private",
        "description": "私密模式，AI 先安抚情绪，再结合画像给出建议",
    },
    "partner_translate": {
        "name": "对方翻译",
        "privacy_level": "private",
        "description": "输入伴侣的一句话，AI 帮你理解背后含义",
    },
    "expression_rewrite": {
        "name": "表达改写",
        "privacy_level": "private",
        "description": "输入你想说的话，AI 帮你改写成更柔和的版本",
    },
    "cold_war": {
        "name": "冷战调解",
        "privacy_level": "couple",
        "description": "冷战状态下的沟通指导",
    },
    "mediation": {
        "name": "矛盾调解",
        "privacy_level": "couple",
        "description": "中立的矛盾调解和沟通建议",
    },
    "letter_understand": {
        "name": "信件解读",
        "privacy_level": "private",
        "description": "深入理解一段文字的含义",
    },
    "letter_rewrite": {
        "name": "信件改写",
        "privacy_level": "private",
        "description": "根据风格要求改写信件",
    },
}


def get_scene_config(scene_key: str) -> dict:
    return SCENE_CONFIGS.get(scene_key, {
        "name": "未知场景",
        "privacy_level": "private",
        "description": "未知场景",
    })


def get_all_scene_keys() -> List[str]:
    return list(SCENE_CONFIGS.keys())


def validate_scene_key(scene_key: str) -> bool:
    return scene_key in SCENE_CONFIGS
