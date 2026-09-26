package com.couple.translator.feature.couple.ai

import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AiRiskLevel
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
        summary: String? = null,
        nextStep: String? = null,
        suggestMediation: Boolean = false,
    ) = com.couple.translator.core.data.model.AiDto.StructuredOutput(
        suggestedReply = suggestedReply,
        openingLines = openingLines,
        summary = summary,
        nextStep = nextStep,
        suggestMediation = suggestMediation,
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
        // §8.2/§8.5：调解未过验收前不得暴露残缺流程。即使后端判定这是冲突语境
        // （suggest_mediation = true），FeatureGate.MEDIATION = false 也必须把它按住
        // ——这是契约里的硬门控，不是开关偏好。
        assertFalse(FeatureGate.MEDIATION)
        val actions = actionsFor("private_advisor", structured(suggestMediation = true))
        assertFalse(AiAction.START_MEDIATION in actions)
    }

    @Test
    fun `没有结论时不给记录为复盘的按钮`() {
        // 整屏结论字段全空时点「记录为复盘」只会得到一张空复盘——那是把用户
        // 送进一个没有内容的表单，比不给按钮更糟。
        val actions = actionsFor("private_advisor", structured())
        assertFalse(AiAction.SAVE_REVIEW in actions)
    }

    @Test
    fun `有结论时记录为复盘会出现`() {
        val actions = actionsFor(
            "private_advisor",
            structured(summary = "你们都想把话说开", nextStep = "本周找时间聊一次"),
        )
        assertTrue(AiAction.SAVE_REVIEW in actions)
    }

    @Test
    fun `反馈不再是行动行里的一枚 chip`() {
        // 整改 B4.1-P1：反馈是**次级控件**，由 AiFeedbackRow 单独渲染。
        // 此前那枚 FEEDBACK chip 点了没有任何反应（handleAction 里是 `-> Unit`），
        // 是典型的装饰按钮；留在行动行里只会占掉主动作的位置。
        //
        // 断言写成「枚举里根本没有这个名字」而不是「它不在本次结果里」：
        // 后者只能证明这一次没出现，有人把常量加回去照样绿；这里挡的是
        // 「把装饰按钮加回来」这个动作本身。
        assertFalse(
            "AiAction 里不该再有反馈动作——反馈归 AiFeedbackRow 管",
            AiAction.values().any { it.name == "FEEDBACK" },
        )
        val actions = actionsFor("private_advisor", structured(suggestedReply = "x"))
        assertEquals(
            "行动行里的动作必须都是真实动作",
            listOf(AiAction.COPY_REPLY, AiAction.SHARE_REPLY),
            actions.take(2),
        )
    }

    // ------------------------------------------------------------------ #
    // §8.2 行动行分组（整改 B4.1-P1：同屏主动作上限）
    // ------------------------------------------------------------------ #

    @Test
    fun `主动作最多两个其余进更多`() {
        // 场景：有可复制内容（复制/分享/写信/邀请四个候选）+ 有结论（复盘）。
        val plan = actionPlanFor(
            "private_advisor",
            structured(suggestedReply = "我们聊聊？", summary = "说开了"),
        )
        assertTrue("主动作不能超过上限", plan.primary.size <= ActionLimits.PRIMARY_MAX)
        assertEquals("主动作应当正好取满", ActionLimits.PRIMARY_MAX, plan.primary.size)
        // 全量动作一个都不能丢：被折叠的必须原样出现在 more 里，
        // 否则「收敛动作」会变成「砍掉动作」。
        assertEquals(
            listOf(
                AiAction.COPY_REPLY,
                AiAction.SHARE_REPLY,
                AiAction.MAKE_LETTER,
                AiAction.INVITE_DUAL,
                AiAction.SAVE_REVIEW,
            ),
            plan.all,
        )
        assertEquals(
            listOf(AiAction.MAKE_LETTER, AiAction.INVITE_DUAL, AiAction.SAVE_REVIEW),
            plan.more,
        )
    }

    @Test
    fun `动作少于一屏上限时不出现更多`() {
        // 只有结论、没有可复制内容 → 候选只剩「记录为复盘」一个。
        val plan = actionPlanFor("private_advisor", structured(summary = "说开了"))
        assertEquals(listOf(AiAction.SAVE_REVIEW), plan.primary)
        assertTrue("没超过上限时不该多出一扇「更多」的门", plan.more.isEmpty())
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `没有任何可用实体时行动行为空`() {
        // 空内容 + 无结论 + 门控未开：一个动作都给不出来，页面不该渲染一整行空 chip。
        val plan = actionPlanFor("private_advisor", structured())
        assertTrue(plan.isEmpty)
        assertTrue(plan.all.isEmpty())
    }

    @Test
    fun `调解建议出现时排在第一位`() {
        // 整改 B4.1-6 的产品裁决：冲突语境下「把两个人都拉进来说」比
        // 「换句话再说一遍」更治本，所以它排在候补队列的最前面。
        //
        // 直接对排序函数断言，而不是走 actionPlanFor：门控关闭时
        // actionPlanFor 里根本不会有 START_MEDIATION，那条断言会退化成
        // 「它不存在」——等于没测排序。这里把已裁决的 suggestMediation 传进去，
        // 就能在不改产品开关的前提下验证真实排序行为。
        val ordered = orderedActionsFor(
            sceneKey = "private_advisor",
            structured = structured(suggestedReply = "x"),
            suggestMediation = true,
        )
        assertEquals(AiAction.START_MEDIATION, ordered.first())
        // 且它占掉的是一个主动作位，不是一个被折叠的次要动作
        val plan = AiActionPlan(
            primary = ordered.take(ActionLimits.PRIMARY_MAX),
            more = ordered.drop(ActionLimits.PRIMARY_MAX),
        )
        assertTrue(AiAction.START_MEDIATION in plan.primary)
    }

    @Test
    fun `门控未开时调解动作完全不进候选队列`() {
        val ordered = orderedActionsFor(
            sceneKey = "private_advisor",
            structured = structured(suggestedReply = "x", suggestMediation = true),
            suggestMediation = false,
        )
        assertFalse(AiAction.START_MEDIATION in ordered)
        assertEquals(AiAction.COPY_REPLY, ordered.first())
    }

    // ------------------------------------------------------------------ #
    // §8.2 高风险安全门控（P0-6 客户端侧）
    // ------------------------------------------------------------------ #

    @Test
    fun `高风险等级判定：控制暴力自伤挡，情绪激动与未知不挡`() {
        assertTrue(mediationBlockedByRisk(AiRiskLevel.MANIPULATION_RISK))
        assertTrue(mediationBlockedByRisk(AiRiskLevel.ABUSE_RISK))
        assertTrue(mediationBlockedByRisk(AiRiskLevel.SELF_HARM_RISK))
        // 双方情绪激动正是调解要处理的场景，不能一起挡掉
        assertFalse(mediationBlockedByRisk(AiRiskLevel.HEATED_CONFLICT))
        // 解析不出等级（normal / 未知 / null）不许把正常用户的路堵死
        assertFalse(mediationBlockedByRisk(null))
    }

    @Test
    fun `高风险下调解动作完全不进候选队列`() {
        // 后端也会阻断产物；这里挡的是「先看到按钮、点进去才发现走不通」——
        // 行动行是在离开页之前就渲染好的，只在后端挡等于给用户一段残缺流程。
        val ordered = orderedActionsFor(
            sceneKey = "private_advisor",
            structured = structured(suggestedReply = "x", suggestMediation = true),
            suggestMediation = true,
            riskLevel = AiRiskLevel.ABUSE_RISK,
        )
        assertFalse("高风险不得把人拉进同一场会话", AiAction.START_MEDIATION in ordered)
        assertFalse(AiAction.INVITE_DUAL in ordered)
        // 自己这一侧的出口（复制/分享/写信）不挡：那是让当事人自己把话说好
        assertTrue(AiAction.COPY_REPLY in ordered)
        assertTrue(AiAction.MAKE_LETTER in ordered)
    }

    @Test
    fun `高风险下邀请双视角也被挡掉`() {
        val normal = orderedActionsFor(
            sceneKey = "private_advisor",
            structured = structured(suggestedReply = "x"),
            suggestMediation = false,
        )
        assertTrue(AiAction.INVITE_DUAL in normal)
        val risky = orderedActionsFor(
            sceneKey = "private_advisor",
            structured = structured(suggestedReply = "x"),
            suggestMediation = false,
            riskLevel = AiRiskLevel.SELF_HARM_RISK,
        )
        assertFalse(AiAction.INVITE_DUAL in risky)
    }

    @Test
    fun `情绪激动不触发高风险门控`() {
        val ordered = orderedActionsFor(
            sceneKey = "private_advisor",
            structured = structured(suggestedReply = "x"),
            suggestMediation = true,
            riskLevel = AiRiskLevel.HEATED_CONFLICT,
        )
        assertEquals(AiAction.START_MEDIATION, ordered.first())
        assertTrue(AiAction.INVITE_DUAL in ordered)
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
