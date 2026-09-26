package com.couple.translator.feature.couple.mediation

import com.couple.translator.feature.couple.data.model.MediationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整改 B4.1-4 / B4.1-5 的**状态翻译层**测试。
 *
 * 为什么这一层必须被测：它把服务端的七八个状态翻成五种「用户能分辨的事」。
 * 翻译错一个分支，用户看到的就是「失败了但页面还在转圈」——不报错、不崩溃、
 * 只是把人永远晾在那儿等一个不会来的结果。这正是 §8.5 走查失败的那一类
 * 静默缺陷，只能靠断言守住。
 */
class MediationGenerationUiTest {

    private fun failure(
        code: String? = "TASK_EXHAUSTED",
        message: String? = "AI 服务暂时不可用",
        retryable: Boolean = true,
    ) = MediationDto.MediationFailure(
        code = code,
        message = message,
        failedAt = null,
        retryable = retryable,
    )

    private fun task(attempt: Int = 3, maxAttempts: Int = 3) = MediationDto.MediationTaskInfo(
        id = 1L,
        type = "mediation_rewrite",
        state = "failed",
        attempt = attempt,
        maxAttempts = maxAttempts,
        nextRetryAt = null,
    )

    // ------------------------------------------------------------------ #
    // 相位判定
    // ------------------------------------------------------------------ #

    @Test
    fun `生成中识别为 GENERATING 而不是失败`() {
        val state = generationStateOf("rewriting", failure = null, task = null, waitedMs = 12_000)
        assertEquals(MediationGenerationPhase.GENERATING, state.phase)
        assertTrue(state.isGenerating)
        assertTrue(state.shouldKeepPolling)
        assertFalse("还在跑不算失败", state.hasFailure)
        assertEquals(12, state.waitSeconds)
    }

    @Test
    fun `自动重试中的失败不许伪装成生成中`() {
        // 服务端在退避重排（TASK_RETRY_SCHEDULED）：用户不必操作，但**必须看见**
        // 「刚刚没成功」——否则他以为一切正常，而实际上已经失败过一次了。
        val state = generationStateOf(
            status = "rewrite_failed",
            failure = failure(code = "TASK_RETRY_SCHEDULED"),
            task = task(attempt = 1),
        )
        assertEquals(MediationGenerationPhase.RETRYING, state.phase)
        assertTrue(state.isGenerating)
        assertTrue(state.hasFailure)
        assertEquals(1, state.attempt)
        assertEquals(3, state.maxAttempts)
        // 自动重试期间仍要轮询：重排后状态会回到 rewriting
        assertTrue(state.shouldKeepPolling)
    }

    @Test
    fun `终态失败识别为 FAILED 且不再轮询`() {
        val state = generationStateOf(
            status = "rewrite_failed",
            failure = failure(code = "TASK_EXHAUSTED", message = "试了几次都没成功"),
            task = task(),
        )
        assertEquals(MediationGenerationPhase.FAILED, state.phase)
        assertFalse("终态失败再轮询也不会变", state.shouldKeepPolling)
        assertFalse(state.isGenerating)
        assertTrue(state.hasFailure)
        assertEquals("试了几次都没成功", state.failureMessage)
        assertTrue("服务端允许重试时页面必须给按钮", state.canRetry)
    }

    @Test
    fun `没有 failure 载荷的失败态也按终态处理`() {
        // 旧服务端 / 字段缺失的情况：状态名本身就是结论，不需要再等一个证明。
        // 这一条是「失败被吞掉」的最后一道保险——不能因为缺字段就退回 IDLE。
        val state = generationStateOf("summary_failed", failure = null, task = null)
        assertEquals(MediationGenerationPhase.FAILED, state.phase)
        assertTrue(state.hasFailure)
    }

    @Test
    fun `失败优先级高于生成中`() {
        // 状态与失败载荷冲突时（理论上不该出现，但真出现时必须安全）：
        // 宁可多报一次失败，也绝不把有明确结论的一次说成"还在跑"。
        val state = generationStateOf(
            status = "rewrite_failed",
            failure = failure(code = "TASK_RETRY_SCHEDULED"),
            task = null,
        )
        assertTrue("绝不能回到 GENERATING", state.phase != MediationGenerationPhase.GENERATING)
    }

    @Test
    fun `已完成与空闲分得清`() {
        assertEquals(
            MediationGenerationPhase.COMPLETED,
            generationStateOf(MediationFlow.COMPLETED, null, null).phase,
        )
        assertEquals(
            MediationGenerationPhase.IDLE,
            generationStateOf("confirming", null, null).phase,
        )
        assertEquals(
            MediationGenerationPhase.IDLE,
            generationStateOf("inputting", null, null).phase,
        )
    }

    @Test
    fun `不支持重试的失败不给重试按钮`() {
        val state = generationStateOf(
            status = "rewrite_failed",
            failure = failure(retryable = false),
            task = null,
        )
        assertFalse(state.canRetry)
    }

    // ------------------------------------------------------------------ #
    // 出路
    // ------------------------------------------------------------------ #

    @Test
    fun `终态失败给重试与重新发起两条路`() {
        val state = MediationGenerationState(
            phase = MediationGenerationPhase.FAILED,
            canRetry = true,
        )
        val actions = generationActionsFor(state)
        assertTrue(MediationAction.RETRY in actions)
        assertTrue("只给重试会把用户堵死", MediationAction.RESTART in actions)
    }

    @Test
    fun `自动重试中不给重试按钮但保留重新发起`() {
        val actions = generationActionsFor(
            MediationGenerationState(phase = MediationGenerationPhase.RETRYING, canRetry = true),
        )
        assertFalse("点了也只是立刻重排，一堆按钮反而让人以为坏了", MediationAction.RETRY in actions)
        assertTrue(actions.contains(MediationAction.RESTART))
    }

    @Test
    fun `生成中与已完成都不给任何按钮`() {
        assertTrue(
            generationActionsFor(MediationGenerationState(MediationGenerationPhase.GENERATING)).isEmpty(),
        )
        assertTrue(
            generationActionsFor(MediationGenerationState(MediationGenerationPhase.COMPLETED)).isEmpty(),
        )
    }

    // ------------------------------------------------------------------ #
    // 轮询预算（B4.1-5）
    // ------------------------------------------------------------------ #

    @Test
    fun `退避间隔递增且封顶`() {
        assertEquals(3_000L, MediationPolling.nextIntervalMs(0))
        assertEquals(6_000L, MediationPolling.nextIntervalMs(1))
        assertEquals(12_000L, MediationPolling.nextIntervalMs(2))
        // 翻倍到这里就会撞上限，此后恒定——不能无限翻倍（那会变成再也不问）
        assertEquals(MediationPolling.MAX_INTERVAL_MS, MediationPolling.nextIntervalMs(3))
        assertEquals(MediationPolling.MAX_INTERVAL_MS, MediationPolling.nextIntervalMs(99))
    }

    @Test
    fun `总窗口覆盖单次超时乘以尝试次数加上退避`() {
        // 旧实现 3s×30 = 90s，注释却写着「够覆盖一次 120s 超时」——那是错的。
        // 现在这条断言把口径钉死：窗口必须 ≥ 120s × 3 + 退避余量。
        val singleLlmTimeoutMs = 120_000L
        val maxAttempts = 3
        assertTrue(
            "窗口 ${MediationPolling.TOTAL_BUDGET_MS}ms 覆盖不了 $maxAttempts 次超时",
            MediationPolling.TOTAL_BUDGET_MS >= singleLlmTimeoutMs * maxAttempts,
        )
    }

    @Test
    fun `预算用尽只停轮询不判失败`() {
        assertFalse(MediationPolling.budgetExhausted(MediationPolling.TOTAL_BUDGET_MS - 1))
        assertTrue(MediationPolling.budgetExhausted(MediationPolling.TOTAL_BUDGET_MS))
        // 提示语里**不许**出现「失败」二字：服务端还在跑，重进一定看得到。
        assertFalse(MediationPolling.BUDGET_EXHAUSTED_NOTICE.contains("失败"))
        // 也不许把「等对方」说成失败（那是人还没回应，不是系统坏了）
        assertFalse(MediationPolling.HUMAN_WAIT_PAUSED_NOTICE.contains("失败"))
    }

    @Test
    fun `等人与等生成的窗口是两个量级`() {
        // 等 LLM 是秒级～分钟级，等人是分钟级～小时级；用一个窗口套两件事，
        // 必然把「对方还在上班」误判成异常。
        assertTrue(MediationPolling.HUMAN_WAIT_TOTAL_BUDGET_MS > MediationPolling.TOTAL_BUDGET_MS)
        assertTrue(MediationPolling.HUMAN_WAIT_INTERVAL_MS > MediationPolling.FIRST_INTERVAL_MS)
        assertFalse(MediationPolling.humanWaitExhausted(MediationPolling.HUMAN_WAIT_TOTAL_BUDGET_MS - 1))
        assertTrue(MediationPolling.humanWaitExhausted(MediationPolling.HUMAN_WAIT_TOTAL_BUDGET_MS))
    }

    // ------------------------------------------------------------------ #
    // 文案
    // ------------------------------------------------------------------ #

    @Test
    fun `等待文案区分改写与总结`() {
        val generating = MediationGenerationState(MediationGenerationPhase.GENERATING)
        assertTrue(waitHeadlineOf(generating, summarizing = false).contains("改写"))
        assertTrue(waitHeadlineOf(generating, summarizing = true).contains("整理"))
    }

    @Test
    fun `自动重试文案带尝试次数`() {
        val state = MediationGenerationState(
            phase = MediationGenerationPhase.RETRYING,
            attempt = 2,
            maxAttempts = 3,
        )
        val text = waitHeadlineOf(state, summarizing = false)
        assertTrue("文案里要说「重试」：实际是「$text」", text.contains("重试"))
        assertTrue("要让用户知道还剩几次，否则他不知道该不该继续等：实际是「$text」", text.contains("2/3"))
    }

    @Test
    fun `没有任务信息时重试文案不出现残缺的斜杠`() {
        val text = waitHeadlineOf(
            MediationGenerationState(MediationGenerationPhase.RETRYING),
            summarizing = false,
        )
        assertFalse("不能渲染成「第 0/0 次」", text.contains("/"))
    }
}
