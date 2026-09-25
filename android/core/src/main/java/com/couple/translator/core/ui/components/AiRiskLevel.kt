package com.couple.translator.core.ui.components

/**
 * 后端风险等级的客户端枚举（P-A §2.2）。
 *
 * 后端 5 档（`app/schemas/ai_output.py`）：`normal / heated_conflict /
 * manipulation_risk / abuse_risk / self_harm_risk`。
 * `normal` 与 null / 未知值一律映射为 null——调用方**不渲染任何卡片**。
 */
enum class AiRiskLevel {
    HEATED_CONFLICT,
    MANIPULATION_RISK,
    ABUSE_RISK,
    SELF_HARM_RISK;

    companion object {
        /** wire 值 → 枚举；normal / null / 未知值一律返回 null（不渲染） */
        fun fromWire(raw: String?): AiRiskLevel? = when (raw?.trim()?.lowercase()) {
            "heated_conflict" -> HEATED_CONFLICT
            "manipulation_risk" -> MANIPULATION_RISK
            "abuse_risk" -> ABUSE_RISK
            "self_harm_risk" -> SELF_HARM_RISK
            else -> null
        }
    }
}
