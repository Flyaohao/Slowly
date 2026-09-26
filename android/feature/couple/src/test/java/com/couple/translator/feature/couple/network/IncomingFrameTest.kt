package com.couple.translator.feature.couple.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS 入站帧解析回归。
 *
 * 之所以值得钉：`mediation_status` 状态帧**曾经被静默丢弃**——`when` 里没有
 * 这一支，服务端推了、客户端不响应，谁也不会看到报错（§8.5-1「发起方等待页
 * 必须能获知对方接受」因此落不了地）。这类静默失败只能靠断言挡住。
 */
class IncomingFrameTest {

    // ------------------------------------------------------------------ #
    // 心跳
    // ------------------------------------------------------------------ #

    @Test
    fun `ping 帧识别为心跳`() {
        assertEquals(IncomingFrame.Ping, parseIncomingFrame("""{"type":"ping"}"""))
    }

    // ------------------------------------------------------------------ #
    // 通知帧
    // ------------------------------------------------------------------ #

    @Test
    fun `通知帧带出跳转必需的身份字段`() {
        val frame = parseIncomingFrame(
            """{"type":"notification","notification_type":"letter_received","data":{"letter_id":7}}"""
        )
        assertTrue(frame is IncomingFrame.Notification)
        val event = (frame as IncomingFrame.Notification).event
        assertEquals("letter_received", event.notificationType)
        assertEquals(7L, event.letterId)
        // 未给的 id 必须保持 null，不能变成 0——0 会被当成「有个 id 为 0 的信」
        assertEquals(null, event.sessionId)
        assertEquals(null, event.eventId)
    }

    @Test
    fun `通知帧缺类型时丢弃`() {
        assertEquals(
            IncomingFrame.Ignored,
            parseIncomingFrame("""{"type":"notification","data":{}}"""),
        )
    }

    // ------------------------------------------------------------------ #
    // 调解状态帧（本次修复的核心）
    // ------------------------------------------------------------------ #

    @Test
    fun `调解状态帧被接出来而不是丢弃`() {
        val frame = parseIncomingFrame(
            """{"type":"mediation_status","session_id":12,"status":"confirming"}"""
        )
        assertEquals(IncomingFrame.MediationStatus(12L, "confirming"), frame)
    }

    @Test
    fun `调解状态帧缺 session_id 或 status 时丢弃`() {
        // 宁可不用这一帧（轮询仍是兜底），也不要用一个 sessionId=0 的帧
        // 去推进当前页面——那会把别的会话的状态灌进来。
        assertEquals(
            IncomingFrame.Ignored,
            parseIncomingFrame("""{"type":"mediation_status","status":"confirming"}"""),
        )
        assertEquals(
            IncomingFrame.Ignored,
            parseIncomingFrame("""{"type":"mediation_status","session_id":12}"""),
        )
        assertEquals(
            IncomingFrame.Ignored,
            parseIncomingFrame("""{"type":"mediation_status","session_id":0,"status":"x"}"""),
        )
    }

    // ------------------------------------------------------------------ #
    // 非法输入
    // ------------------------------------------------------------------ #

    @Test
    fun `非 JSON 与未知类型都安全丢弃`() {
        assertEquals(IncomingFrame.Ignored, parseIncomingFrame("not json at all"))
        assertEquals(IncomingFrame.Ignored, parseIncomingFrame(""))
        assertEquals(IncomingFrame.Ignored, parseIncomingFrame("""{"type":"something_new"}"""))
    }
}
