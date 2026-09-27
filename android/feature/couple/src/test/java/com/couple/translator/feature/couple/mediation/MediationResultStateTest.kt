package com.couple.translator.feature.couple.mediation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结果页状态的三道门（整改 B4.3 P0-2）。
 *
 * 这三道门都是「安全终止 ≠ 正常完成」这一个判断的三个出口。分开测的理由：
 * 漏掉任何一个，安全终止的会话就会在结果页上长出一排它不该有的东西——
 * 「选择下一步」（点了会被服务端 50003 拒掉）、「重试生成」（等于鼓励用户
 * 绕开安全阻断）、或「这次调解没有留下总结」（把一次主动干预说成技术故障）。
 */
class MediationResultStateTest {

    private fun state(
        status: String = "completed",
        safetyBlocked: Boolean = false,
        generation: MediationGenerationState = MediationGenerationState(MediationGenerationPhase.IDLE),
        pollingBudgetExhausted: Boolean = false,
        isLoading: Boolean = false,
        commonPoints: List<String> = emptyList(),
    ) = MediationResultUiState(
        status = status,
        safetyBlocked = safetyBlocked,
        generation = generation,
        pollingBudgetExhausted = pollingBudgetExhausted,
        isLoading = isLoading,
        commonPoints = commonPoints,
    )

    @Test
    fun `正常谈完才有下一步动作`() {
        assertTrue(state(status = "completed").isCompleted)
        assertFalse("安全终止不是「谈完了」", state(status = "safety_blocked", safetyBlocked = true).isCompleted)
        listOf("inviting", "inputting", "rewriting", "confirming", "summarizing", "summary_failed")
            .forEach { assertFalse("$it 不该有下一步动作", state(status = it).isCompleted) }
    }

    @Test
    fun `安全终止不给重试生成`() {
        assertFalse(state(status = "safety_blocked", safetyBlocked = true).canRetryGeneration)
        assertFalse(state(status = "completed").canRetryGeneration)
        assertTrue(state(status = "summary_failed").canRetryGeneration)
        assertTrue(state(status = "summarizing").canRetryGeneration)
    }

    @Test
    fun `安全终止不显示「没有留下总结」`() {
        // 这一场本来就不该有调解产物。说「没有留下总结」等于把一次主动的
        // 安全干预讲成一次技术故障，用户会去点「重新发起」而不是看安全提示。
        assertFalse(
            "安全终止不得渲染空态",
            state(status = "safety_blocked", safetyBlocked = true).isEmpty,
        )
        // 对照：真正空的结果页仍然显示空态
        assertTrue(state(status = "completed").isEmpty)
    }

    @Test
    fun `安全终止不进入轮询`() {
        assertFalse(
            MediationFlow.shouldKeepPolling("safety_blocked"),
        )
    }
}
