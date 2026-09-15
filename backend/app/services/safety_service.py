import json
import logging
import os
from pathlib import Path
from typing import Optional, Dict, List, Tuple

from app.services import safety_words as _builtin_words

logger = logging.getLogger(__name__)

#: 弱信号词的触发阈值：单独出现不算，需共现多个才判定为高风险。
#: 设计理由：像「你总是」「每次都」这类泛化表达在正常抱怨中极其常见
#: （"你总是忘记我们的纪念日，我有点难过"），若按强信号处理会造成大量误报，
#: 用户会把安全提示当成干扰。共现多个才说明冲突确实在升级。
WEAK_SIGNAL_THRESHOLD = 2

SAFETY_RESPONSES = {
    "heated_conflict": "感觉你们现在情绪都比较高。建议先深呼吸几次，等双方都冷静一些再继续沟通。你们的感受都是真实的，但情绪激动时说出来的话容易让对方更受伤。",
    "manipulation_risk": "我注意到这个表达可能带有控制或威胁的意味。健康的关系建立在尊重和理解之上。我没办法帮你生成这类内容，但我可以帮你想一个更真诚、更能被对方听见的表达方式。",
    "abuse_risk": "你的安全是最重要的。如果你正在经历或担心暴力、胁迫、控制，请联系：\n- 全国妇女维权热线：12338\n- 报警：110\nAI 无法替代现实中的专业帮助。",
    "self_harm_risk": "我很担心你现在的状态。请记住你不是一个人：\n- 全国 24 小时心理危机热线：400-161-9995\n- 北京心理危机研究与干预中心：010-82951332\n请立即联系专业人士，他们能给你最需要的支持。",
}

RISK_LEVEL_ORDER = ["normal", "heated_conflict", "manipulation_risk", "abuse_risk", "self_harm_risk"]


def _load_keywords() -> Dict:
    """加载安全词库：优先 backend/data/safety_words.json 覆盖，缺失/非法则用内置扩充词库。

    覆盖文件的意义：线上发现误报/漏报时，可以不改代码、只投一个 JSON 文件
    到数据卷上完成词库热更新（重启进程生效）。文件必须整体合法才生效，
    防止半截文件把词库打空。
    """
    override = Path(os.getenv("SAFETY_WORDS_FILE", "")) if os.getenv("SAFETY_WORDS_FILE") else (
        Path(__file__).resolve().parent.parent.parent / "data" / "safety_words.json"
    )
    try:
        if override.is_file():
            data = json.loads(override.read_text(encoding="utf-8"))
            if isinstance(data, dict) and all(
                isinstance(v, dict) and "strong" in v and "weak" in v for v in data.values()
            ):
                logger.info("Safety keywords loaded from override file: %s", override)
                return data
            logger.warning("Safety words override file invalid, falling back to builtin: %s", override)
    except Exception as exc:  # noqa: BLE001 —— 词库文件问题绝不拦启动
        logger.warning("Failed to load safety words override %s: %s", override, exc)
    return _builtin_words.WORDS


SAFETY_KEYWORDS = _load_keywords()


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


def check_input_safety_detail(text: str) -> Tuple[str, List[str]]:
    """同 check_input_safety，额外返回命中词列表（供审计落库用）。"""
    risk, hits = _keyword_check_detail(text)
    if risk != "normal":
        logger.warning("Input safety risk detected: level=%s hits=%s", risk, hits)
    return risk, hits


def check_output_safety_detail(text: str) -> Tuple[str, List[str]]:
    risk, hits = _keyword_check_detail(text)
    if risk != "normal":
        logger.warning("Output safety risk detected: level=%s hits=%s", risk, hits)
    return risk, hits


def get_safety_response(risk_level: str) -> Optional[str]:
    return SAFETY_RESPONSES.get(risk_level)


def _keyword_check(text: str) -> str:
    return _keyword_check_detail(text)[0]


def _keyword_check_detail(text: str) -> Tuple[str, List[str]]:
    """基于强弱信号分级的风险判定，返回（命中的最高风险等级, 命中词列表）。

    命中词只收集**最高风险等级**的命中项——低等级的命中词对审计意义不大，
    反而会把记录撑得很长。
    """
    text_lower = (text or "").lower()
    highest_risk = "normal"
    highest_idx = 0
    highest_hits: List[str] = []

    for level, groups in SAFETY_KEYWORDS.items():
        idx = RISK_LEVEL_ORDER.index(level)

        hits = [kw for kw in groups.get("strong", []) if kw in text_lower]
        weak_hits = [kw for kw in groups.get("weak", []) if kw in text_lower]
        strong_hit = bool(hits)
        if not strong_hit and len(weak_hits) >= WEAK_SIGNAL_THRESHOLD:
            hits = weak_hits

        if (strong_hit or len(weak_hits) >= WEAK_SIGNAL_THRESHOLD) and idx > highest_idx:
            highest_risk = level
            highest_idx = idx
            highest_hits = hits

    return highest_risk, highest_hits


def merge_risk_levels(level_a: str, level_b: str) -> str:
    idx_a = RISK_LEVEL_ORDER.index(level_a) if level_a in RISK_LEVEL_ORDER else 0
    idx_b = RISK_LEVEL_ORDER.index(level_b) if level_b in RISK_LEVEL_ORDER else 0
    return RISK_LEVEL_ORDER[max(idx_a, idx_b)]
