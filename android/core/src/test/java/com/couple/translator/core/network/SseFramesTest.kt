package com.couple.translator.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 行解析的协议细节测试。
 *
 * 这些用例盯的都是**真踩过的坑**，不是补覆盖率：
 * - 服务端每 2 秒发一帧 `: keep-alive`，它如果被当成事件派发，上层会满屏空提示；
 * - 心跳如果顺手把缓冲清空了，那一帧的正文就丢了；
 * - 缺 event 或缺 data 的残帧派发出去，上层拿到空事件会白刷一次 UI。
 */
class SseFramesTest {

    private fun parse(raw: String): List<SseFrame> {
        val parser = SseLineParser()
        val frames = mutableListOf<SseFrame>()
        for (line in raw.split("\n")) {
            parser.feed(line)?.let { frames += it }
        }
        return frames
    }

    @Test
    fun `一帧最基本的 event 加 data 加空行`() {
        val frames = parse("event: delta\ndata: {\"content\":\"你好\"}\n")
        assertEquals(1, frames.size)
        assertEquals("delta", frames[0].event)
        assertEquals("{\"content\":\"你好\"}", frames[0].data)
    }

    @Test
    fun `心跳注释被丢弃且不产生空帧`() {
        val frames = parse(": keep-alive\n: keep-alive\n\n: keep-alive\n")
        assertTrue("心跳不该派发成事件", frames.isEmpty())
    }

    @Test
    fun `心跳夹在 data 与空行之间不会清空缓冲`() {
        // 服务端的心跳是随时可能插进来的，插在帧中间时不能把这一帧吃掉
        val frames = parse("event: delta\ndata: {\"a\":1}\n: keep-alive\n\n")
        assertEquals(1, frames.size)
        assertEquals("{\"a\":1}", frames[0].data)
    }

    @Test
    fun `缺 data 的帧不派发`() {
        assertTrue(parse("event: delta\n\n").isEmpty())
    }

    @Test
    fun `缺 event 的帧不派发`() {
        assertTrue(parse("data: {\"a\":1}\n\n").isEmpty())
    }

    @Test
    fun `多行 data 直接拼接`() {
        val frames = parse("event: done\ndata: {\"a\":\ndata: 1}\n\n")
        assertEquals("{\"a\":1}", frames[0].data)
    }

    @Test
    fun `连续多帧按顺序吐出`() {
        val frames = parse(
            "event: meta\ndata: {\"generation_id\":1}\n\n" +
                "event: thinking\ndata: {\"content\":\"想\"}\n\n" +
                "event: delta\ndata: {\"content\":\"好\"}\n\n"
        )
        assertEquals(listOf("meta", "thinking", "delta"), frames.map { it.event })
    }

    @Test
    fun `帧结束后不残留上一个事件名`() {
        // 漏了状态清空的话，下面那条没有 event 的 data 会被算到 meta 头上
        val frames = parse("event: meta\ndata: {\"a\":1}\n\ndata: {\"b\":2}\n\n")
        assertEquals(1, frames.size)
        assertEquals("meta", frames[0].event)
    }

    @Test
    fun `event 与 data 两侧空白被裁掉`() {
        val parser = SseLineParser()
        assertNull(parser.feed("event:  delta  "))
        assertNull(parser.feed("data:   {\"c\":1}   "))
        val frame = parser.feed("")
        assertEquals("delta", frame?.event)
        assertEquals("{\"c\":1}", frame?.data)
    }

    @Test
    fun `空流不产出任何帧`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("\n\n\n").isEmpty())
    }
}
