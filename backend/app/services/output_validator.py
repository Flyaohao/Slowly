from typing import Optional, List, Dict, Any


OUTPUT_SCHEMA = {
    "type": "object",
    "required": ["summary", "risk_level"],
    "properties": {
        "summary": {"type": "string"},
        "emotion_validation": {"type": "string"},
        "partner_possible_meaning": {"type": "string"},
        "suggested_reply": {"type": "string"},
        "do_not_say": {"type": "string"},
        "next_step": {"type": "string"},
        "risk_level": {
            "type": "string",
            "enum": [
                "normal",
                "heated_conflict",
                "manipulation_risk",
                "abuse_risk",
                "self_harm_risk",
            ],
        },
        "suggested_actions": {
            "type": "array",
            "items": {"type": "string"},
        },
        "rewrites": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "style": {"type": "string"},
                    "content": {"type": "string"},
                },
            },
        },
    },
}

COLD_WAR_SCHEMA = {
    "type": "object",
    "required": ["goal_analysis", "face_vs_need", "approach", "opening_lines", "avoid_reminders", "risk_level"],
    "properties": {
        "goal_analysis": {"type": "string"},
        "face_vs_need": {"type": "string"},
        "approach": {"type": "string", "enum": ["approach", "give_space"]},
        "approach_reason": {"type": "string"},
        "opening_lines": {
            "type": "array",
            "items": {"type": "string"},
        },
        "avoid_reminders": {
            "type": "array",
            "items": {"type": "string"},
        },
        "risk_level": {
            "type": "string",
            "enum": [
                "normal",
                "heated_conflict",
                "manipulation_risk",
                "abuse_risk",
                "self_harm_risk",
            ],
        },
    },
}

VALID_RISK_LEVELS = {
    "normal",
    "heated_conflict",
    "manipulation_risk",
    "abuse_risk",
    "self_harm_risk",
}

SCENE_SCHEMAS = {
    "cold_war": COLD_WAR_SCHEMA,
}


def validate_structured_output(raw_output: dict, scene_key: str = "default") -> dict:
    if scene_key == "cold_war":
        return _validate_cold_war(raw_output)

    result = {}

    result["summary"] = _safe_str(raw_output, "summary", "AI 回复")
    result["emotion_validation"] = _safe_str(raw_output, "emotion_validation")
    result["partner_possible_meaning"] = _safe_str(raw_output, "partner_possible_meaning")
    result["suggested_reply"] = _safe_str(raw_output, "suggested_reply")
    result["do_not_say"] = _safe_str(raw_output, "do_not_say")
    result["next_step"] = _safe_str(raw_output, "next_step")

    risk = raw_output.get("risk_level", "normal")
    if risk not in VALID_RISK_LEVELS:
        risk = "normal"
    result["risk_level"] = risk

    if "suggested_actions" in raw_output and isinstance(raw_output["suggested_actions"], list):
        result["suggested_actions"] = [
            str(a) for a in raw_output["suggested_actions"]
        ]

    if "rewrites" in raw_output and isinstance(raw_output["rewrites"], list):
        result["rewrites"] = [
            {
                "style": str(r.get("style", "")),
                "content": str(r.get("content", "")),
            }
            for r in raw_output["rewrites"]
            if isinstance(r, dict)
        ]

    return result


def _validate_cold_war(raw_output: dict) -> dict:
    result = {}

    result["goal_analysis"] = _safe_str(raw_output, "goal_analysis", "")
    result["face_vs_need"] = _safe_str(raw_output, "face_vs_need", "")
    result["approach"] = _safe_str(raw_output, "approach", "approach")
    if result["approach"] not in ("approach", "give_space"):
        result["approach"] = "approach"
    result["approach_reason"] = _safe_str(raw_output, "approach_reason", "")

    if "opening_lines" in raw_output and isinstance(raw_output["opening_lines"], list):
        result["opening_lines"] = [str(line) for line in raw_output["opening_lines"]]
    else:
        result["opening_lines"] = []

    if "avoid_reminders" in raw_output and isinstance(raw_output["avoid_reminders"], list):
        result["avoid_reminders"] = [str(r) for r in raw_output["avoid_reminders"]]
    else:
        result["avoid_reminders"] = []

    risk = raw_output.get("risk_level", "normal")
    if risk not in VALID_RISK_LEVELS:
        risk = "normal"
    result["risk_level"] = risk

    return result


def _safe_str(d: dict, key: str, default: Optional[str] = None) -> Optional[str]:
    val = d.get(key)
    if val is None:
        return default
    return str(val)
