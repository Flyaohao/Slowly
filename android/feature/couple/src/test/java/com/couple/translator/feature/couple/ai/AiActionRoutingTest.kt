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
        intent: String? = null,
        suggestDualPerspective: Boolean = false,
        reviewWorthy: Boolean = false,
    ) = com.couple.translator.core.data.model.AiDto.StructuredOutput(
        suggestedReply = suggestedReply,
        openingLines = openingLines,
        summary = summary,
        nextStep = nextStep,
        suggestMediation = suggestMediation,
        intent = intent,
        suggestDualPerspective = suggestDualPerspective,
        reviewWorthy = reviewWorthy,
    )

    @Test
    fun `没有可复制内容时不给复制分享写信三个空按钮`() {
        val actions = actionsFor("private_advisor", structured())
        assertFalse(AiAction.COPY_REPLY in actions)
        assertFalse(AiAction.SHARE_REPLY in actions)
        assertFalse(AiAction.MAKE_LETTER in actions)
    }

    @Test
    fun `对方翻译只给复制建议回复`() {
        // 整改 B4.3 P1-7：`partner_translate` 的意图是「解释 TA 那句话」。
        // 用户此刻要的是一句能直接发出去的话，不是一排动作。分享/写信/双视角
        // 都不该出现在这里——它们不是这个意图下用户想做的事。
        val actions = actionsFor("partner_translate", structured(suggestedReply = "我们聊聊？"))
        assertEquals(listOf(AiAction.COPY_REPLY), actions)
    }

    @Test
    fun `冷战优先复制破冰表达`() {
        // 冷战意图下用户点开这个场景要的就是「一句能开口的话」，所以它排第一。
        val actions = actionsFor("cold_war", structured(openingLines = listOf("这两天我想了很多")))
        assertEquals(AiAction.COPY_REPLY, actions.first())
        // 写信不在冷战意图里：冷战要的是**开口**，一封信反而把门槛抬高了
        assertFalse(AiAction.MAKE_LETTER in actions)
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
    fun `有结论且有复盘价值时记录为复盘会出现`() {
        // 整改 B4.3 P1-7：光有结论不够——「这次值不值得存档」由模型显式给出
        // （`review_worthy`）。有结论但没有可复用的模式时，存下来的只是一张
        // 空复盘，所以两个条件都要。
        val actions = actionsFor(
            "private_advisor",
            structured(summary = "你们都想把话说开", nextStep = "本周找时间聊一次", reviewWorthy = true),
        )
        assertTrue(AiAction.SAVE_REVIEW in actions)
    }

    @Test
    fun `有结论但没有复盘价值时不给记录为复盘`() {
        val actions = actionsFor(
            "private_advisor",
            structured(summary = "你们都想把话说开", nextStep = "本周找时间聊一次"),
        )
        assertFalse(AiAction.SAVE_REVIEW in actions)
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
            listOf(AiAction.COPY_REPLY),
            actions,
        )
    }

    // ------------------------------------------------------------------ #
    // §8.2 行动行分组（整改 B4.1-P1：同屏主动作上限）
    // ------------------------------------------------------------------ #

    @Test
    fun `主动作最多两个其余进更多`() {
        // 场景：`expression_rewrite` 意图下的完整候选集
        // （复制 / 分享 / 写信 + 有复盘价值的存档）。
        val plan = actionPlanFor(
            "expression_rewrite",
            structured(
                suggestedReply = "我们聊聊？",
                summary = "说开了",
                intent = "expression_rewrite",
                reviewWorthy = true,
            ),
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
                AiAction.SAVE_REVIEW,
            ),
            plan.all,
        )
        assertEquals(listOf(AiAction.MAKE_LETTER, AiAction.SAVE_REVIEW), plan.more)
    }

    @Test
    fun `动作少于一屏上限时不出现更多`() {
        // 冷战意图：只有可复制的破冰表达 → 候选只剩「复制」一个。
        val plan = actionPlanFor("cold_war", structured(openingLines = listOf("这两天我想了很多")))
        assertEquals(listOf(AiAction.COPY_REPLY), plan.primary)
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
    // §8.2 高风险安全门控（P0-6 客户端侧 + B4.3 P0-4 fail closed）
    // ------------------------------------------------------------------ #

    @Test
    fun `高风险等级判定：只有 normal 与情绪激动放行`() {
        assertTrue(mediationBlockedByRisk(AiRiskLevel.MANIPULATION_RISK))
        assertTrue(mediationBlockedByRisk(AiRiskLevel.ABUSE_RISK))
        assertTrue(mediationBlockedByRisk(AiRiskLevel.SELF_HARM_RISK))
        // 双方情绪激动正是调解要处理的场景，不能一起挡掉（仍会渲染降温提示）
        assertFalse(mediationBlockedByRisk(AiRiskLevel.HEATED_CONFLICT))
        // null = 后端**明确说了** normal，这是唯一可放行的信号
        assertFalse(mediationBlockedByRisk(null))
    }

    /**
     * 整改 B4.3 P0-4：**未知风险必须 fail closed**。
     *
     * 旧实现里 `UNKNOWN` 与 `null` 走同一个分支（放行）——等于「解析不出等级」
     * 被当成「没有风险」。后端某天新增一个更危险的档位、或响应字段被改名，
     * 客户端就会把高风险当正常放行，还一声不响。
     */
    @Test
    fun `无法解析的风险等级必须阻断双人动作`() {
        assertTrue(
            "UNKNOWN 不是「没有风险」，必须挡",
            mediationBlockedByRisk(AiRiskLevel.UNKNOWN),
        )
        // 端到端：从 wire 值一路走到动作裁决。任务书点名的全部形态都过一遍。
        val unknownWireValues = listOf<String?>(
            null, "", "   ", "HIGH", "high_risk", "very_high", "高风险", "3", "true",
        )
        unknownWireValues.forEach { raw ->
            val resolved = AiRiskLevel.fromWire(raw)
            // null 只可能来自「后端明确说了 normal」；这里的输入都不是 normal，
            // 所以必须解析成 UNKNOWN，并且必须被挡。
            assertEquals("「$raw」应解析为 UNKNOWN", AiRiskLevel.UNKNOWN, resolved)
            assertTrue("「$raw」下不得把人拉进同一场会话", mediationBlockedByRisk(resolved))
        }
        // 反面对照：明确的 normal 才放行
        assertNull(AiRiskLevel.fromWire("normal"))
        assertFalse(mediationBlockedByRisk(AiRiskLevel.fromWire("normal")))
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
        // 自己这一侧的出口（复制）不挡：那是让当事人自己把话说好
        assertTrue(AiAction.COPY_REPLY in ordered)
    }

    @Test
    fun `高风险下邀请双视角也被挡掉`() {
        val normal = orderedActionsFor(
            sceneKey = "partner_translate",
            structured = structured(suggestedReply = "x", suggestDualPerspective = true),
            suggestMediation = false,
        )
        assertTrue(AiAction.INVITE_DUAL in normal)
        val risky = orderedActionsFor(
            sceneKey = "partner_translate",
            structured = structured(suggestedReply = "x", suggestDualPerspective = true),
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
        assertTrue(AiAction.COPY_REPLY in ordered)
    }

    // ------------------------------------------------------------------ #
    // §8.2 按意图的确定性路由表（整改 B4.3 P1-7）
    // ------------------------------------------------------------------ #

    /** 每个意图的**确定**期望集合。这张表就是任务书要求的「确定性路由表」。 */
    private data class RoutingCase(
        val label: String,
        val intent: AiIntent,
        val structured: com.couple.translator.core.data.model.AiDto.StructuredOutput,
        val suggestMediation: Boolean,
        val expected: List<AiAction>,
    )

    private fun routingMatrix(): List<RoutingCase> {
        // 每个用例都给「全部可用实体」——即：让 hasReply / hasConclusion /
        // wantsDual / wantsReview 都为真。这样期望集合里的**缺席**就只可能
        // 来自意图路由本身，而不是「没有素材」。
        val everything = structured(
            suggestedReply = "我们聊聊？",
            summary = "说开了",
            nextStep = "本周找时间聊一次",
            suggestDualPerspective = true,
            reviewWorthy = true,
        )
        return listOf(
            RoutingCase(
                "表达改写：复制/分享是主动作，写信进更多；不默认双视角",
                AiIntent.EXPRESSION_REWRITE,
                everything, false,
                listOf(
                    AiAction.COPY_REPLY, AiAction.SHARE_REPLY,
                    AiAction.MAKE_LETTER, AiAction.SAVE_REVIEW,
                ),
            ),
            RoutingCase(
                "对方翻译：复制建议回复，双视角仅明确建议时给",
                AiIntent.PARTNER_TRANSLATE,
                everything, false,
                listOf(AiAction.COPY_REPLY, AiAction.INVITE_DUAL),
            ),
            RoutingCase(
                "私人军师冲突分析：安全且建议调解时给调解，有复盘价值时给存档",
                AiIntent.PRIVATE_ADVISOR,
                everything, true,
                listOf(AiAction.START_MEDIATION, AiAction.COPY_REPLY, AiAction.SAVE_REVIEW),
            ),
            RoutingCase(
                "情绪倾诉：一个动作都不给（继续对话就是唯一的出路）",
                AiIntent.EMOTION_SUPPORT,
                everything, true,
                emptyList(),
            ),
            RoutingCase(
                "冷战：优先复制破冰表达，安全且明确建议时才给调解",
                AiIntent.COLD_WAR,
                everything, true,
                listOf(AiAction.COPY_REPLY, AiAction.START_MEDIATION),
            ),
            RoutingCase(
                "关系复盘：看/存复盘，不显示写信与双视角",
                AiIntent.RELATIONSHIP_REVIEW,
                everything, false,
                listOf(AiAction.SAVE_REVIEW, AiAction.COPY_REPLY),
            ),
            RoutingCase(
                "无法归类：最多复制原回答",
                AiIntent.UNKNOWN,
                everything, true,
                listOf(AiAction.COPY_REPLY),
            ),
        )
    }

    @Test
    fun `每个意图给出确定性的动作集合`() {
        routingMatrix().forEach { case ->
            val actual = orderedActionsFor(
                sceneKey = "whatever",
                structured = case.structured,
                suggestMediation = case.suggestMediation,
                intent = case.intent,
            )
            assertEquals("「${case.label}」的动作集合", case.expected, actual)
            // 主动作上限对每个意图都成立，且「更多」里只能放相关动作——
            // 因为 more 就是同一个有序全集切出来的后半段，不存在「无关动作」。
            val plan = AiActionPlan(
                primary = actual.take(ActionLimits.PRIMARY_MAX),
                more = actual.drop(ActionLimits.PRIMARY_MAX),
            )
            assertTrue(plan.primary.size <= ActionLimits.PRIMARY_MAX)
            assertEquals("「${case.label}」不得在折叠中丢动作", actual, plan.all)
        }
    }

    @Test
    fun `写信与双视角不在表达改写与对方翻译的主动作里`() {
        // 任务书逐条要求：expression_rewrite 不默认双视角与复盘（复盘要 reviewWorthy）；
        // partner_translate 仅 suggest_dual_perspective=true 才允许双视角。
        val rewrite = orderedActionsFor(
            sceneKey = "expression_rewrite",
            structured = structured(suggestedReply = "x", suggestDualPerspective = true),
            suggestMediation = false,
            intent = AiIntent.EXPRESSION_REWRITE,
        )
        assertFalse("表达改写不默认给双视角", AiAction.INVITE_DUAL in rewrite)
        assertFalse("表达改写不默认给复盘", AiAction.SAVE_REVIEW in rewrite)

        val translateNoFlag = orderedActionsFor(
            sceneKey = "partner_translate",
            structured = structured(suggestedReply = "x"),
            suggestMediation = false,
            intent = AiIntent.PARTNER_TRANSLATE,
        )
        assertFalse(
            "没有明确建议时不得把伴侣拉进双视角",
            AiAction.INVITE_DUAL in translateNoFlag,
        )
    }

    @Test
    fun `复盘意图下写信与双视角一律不出现`() {
        val ordered = orderedActionsFor(
            sceneKey = "relationship_review",
            structured = structured(
                suggestedReply = "x",
                summary = "说开了",
                suggestDualPerspective = true,
                reviewWorthy = true,
            ),
            suggestMediation = true,
            intent = AiIntent.RELATIONSHIP_REVIEW,
        )
        assertFalse(AiAction.MAKE_LETTER in ordered)
        assertFalse(AiAction.INVITE_DUAL in ordered)
        assertFalse(AiAction.START_MEDIATION in ordered)
        assertTrue(AiAction.SAVE_REVIEW in ordered)
    }

    @Test
    fun `风险阻断优先于所有意图路由`() {
        // 任务书：风险阻断优先于意图路由。逐个意图 × 逐个阻断档位验证——
        // 即使模型明确建议调解/双视角，这两枚动作一个都不能出现。
        AiIntent.values().forEach { intent ->
            listOf(
                AiRiskLevel.MANIPULATION_RISK, AiRiskLevel.ABUSE_RISK,
                AiRiskLevel.SELF_HARM_RISK, AiRiskLevel.UNKNOWN,
            ).forEach { risk ->
                val ordered = orderedActionsFor(
                    sceneKey = "private_advisor",
                    structured = structured(
                        suggestedReply = "x",
                        suggestMediation = true,
                        suggestDualPerspective = true,
                    ),
                    suggestMediation = true,
                    riskLevel = risk,
                    intent = intent,
                )
                assertFalse(
                    "意图 $intent / 风险 $risk 下不得给调解",
                    AiAction.START_MEDIATION in ordered,
                )
                assertFalse(
                    "意图 $intent / 风险 $risk 下不得给双视角",
                    AiAction.INVITE_DUAL in ordered,
                )
            }
        }
        // 自己这一侧的出口不受影响
        assertTrue(
            AiAction.COPY_REPLY in orderedActionsFor(
                sceneKey = "private_advisor",
                structured = structured(suggestedReply = "x"),
                suggestMediation = false,
                riskLevel = AiRiskLevel.MANIPULATION_RISK,
                intent = AiIntent.PRIVATE_ADVISOR,
            ),
        )
    }

    @Test
    fun `意图缺省时按场景兜底而不是丢弃整行动作`() {
        // 兜底不是「猜正文」：sceneKey 是用户自己选的、或会话上记着的结构化字段。
        // 兜底存在的意义是——模型少填一次 intent 不该让整行动作凭空消失。
        assertEquals(AiIntent.EXPRESSION_REWRITE, intentOfScene("expression_rewrite"))
        assertEquals(AiIntent.PARTNER_TRANSLATE, intentOfScene("partner_translate"))
        assertEquals(AiIntent.COLD_WAR, intentOfScene("cold_war"))
        assertEquals(AiIntent.RELATIONSHIP_REVIEW, intentOfScene("relationship_review"))
        assertEquals(AiIntent.PRIVATE_ADVISOR, intentOfScene("private_advisor"))
        assertEquals(AiIntent.UNKNOWN, intentOfScene("no_such_scene"))

        // wire 解析：合法值优先于兜底；非法/缺失一律回退到场景兜底
        assertEquals(
            AiIntent.EMOTION_SUPPORT,
            resolveIntent("private_advisor", structured(intent = "emotion_support")),
        )
        assertEquals(
            "空 intent 回退到场景兜底",
            AiIntent.PRIVATE_ADVISOR,
            resolveIntent("private_advisor", structured(intent = "")),
        )
        assertEquals(
            "自造 intent 回退到场景兜底",
            AiIntent.PRIVATE_ADVISOR,
            resolveIntent("private_advisor", structured(intent = "very_conflict")),
        )
        assertEquals(
            "场景也认不出时落 UNKNOWN",
            AiIntent.UNKNOWN,
            resolveIntent("no_such_scene", structured(intent = "bogus")),
        )
    }

    @Test
    fun `情绪倾诉意图下行动行整行为空`() {
        // 「一个动作都不给」是**有意的**，不是遗漏：用户此刻要的是被听见。
        // 空计划 = 页面不渲染行动行（AiActionRow 自己判空），
        // 剩下的唯一出路就是把话说完。
        val plan = actionPlanFor(
            "private_advisor",
            structured(suggestedReply = "x", summary = "y", intent = "emotion_support"),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `客户端意图档位与后端契约逐值对齐`() {
        // 后端 ActionIntent 与客户端 AiIntent 必须逐值对齐：任一侧少一个值，
        // 那个值就会静默落进 UNKNOWN（行为从「只给复制」变成「什么都不给」，
        // 或者反过来把倾诉当成冲突）。
        //
        // 这里断言的是 [AiIntent.WIRE_VALUES]——它就是**给后端测试读的那份声明**
        // （`backend/tests/test_action_routing_contract.py` 直接解析这个常量并与
        // `ActionIntent` 比对）。所以本地这条测试的作用是：常量本身不许漂移，
        // 且 `fromWire` 必须认得常量里的每一个值。
        val backendValues = listOf(
            "expression_rewrite", "partner_translate", "private_advisor",
            "emotion_support", "cold_war", "relationship_review", "unknown",
        )
        assertEquals(backendValues, AiIntent.WIRE_VALUES)
        AiIntent.WIRE_VALUES.forEach { wire ->
            val parsed = AiIntent.fromWire(wire)
            assertTrue(
                "后端值「$wire」必须在客户端有对应档位",
                wire == "unknown" || parsed != AiIntent.UNKNOWN,
            )
        }
        assertEquals(7, AiIntent.values().size)
        // 大小写/空格容忍
        assertEquals(AiIntent.EMOTION_SUPPORT, AiIntent.fromWire("  Emotion_Support "))
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
