import logging
from typing import Optional, Dict

logger = logging.getLogger(__name__)

SAFETY_KEYWORDS = {
    "heated_conflict": [
        "吵架", "大吵", "吼", "骂", "摔东西", "气死", "受不了了",
        "滚", "闭嘴", "你总是", "你从来", "每次都", "废物",
        "离婚", "分手", "不过了", "受够了",
    ],
    "manipulation_risk": [
        "威胁", "如果不…就", "你敢", "你试试", "报复",
        "让你后悔", "控制", "不许", "不准", "监控你",
        "查手机", "跟踪", "逼迫", "要挟", "自杀威胁",
    ],
    "abuse_risk": [
        "打你", "家暴", "暴力", "动手", "掐", "推搡",
        "囚禁", "锁门", "不许出门", "没收手机",
        "恐吓", "人身威胁", "伤害你", "打死",
    ],
    "self_harm_risk": [
        "不想活", "自杀", "割腕", "跳楼", "吃药",
        "活不下去", "死了算了", "结束生命", "轻生",
        "自残", "伤害自己", "没有意义", "不如死",
    ],
}

SAFETY_RESPONSES = {
    "heated_conflict": "感觉你们现在情绪都比较高。建议先深呼吸几次，等双方都冷静一些再继续沟通。你们的感受都是真实的，但情绪激动时说出来的话容易让对方更受伤。",
    "manipulation_risk": "我注意到这个表达可能带有控制或威胁的意味。健康的关系建立在尊重和理解之上。我没办法帮你生成这类内容，但我可以帮你想一个更真诚、更能被对方听见的表达方式。",
    "abuse_risk": "你的安全是最重要的。如果你正在经历或担心暴力、胁迫、控制，请联系：\n- 全国妇女维权热线：12338\n- 报警：110\nAI 无法替代现实中的专业帮助。",
    "self_harm_risk": "我很担心你现在的状态。请记住你不是一个人：\n- 全国 24 小时心理危机热线：400-161-9995\n- 北京心理危机研究与干预中心：010-82951332\n请立即联系专业人士，他们能给你最需要的支持。",
}

RISK_LEVEL_ORDER = ["normal", "heated_conflict", "manipulation_risk", "abuse_risk", "self_harm_risk"]


def check_input_safety(text: str) -> str:
    risk = _keyword_check(text)
    if risk != "normal":
        logger.warning("Input safety risk detected: level=%s", risk)
    return risk


def check_output_safety(text: str) -> str:
    risk = _keyword_check(text)
    if risk != "normal":
        logger.warning("Output safety risk detected: level=%s", risk)
    return risk


def get_safety_response(risk_level: str) -> Optional[str]:
    return SAFETY_RESPONSES.get(risk_level)


def _keyword_check(text: str) -> str:
    text_lower = text.lower()
    highest_risk = "normal"
    highest_idx = 0
    for level, keywords in SAFETY_KEYWORDS.items():
        idx = RISK_LEVEL_ORDER.index(level)
        for kw in keywords:
            if kw in text_lower:
                if idx > highest_idx:
                    highest_risk = level
                    highest_idx = idx
    return highest_risk


def merge_risk_levels(level_a: str, level_b: str) -> str:
    idx_a = RISK_LEVEL_ORDER.index(level_a) if level_a in RISK_LEVEL_ORDER else 0
    idx_b = RISK_LEVEL_ORDER.index(level_b) if level_b in RISK_LEVEL_ORDER else 0
    return RISK_LEVEL_ORDER[max(idx_a, idx_b)]
