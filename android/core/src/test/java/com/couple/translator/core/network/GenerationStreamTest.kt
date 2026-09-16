package com.couple.translator.core.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 流式生成的两处纯逻辑测试：
 * 1. 错误 → 中文提示 的映射（用户唯一能看到的失败信息，措辞必须对得上场景）；
 * 2. SSE 帧 → 业务事件 的解码（解错一帧，用户看到的就是空白答案或永远转圈）。
 */
class GenerationStreamTest {

    private val decoder = GenerationStreamDecoder(
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    )

    // ---------- httpHint：HTTP 状态码 → 文案 ----------

    @Test
    fun `HTTP 状态码映射到各自的中文提示`() {
        assertEquals("请求被拒绝，请检查输入或绑定状态", httpHint(400))
        assertEquals("登录已过期，请重新登录", httpHint(401))
        assertEquals("无权访问此内容", httpHint(403))
        assertEquals("接口不存在，请升级 App", httpHint(404))
        assertEquals("请求参数不合法", httpHint(422))
        assertEquals("服务异常（HTTP 500）", httpHint(500))
        assertEquals("服务异常（HTTP 502）", httpHint(502))
    }

    // ---------- netHint：异常类型 → 文案 ----------

    @Test
    fun `网络异常映射到各自的中文提示`() {
        assertEquals("等待响应超时，请重试", netHint(SocketTimeoutException()))
        // SocketTimeoutException 也是 IOException，顺序错了这里会退化成通用文案
        assertEquals("网络连接被中断（可能是切换了 Wi-Fi 或移动数据）", netHint(SocketException()))
        assertEquals("网络读写失败，请检查网络后重试", netHint(IOException("broken pipe")))
    }

    @Test
    fun `无 message 的异常也有兜底文案`() {
        assertEquals("连接已断开", netHint(RuntimeException()))
        assertEquals("自定义原因", netHint(IllegalStateException("自定义原因")))
    }

    // ---------- 解码：正常帧 ----------

    @Test
    fun `meta 帧解出 generationId`() {
        assertEquals(
            GenerationStreamEvent.Started(42L),
            decoder.decode(SseFrame("meta", "{\"generation_id\":42}")),
        )
    }

    @Test
    fun `thinking 与 delta 分别对应思考与正文`() {
        assertEquals(
            GenerationStreamEvent.Thinking("先想一下"),
            decoder.decode(SseFrame("thinking", "{\"content\":\"先想一下\"}")),
        )
        assertEquals(
            GenerationStreamEvent.Delta("你好"),
            decoder.decode(SseFrame("delta", "{\"content\":\"你好\"}")),
        )
    }

    @Test
    fun `notice 帧表示正文结束转为整理结构化结果`() {
        assertEquals(
            GenerationStreamEvent.Structuring,
            decoder.decode(SseFrame("notice", "{}")),
        )
    }

    @Test
    fun `done 帧带齐终稿结构化结果与风险等级`() {
        val event = decoder.decode(
            SseFrame(
                "done",
                "{\"generation_id\":7,\"status\":\"done\",\"interrupted\":false," +
                    "\"content\":\"终稿正文\",\"thinking\":\"推理过程\"," +
                    "\"structured_output\":{\"mood\":\"平静\"},\"risk_level\":\"high\"}"
            )
        )
        assertTrue(event is GenerationStreamEvent.Finished)
        event as GenerationStreamEvent.Finished
        assertEquals(7L, event.generationId)
        assertEquals("done", event.status)
        assertFalse(event.interrupted)
        assertEquals("终稿正文", event.content)
        assertEquals("推理过程", event.thinking)
        assertEquals("平静", event.structured?.get("mood"))
        assertEquals("high", event.riskLevel)
    }

    @Test
    fun `done 帧中断时 interrupted 为真`() {
        val event = decoder.decode(
            SseFrame("done", "{\"generation_id\":9,\"status\":\"interrupted\",\"interrupted\":true}")
        ) as GenerationStreamEvent.Finished
        assertTrue(event.interrupted)
        assertEquals("interrupted", event.status)
    }

    @Test
    fun `error 帧解出错误码与文案`() {
        assertEquals(
            GenerationStreamEvent.Failure(40101, "额度已用完"),
            decoder.decode(SseFrame("error", "{\"code\":40101,\"message\":\"额度已用完\"}")),
        )
    }

    // ---------- 解码：容错 ----------

    @Test
    fun `未知帧返回 null 而不是崩掉`() {
        // 服务端将来新增事件名时，老客户端必须安静忽略
        assertNull(decoder.decode(SseFrame("brand_new_event", "{}")))
        assertNull(decoder.decode(SseFrame("", "{}")))
    }

    @Test
    fun `坏报文返回 null 而不是抛异常`() {
        assertNull(decoder.decode(SseFrame("delta", "这不是 JSON")))
        assertNull(decoder.decode(SseFrame("done", "")))
        assertNull(decoder.decode(SseFrame("meta", "{")))
    }
}
