package com.couple.translator.feature.couple.mediation

import com.couple.translator.feature.couple.data.model.MediationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 安全终止提示的取文函数（整改 B4.3 P0-2）。
 *
 * 为什么值得单独测：`safetyTextOf` 的输入是一场调解的**全部消息**，而一场被安全
 * 终止的会话里通常已经有别的 assistant 消息（改写稿）。只按「最后一条 assistant」
 * 取，一旦顺序不如预期就会把**改写稿当成安全提示**渲染给用户——那段文字是
 * 「替你把话说软」的产物，把它标成安全资源，用户会以为自己收到了保护性提示，
 * 而实际上那是他刚刚要求 AI 帮他改写的那句话。
 */
class MediationSafetyTextTest {

    private fun assistant(
        content: String = "",
        rewriteA: String? = null,
        rewriteB: String? = null,
        commonPoints: List<String> = emptyList(),
        nextActions: List<String> = emptyList(),
        safetyResponse: String? = null,
    ) = MediationDto.MediationMessageItem(
        role = "assistant",
        content = content,
        structuredOutput = MediationDto.MediationStructuredOutput(
            rewriteA = rewriteA,
            rewriteB = rewriteB,
            commonPoints = commonPoints,
            nextActions = nextActions,
            safetyResponse = safetyResponse,
        ),
    )

    private fun user(content: String = "我写的") = MediationDto.MediationMessageItem(
        role = "user",
        content = content,
    )

    @Test
    fun `优先取结构化契约字段`() {
        val messages = listOf(
            user(),
            assistant(rewriteA = "改写稿", rewriteB = "改写稿 B"),
            assistant(content = "安全提示正文", safetyResponse = "安全资源文案"),
        )
        assertEquals("安全资源文案", safetyTextOf(messages))
    }

    @Test
    fun `契约字段缺失时不得把改写稿当成安全提示`() {
        // 这是本条测试存在的**唯一理由**：改写稿是调解产物，不是安全资源。
        // 把它渲染成「安全提示」等于给用户看一份伪装成保护动作的软化稿。
        val messages = listOf(
            user(),
            assistant(content = "改写后的说法", rewriteA = "改写后的说法", rewriteB = "B 侧"),
        )
        assertNull(safetyTextOf(messages))
    }

    @Test
    fun `契约字段缺失时不得把总结稿当成安全提示`() {
        val messages = listOf(
            assistant(
                content = "你们的共同点是……",
                commonPoints = listOf("都想把话说开"),
                nextActions = listOf("本周聊一次"),
            ),
        )
        assertNull(safetyTextOf(messages))
    }

    @Test
    fun `没有安全提示时返回 null 而不是本地兜底文案`() {
        // 界面据此只渲染标题与固定引导语——**绝不**编一段假的「服务端提示」。
        assertNull(safetyTextOf(emptyList()))
        assertNull(safetyTextOf(listOf(user())))
        assertNull(safetyTextOf(listOf(assistant(content = "   "))))
    }

    @Test
    fun `空白的安全资源字段不算数`() {
        val messages = listOf(assistant(content = "", safetyResponse = "   "))
        assertNull(safetyTextOf(messages))
    }

    @Test
    fun `只有 content 的纯安全消息可以降级取正文`() {
        // 后端把安全文案同时放在 structured_output.safety_response 与 content。
        // 万一只有 content 落地（例如旧数据），它仍然不是调解产物，
        // 可以安全地当作提示正文。
        val messages = listOf(
            user(),
            assistant(content = "你们的对话触发了安全提示，请先照顾好自己。"),
        )
        assertEquals("你们的对话触发了安全提示，请先照顾好自己。", safetyTextOf(messages))
    }
}
