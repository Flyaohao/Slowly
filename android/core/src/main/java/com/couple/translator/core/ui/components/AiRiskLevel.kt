package com.couple.translator.core.ui.components

/**
 * 后端风险等级的客户端枚举（P-A §2.2 + 整改 B4.3 P0-4）。
 *
 * 后端 wire 档位：`normal / heated_conflict / manipulation_risk / abuse_risk /
 * self_harm_risk`，外加整改 B4.3 新增的 `unknown`——后端把**一切无法识别的
 * 值**（null / 空串 / 大小写混杂 / 模型自造值）都收敛成它。
 *
 * ## 为什么这里刻意**不**把「解析失败」和「normal」混为一谈
 *
 * 旧实现的 `fromWire` 对 `normal`、`null`、任意垃圾值一律返回 `null`，调用方
 * 只能看到「没有卡片要渲染」。于是**解析失败 = 没有风险 = 放行**，这是
 * fail open：后端某天新增一个更危险的档位、或响应字段被改名，客户端会把
 * 高风险当正常放行，还一声不响。
 *
 * 现在三态分明：
 *
 * - `fromWire(raw) == null` **只**表示「后端明确说了 normal」——唯一可放行的信号；
 * - `fromWire(raw) == UNKNOWN` 表示**没能解析出合法等级**（含 null/空/自造值），
 *   调用方必须按 fail closed 处理（见 `mediationBlockedByRisk`）；
 * - 其余返回具体档位。
 */
enum class AiRiskLevel {
    HEATED_CONFLICT,
    MANIPULATION_RISK,
    ABUSE_RISK,
    SELF_HARM_RISK,

    /** 后端明确下发的「无法识别」标记；不是危险度档位，只表示「不许当正常放行」 */
    UNKNOWN;

    companion object {
        /**
         * 后端 `RiskLevel` 枚举里**会**出现在 wire 上的全部字面量（含 `normal`）。
         *
         * 这不是给人看的文档，是被 `AiRiskLevelContractTest` 直接断言的契约：
         * 后端 `app/schemas/ai_output.py::RiskLevel` 一旦新增档位，两侧的
         * 取值集合就对不上，测试立刻失败——而不是等某个新档位在客户端被
         * 静默解析成 `UNKNOWN`（后果是安全的，但会悄悄把正常语境也拦掉）。
         */
        val WIRE_VALUES = listOf(
            "normal",
            "heated_conflict",
            "manipulation_risk",
            "abuse_risk",
            "self_harm_risk",
            "unknown",
        )

        /**
         * wire 值 → 枚举。**仅** `normal` 返回 `null`（= 无卡片、可放行）；
         * 无法识别的值返回 [UNKNOWN]（= 不渲染具体卡片，但**必须**阻断双人动作）。
         *
         * 除大小写/首尾空格外不做任何模糊匹配：`"HIGH"`、`"高风险"`、`"3"`
         * 一律 `UNKNOWN`——猜错方向的代价是不对称的。
         */
        fun fromWire(raw: String?): AiRiskLevel? = when (raw?.trim()?.lowercase()) {
            "normal" -> null
            "heated_conflict" -> HEATED_CONFLICT
            "manipulation_risk" -> MANIPULATION_RISK
            "abuse_risk" -> ABUSE_RISK
            "self_harm_risk" -> SELF_HARM_RISK
            // 后端 P0-4 起会把一切无法识别的值收敛成 `unknown` 再下发。它与
            // 「客户端自己解析失败」落到同一个枚举值，处置也相同：不渲染本地卡片、
            // 阻断双人动作。两条路合并是有意的——它们对用户的含义完全一样。
            "unknown" -> UNKNOWN
            else -> UNKNOWN
        }

        /**
         * 这个 wire 值是否**可被当作正常放行**。
         *
         * 只有后端明确说了 `normal` 才算。null / 空串 / 任何自造值都是 false——
         * 「字段缺失」不是「没有风险」。
         */
        fun isKnown(raw: String?): Boolean = raw?.trim()?.lowercase() == "normal"
    }
}
