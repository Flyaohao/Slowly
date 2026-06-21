from typing import Optional, Dict


def detect_conflict_pattern(dim_a: Dict[str, float], dim_b: Dict[str, float]) -> str:
    a_anxiety = dim_a.get("attachment_anxiety", 50)
    a_avoidance = dim_a.get("attachment_avoidance", 50)
    b_anxiety = dim_b.get("attachment_anxiety", 50)
    b_avoidance = dim_b.get("attachment_avoidance", 50)
    a_defensive = dim_a.get("defensive_response", 50)
    b_defensive = dim_b.get("defensive_response", 50)
    a_emotional = dim_a.get("emotional_validation_need", 50)
    b_emotional = dim_b.get("emotional_validation_need", 50)
    a_direct = dim_a.get("directness_preference", 50)
    b_direct = dim_b.get("directness_preference", 50)

    a_high_anxiety = a_anxiety >= 50
    b_high_anxiety = b_anxiety >= 50
    a_high_avoidance = a_avoidance >= 50
    b_high_avoidance = b_avoidance >= 50
    a_high_defensive = a_defensive >= 50
    b_high_defensive = b_defensive >= 50
    a_high_emotional = a_emotional >= 50
    b_high_emotional = b_emotional >= 50
    a_high_direct = a_direct >= 50
    b_high_direct = b_direct >= 50

    if a_high_anxiety and b_high_avoidance:
        return "pursue_withdraw"
    if b_high_anxiety and a_high_avoidance:
        return "pursue_withdraw"
    if a_high_anxiety and b_high_anxiety:
        return "emotional_escalation"
    if a_high_avoidance and b_high_avoidance:
        return "mutual_withdrawal"
    if (a_high_defensive and b_high_emotional) or (b_high_defensive and a_high_emotional):
        return "explanation_misunderstanding"
    if (a_high_direct and not b_high_direct) or (b_high_direct and not a_high_direct):
        return "suppress_explode"

    return "no_conflict"


CONFLICT_PATTERN_INFO = {
    "pursue_withdraw": {
        "name": "追问-退缩循环",
        "description": "一方在冲突中倾向于追问和寻求回应，另一方倾向于退缩和回避。这种模式容易导致双方都感到不被理解。",
    },
    "emotional_escalation": {
        "name": "情绪升级循环",
        "description": "双方在冲突中都容易情绪激动，导致争论不断升级，难以冷静下来。",
    },
    "mutual_withdrawal": {
        "name": "双向冷处理循环",
        "description": "双方在冲突中都倾向于回避和冷处理，问题可能被搁置而非解决。",
    },
    "explanation_misunderstanding": {
        "name": "解释-不被理解循环",
        "description": "一方试图解释自己的行为，另一方感到情绪未被确认，导致解释反而加剧矛盾。",
    },
    "suppress_explode": {
        "name": "压抑-爆发循环",
        "description": "一方表达方式直接，另一方倾向于压抑自己的需求，积累到一定程度后爆发。",
    },
    "no_conflict": {
        "name": "无明显冲突模式",
        "description": "双方在冲突处理方面较为平衡，未发现明显的冲突循环模式。",
    },
}
