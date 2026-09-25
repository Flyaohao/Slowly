package com.couple.translator.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * wire 风险等级 → 枚举映射（P-A §2.2）。
 *
 * normal / null / 未知值一律 null —— 调用方不渲染卡片，这是「正常回答不弹警示」的保证。
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
    fun `normal 与空值与未知值一律 null`() {
        assertNull(AiRiskLevel.fromWire(null))
        assertNull(AiRiskLevel.fromWire(""))
        assertNull(AiRiskLevel.fromWire("normal"))
        assertNull(AiRiskLevel.fromWire("NORMAL"))
        assertNull(AiRiskLevel.fromWire("foo"))
    }

    @Test
    fun `大小写与空白容忍命中合法值`() {
        assertEquals(AiRiskLevel.HEATED_CONFLICT, AiRiskLevel.fromWire(" HEATED_CONFLICT "))
    }
}
