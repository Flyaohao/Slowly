package com.couple.translator.feature.couple.ai

import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整改 §8.3 / §8.2 的**决策函数**测试。
 *
 * 为什么测这两个纯函数而不是测 UI：任务卡路由一旦映射错，用户点到的就是
 * 未注册路由（直接崩）或一张点不动的死卡——「composable 已注册但用户进不去」
 * 正是 §8.0 要求被测试挡住的回归类型。而行动行的按钮集合决定了调解是否被
 * 门控、复盘页是否会自己跳自己，都属于「错了不报错、只是体验坏掉」的静默失败。
 */
class AiActionRoutingTest {

    private fun card(type: String, id: Long = 7L) = HomeDto.TaskCard(
        type = type,
        id = id,
        title = "t",
        createdAt = null,
        route = null,
    )

    // ------------------------------------------------------------------ #
    // §8.3 任务卡 → 路由
    // ------------------------------------------------------------------ #

    @Test
    fun `feedback_outcome 任务卡可点击且落到待反馈页`() {
        val route = routeForTaskCard(card("feedback_outcome", id = 42L))
        // 「必须可点击」的前置条件：route 非 null。此前这里返回 null，
        // 首页那张卡渲染成不可点击的信息行，用户无处回答。
        assertTrue(route != null)
        assertTrue(route!!.startsWith(Screen.FeedbackOutcome.route))
        assertTrue("要带上会话 id", route.contains("sessionId=42"))
    }

    @Test
    fun `已冻结与未知类型的任务卡不产生路由`() {
        // 宁可渲染成信息行，也绝不 navigate 到未注册路由（会崩）
        assertNull(routeForTaskCard(card("unknown_type")))
        assertNull(routeForTaskCard(card("wishlist")))
        assertNull(routeForTaskCard(card("museum")))
    }

    @Test
    fun `保留类型的任务卡路由形状正确`() {
        assertEquals(
            "${Screen.MediationInvite.route}?sessionId=7&isInviter=false",
            routeForTaskCard(card("mediation_invite")),
        )
        assertEquals(
            "${Screen.DualPerspectiveDetail.route}/7",
            routeForTaskCard(card("dual_perspective")),
        )
        assertEquals(
            "${Screen.LetterDetail.route}/7",
            routeForTaskCard(card("pending_letter")),
        )
        assertEquals(
            Screen.QuestionnaireIntro.route,
            routeForTaskCard(card("questionnaire")),
        )
    }

    @Test
    fun `复盘回访卡落到复盘详情的路径段而不是查询参数`() {
        // §8.7：复盘首页与复盘详情是 NavHost 里两条**不同**的 route 字符串。
        // 用 `?reviewId=` 拼查询参数会命中复盘首页那条 route——页面照样打开、
        // id 却被丢掉，用户看到的是空白输入框而不是回访目标，这是最典型的
        // 「composable 已注册但用户进不去（要去的那条）」静默失败。
        val route = routeForTaskCard(card("review_recall", id = 42L))
        assertEquals("${Screen.RelationshipReview.route}/42", route)
        assertFalse("不能退回查询参数写法", route!!.contains("?"))
        assertFalse("不能与结果回访卡同路", route == routeForTaskCard(card("feedback_outcome", id = 42L)))
    }

    // ------------------------------------------------------------------ #
    // §8.2 行动行
    // ------------------------------------------------------------------ #

    private fun structured(
        suggestedReply: String? = null,
        openingLines: List<String>? = null,
        mediaId: Long? = null,
    ) = com.couple.translator.core.data.model.AiDto.StructuredOutput(
        suggestedReply = suggestedReply,
        openingLines = openingLines,
        mediationId = mediaId,
    )

    @Test
    fun `没有可复制内容时不给复制分享写信三个空按钮`() {
        val actions = actionsFor("private_advisor", structured())
        assertFalse(AiAction.COPY_REPLY in actions)
        assertFalse(AiAction.SHARE_REPLY in actions)
        assertFalse(AiAction.MAKE_LETTER in actions)
    }

    @Test
    fun `有建议回复时复制分享写信齐备`() {
        val actions = actionsFor("partner_translate", structured(suggestedReply = "我们聊聊？"))
        assertTrue(AiAction.COPY_REPLY in actions)
        assertTrue(AiAction.SHARE_REPLY in actions)
        assertTrue(AiAction.MAKE_LETTER in actions)
    }

    @Test
    fun `冷战场景的破冰话术也算可复制的建议表达`() {
        val actions = actionsFor("cold_war", structured(openingLines = listOf("这两天我想了很多")))
        assertTrue(AiAction.COPY_REPLY in actions)
        assertTrue(AiAction.MAKE_LETTER in actions)
    }

    @Test
    fun `复盘场景不再自己跳自己`() {
        val actions = actionsFor("relationship_review", structured(suggestedReply = "x"))
        assertFalse(AiAction.SAVE_REVIEW in actions)
        // 其他动作不该被一并砍掉
        assertTrue(AiAction.COPY_REPLY in actions)
    }

    @Test
    fun `调解门控未开时永不出现调解按钮`() {
        // §8.2/§8.5：调解未过验收前不得暴露残缺流程。即使后端给了 mediationId，
        // FeatureGate.MEDIATION = false 也必须把它按住——这是契约里的硬门控。
        assertFalse(FeatureGate.MEDIATION)
        val actions = actionsFor("private_advisor", structured(mediaId = 99L))
        assertFalse(AiAction.START_MEDIATION in actions)
    }

    @Test
    fun `反馈永远可用`() {
        val actions = actionsFor("private_advisor", structured())
        assertTrue(AiAction.FEEDBACK in actions)
    }

    // ------------------------------------------------------------------ #
    // §8.3 反馈行三态
    // ------------------------------------------------------------------ #

    private fun fb(outcome: String? = null, adopted: Boolean? = null) =
        com.couple.translator.core.data.model.AiDto.FeedbackOut(
            messageId = 1L,
            adopted = adopted,
            outcome = outcome,
        )

    @Test
    fun `未表态问有没有用`() {
        assertEquals(FeedbackRowMode.ASK, feedbackRowModeOf(null))
        assertEquals(FeedbackRowMode.ASK, feedbackRowModeOf(fb()))
        assertEquals(FeedbackRowMode.ASK, feedbackRowModeOf(fb(adopted = false)))
    }

    @Test
    fun `已采纳未填结果催回访`() {
        assertEquals(FeedbackRowMode.OUTCOME_DUE, feedbackRowModeOf(fb(adopted = true)))
    }

    @Test
    fun `已填结果进入回看态`() {
        assertEquals(FeedbackRowMode.DONE, feedbackRowModeOf(fb(outcome = "聊开了")))
        // 结果优先于采纳态：已填结果就是终态
        assertEquals(
            FeedbackRowMode.DONE,
            feedbackRowModeOf(fb(outcome = "聊开了", adopted = true)),
        )
    }

    // ------------------------------------------------------------------ #
    // §8.2 可复制内容优先级
    // ------------------------------------------------------------------ #

    @Test
    fun `可复制内容优先级为建议回复 大于 破冰话术 大于 改写 大于 正文`() {
        assertEquals(
            "建议回复",
            copyableReplyOf(structured(suggestedReply = "建议回复", openingLines = listOf("破冰")), "正文"),
        )
        assertEquals(
            "破冰",
            copyableReplyOf(structured(openingLines = listOf("破冰")), "正文"),
        )
        assertEquals("正文", copyableReplyOf(null, "正文"))
        assertNull(copyableReplyOf(null, "   "))
    }
}
