package com.couple.translator.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wire 风险等级 → 枚举映射（P-A §2.2 + 整改 B4.3 P0-4）。
 *
 * ## 这一版与旧版的裁决**相反**，改的是安全性而不是风格
 *
 * 旧实现里 `normal` / `null` / 空串 / 任意自造值**一律返回 null**，调用方只能
 * 看到「没有卡片要渲染」。于是「解析失败」和「后端说了 normal」在客户端是
 * 同一个信号——**解析失败 = 没有风险 = 放行**。这是 fail open：后端某天新增
 * 一个更危险的档位、或响应字段被改名，客户端会把高风险当正常放行，还一声不响。
 *
 * 现在三态分明：
 * - `null`  **只**表示「后端明确说了 normal」——唯一可放行的信号；
 * - `UNKNOWN` 表示「没能解析出合法等级」（含 null / 空串 / 自造值）；
 * - 其余返回具体档位。
 *
 * 下面 `旧实现会在这个矩阵上失败` 那一条测试是**测试有效性自证**：
 * 它逐条列出旧实现的返回值，若有人把 `fromWire` 改回 fail open，
 * 这一条会立刻变红。
 */
class AiRiskLevelTest {

    @Test
    fun `四个合法值各自映射正确`() {
        assertEquals(AiRiskLevel.HEATED_CONFLICT, AiRiskLevel.fromWire("heated_conflict"))
        assertEquals(AiRiskLevel.MANIPULATION_RISK, AiRiskLevel.fromWire("manipulation_risk"))
        assertEquals(AiRiskLevel.ABUSE_RISK, AiRiskLevel.fromWire("abuse_risk"))
        assertEquals(AiRiskLevel.SELF_HARM_RISK, AiRiskLevel.fromWire("self_harm_risk"))
    }

    @Test
    fun `只有 normal 返回 null`() {
        assertNull(AiRiskLevel.fromWire("normal"))
        assertNull(AiRiskLevel.fromWire("NORMAL"))
        assertNull(AiRiskLevel.fromWire("  Normal  "))
    }

    /**
     * fail closed 矩阵：**一切无法识别的值都是 UNKNOWN，不是 null**。
     *
     * 这一条覆盖任务书点名的全部输入形态：null、空串、纯空格、大小写、
     * 前后空格、完全未知值、模型自造值（英文 / 中文 / 数字）。
     */
    @Test
    fun `无法识别的值一律 UNKNOWN 而不是 null`() {
        val unknownInputs = listOf(
            "null 字段缺失" to null,
            "空串" to "",
            "纯空格" to "   ",
            "大写的高危档位" to "HIGH",
            "旧档位名" to "high_risk",
            "模型自造英文值" to "very_high",
            "模型自造中文值" to "高风险",
            "数字" to "3",
            "布尔串" to "true",
            "带前后空格的未知值" to "  bogus  ",
            "全角空格包围的未知值" to "　severe　",
        )
        unknownInputs.forEach { (label, raw) ->
            assertEquals(
                "「$label」必须解析为 UNKNOWN（fail closed），而不是放行",
                AiRiskLevel.UNKNOWN,
                AiRiskLevel.fromWire(raw),
            )
        }
    }

    @Test
    fun `大小写与前后空格容忍命中合法值`() {
        assertEquals(AiRiskLevel.HEATED_CONFLICT, AiRiskLevel.fromWire(" HEATED_CONFLICT "))
        assertEquals(AiRiskLevel.HEATED_CONFLICT, AiRiskLevel.fromWire("Heated_Conflict"))
        assertEquals(AiRiskLevel.ABUSE_RISK, AiRiskLevel.fromWire("  abuse_risk  "))
        assertEquals(AiRiskLevel.SELF_HARM_RISK, AiRiskLevel.fromWire("Self_Harm_Risk"))
    }

    /**
     * 后端 P0-4 起会**显式下发** `unknown`（它把 null / 空串 / 自造值都收敛成它）。
     *
     * 它与「客户端自己解析失败」落到同一个枚举值，处置相同；但作为 wire 契约
     * 它必须被认出来，否则 `WIRE_VALUES` 与后端 `RiskLevel` 就对不上了。
     */
    @Test
    fun `后端显式下发的 unknown 被认出来`() {
        assertEquals(AiRiskLevel.UNKNOWN, AiRiskLevel.fromWire("unknown"))
        assertEquals(AiRiskLevel.UNKNOWN, AiRiskLevel.fromWire("  UNKNOWN  "))
    }

    /**
     * `WIRE_VALUES` 是**给后端契约测试读的那份声明**
     * （`backend/tests/test_action_routing_contract.py` 直接解析这个常量并与
     * `ai_output.RiskLevel` 比对）。所以它自己不许漂移，且必须与 `fromWire`
     * 的认值集合一致。
     */
    @Test
    fun `wire 取值声明与解析行为一致`() {
        assertEquals(
            listOf(
                "normal", "heated_conflict", "manipulation_risk",
                "abuse_risk", "self_harm_risk", "unknown",
            ),
            AiRiskLevel.WIRE_VALUES,
        )
        // 除 normal（唯一可放行）外，每个声明值都必须解析成一个具体档位
        AiRiskLevel.WIRE_VALUES.forEach { wire ->
            if (wire == "normal") {
                assertNull(AiRiskLevel.fromWire(wire))
            } else {
                assertTrue(
                    "声明了「$wire」却解析不出来",
                    AiRiskLevel.fromWire(wire) != null,
                )
            }
        }
    }

    /**
     * `isKnown` 的语义：**只有后端明确说了 normal 才算已知**。
     *
     * 「字段缺失」不是「没有风险」——这正是 fail open 的病根。
     */
    @Test
    fun `isKnown 只认 normal`() {
        assertTrue(AiRiskLevel.isKnown("normal"))
        assertTrue(AiRiskLevel.isKnown(" NORMAL "))
        assertFalse("字段缺失不是「已知安全」", AiRiskLevel.isKnown(null))
        assertFalse(AiRiskLevel.isKnown(""))
        assertFalse(AiRiskLevel.isKnown("   "))
        assertFalse(AiRiskLevel.isKnown("heated_conflict"))
        assertFalse(AiRiskLevel.isKnown("very_high"))
    }

    /**
     * **测试有效性自证**：把旧实现的返回值逐条钉在这里。
     *
     * 为什么值得单独写一条：这一版 `fromWire` 的改动方向是「以前放行的现在阻断」，
     * 而**放行**的回归在测试里是隐形的——没有断言的话，改回 fail open 时
     * 上面那些 UNKNOWN 断言才会报错，而调用方（`mediationBlockedByRisk`）
     * 的测试也可能被人一并「顺手改绿」。这一条把旧行为写成明确的**不应成立**
     * 的事实，任何人想回到 fail open 都必须先删掉它——那是一个显式动作，
     * 不是一次静默的编辑。
     */
    @Test
    fun `旧实现的 fail open 行为不再成立`() {
        val failOpenInputs = listOf<String?>(null, "", "foo", "very_high", "高风险")
        failOpenInputs.forEach { raw ->
            assertFalse(
                "旧实现把「$raw」当成没有风险（返回 null）——这个行为必须保持不成立",
                AiRiskLevel.fromWire(raw) == null,
            )
        }
    }
}
