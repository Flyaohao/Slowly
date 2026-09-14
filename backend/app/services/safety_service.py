import logging
from typing import Optional, Dict

logger = logging.getLogger(__name__)

#: 弱信号词的触发阈值：单独出现不算，需共现多个才判定为高风险。
#: 设计理由：像「你总是」「每次都」这类泛化表达在正常抱怨中极其常见
#: （"你总是忘记我们的纪念日，我有点难过"），若按强信号处理会造成大量误报，
#: 用户会把安全提示当成干扰。共现多个才说明冲突确实在升级。
WEAK_SIGNAL_THRESHOLD = 2

#: 每个风险等级分「强信号」与「弱信号」两组。
#:   强信号：单独出现即判定，是明确的高危特征
#:   弱信号：口语中常见的泛化表达，需与同等级其它弱信号共现才判定
SAFETY_KEYWORDS = {
    "heated_conflict": {
        "strong": [
            "吵架", "大吵", "吼", "骂", "摔东西", "气死",
            "滚", "闭嘴", "废物",
            "离婚", "分手", "不过了", "受够了",
        ],
        "weak": ["你总是", "你从来", "每次都", "受不了了"],
    },
    "manipulation_risk": {
        "strong": [
            "威胁", "如果不…就", "你敢", "你试试", "报复",
            "让你后悔", "控制", "不许", "不准", "监控你",
            "查手机", "跟踪", "逼迫", "要挟", "自杀威胁",
        ],
        "weak": [],
    },
    "abuse_risk": {
        "strong": [
            "打你", "家暴", "暴力", "动手", "掐", "推搡",
            "囚禁", "锁门", "不许出门", "没收手机",
            "恐吓", "人身威胁", "伤害你", "打死",
        ],
        "weak": [],
    },
    "self_harm_risk": {
        "strong": [
            "不想活", "自杀", "割腕", "跳楼", "吃药",
            "活不下去", "死了算了", "结束生命", "轻生",
            "自残", "伤害自己", "没有意义", "不如死",
        ],
        "weak": [],
    },
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
    """基于强弱信号分级的风险判定，返回命中的最高风险等级。"""
    text_lower = (text or "").lower()
    highest_risk = "normal"
    highest_idx = 0

    for level, groups in SAFETY_KEYWORDS.items():
        idx = RISK_LEVEL_ORDER.index(level)

        strong_hit = any(kw in text_lower for kw in groups.get("strong", []))
        weak_hits = sum(1 for kw in groups.get("weak", []) if kw in text_lower)

        if (strong_hit or weak_hits >= WEAK_SIGNAL_THRESHOLD) and idx > highest_idx:
            highest_risk = level
            highest_idx = idx

    return highest_risk


def merge_risk_levels(level_a: str, level_b: str) -> str:
    idx_a = RISK_LEVEL_ORDER.index(level_a) if level_a in RISK_LEVEL_ORDER else 0
    idx_b = RISK_LEVEL_ORDER.index(level_b) if level_b in RISK_LEVEL_ORDER else 0
    return RISK_LEVEL_ORDER[max(idx_a, idx_b)]
