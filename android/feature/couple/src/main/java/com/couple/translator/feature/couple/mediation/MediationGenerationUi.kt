package com.couple.translator.feature.couple.mediation

import com.couple.translator.feature.couple.data.model.MediationDto

/**
 * 生成状态到 UI 的**唯一翻译层**（整改 B4.1-4）。
 *
 * 为什么需要它：服务端有七八个会话状态（inviting / accepted / inputting /
 * rewriting / confirming / summarizing / completed / rewrite_failed /
 * summary_failed），客户端此前把它们拍扁成一个布尔 `isGenerating`——
 * 于是「失败了」和「正在跑」长得一模一样，用户只能对着一张转圈的页面等一个
 * 永远不会来的结果。**这不是文案问题，是状态丢失**。
 *
 * 这里把「服务端状态 + 失败信息 + 任务进度」翻成客户端真正要区分的五件事：
 *
 * | 相位 | 用户看到 | 有没有出路 |
 * |------|---------|-----------|
 * | [IDLE] | 没有生成这件事 | — |
 * | [GENERATING] | 正在生成 + 已等 N 秒 | 继续等（页面里在轮询） |
 * | [RETRYING] | 生成失败，正在自动重试（第 n/m 次） | 等，或手动重试立刻排 |
 * | [FAILED] | 生成失败 + 原因 | 重试（服务端允许时） |
 * | [COMPLETED] | 结果就在这儿 | — |
 *
 * 轮询上限到达时**不许判成服务端失败**：状态在服务端，用户退出重进一定看得到，
 * 所以那时只是停止轮询、留着「继续等 / 刷新」的出路，见 [MediationPolling]。
 */
enum class MediationGenerationPhase {
    /** 没有正在进行的生成。 */
    IDLE,

    /** 服务端正在跑（rewriting / summarizing），还没失败过。 */
    GENERATING,

    /** 失败但还会自动重试（`TASK_RETRY_SCHEDULED`）：用户只需等。 */
    RETRYING,

    /** 终态失败（`TASK_EXHAUSTED`）：必须由用户手动重试或重新发起。 */
    FAILED,

    /** 已完成，结果就绪。 */
    COMPLETED,
}

/**
 * 生成相位的完整展示态。
 *
 * @param phase 五相位之一
 * @param waitSeconds 已经等了多久（秒）；非生成态为 0
 * @param attempt 当前第几次尝试（1 起）；无任务信息为 0
 * @param maxAttempts 尝试上限；无任务信息为 0
 * @param failureMessage 失败原因（服务端只给类型与短消息，**不含用户正文**）
 * @param canRetry 服务端允许手动重试（`failure.retryable`）
 */
data class MediationGenerationState(
    val phase: MediationGenerationPhase,
    val waitSeconds: Int = 0,
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
    val failureMessage: String? = null,
    val canRetry: Boolean = false,
) {
    val isGenerating: Boolean
        get() = phase == MediationGenerationPhase.GENERATING ||
            phase == MediationGenerationPhase.RETRYING

    /** 页面该不该继续轮询：只有还在跑/还在自动重试时才值得等。 */
    val shouldKeepPolling: Boolean get() = isGenerating

    /** 有失败（含还在自动重试的）：页面必须显示出来，不许当成"还在转"。 */
    val hasFailure: Boolean
        get() = phase == MediationGenerationPhase.RETRYING || phase == MediationGenerationPhase.FAILED
}

/**
 * 服务端状态 + 失败 + 任务 → [MediationGenerationState]（纯函数，可单测）。
 *
 * 优先级刻意是「失败 > 生成中 > 完成」：
 * 失败时**绝不能**回到 GENERATING——那正是「失败伪装成一直 processing」。
 * 反过来，只要有失败态就说明这一次生成有了明确结论，客户端必须让用户看见。
 */
fun generationStateOf(
    status: String,
    failure: MediationDto.MediationFailure?,
    task: MediationDto.MediationTaskInfo?,
    waitedMs: Long = 0L,
): MediationGenerationState {
    val seconds = (waitedMs / 1000L).toInt().coerceAtLeast(0)
    val attempt = task?.attempt ?: 0
    val maxAttempts = task?.maxAttempts ?: 0
    val failureMessage = failure?.message
    val canRetry = failure?.retryable != false

    return when {
        status == "rewrite_failed" || status == "summary_failed" ->
            MediationGenerationState(
                phase = if (failure?.isAutoRetrying == true) {
                    MediationGenerationPhase.RETRYING
                } else {
                    // 没有 failure 载荷的失败态（旧服务端 / 字段缺失）也按终态处理：
                    // 状态名本身就是结论，不需要再等一个证明。
                    MediationGenerationPhase.FAILED
                },
                waitSeconds = seconds,
                attempt = attempt,
                maxAttempts = maxAttempts,
                failureMessage = failureMessage,
                canRetry = canRetry,
            )

        status == "rewriting" || status == "summarizing" ->
            MediationGenerationState(
                phase = MediationGenerationPhase.GENERATING,
                waitSeconds = seconds,
                attempt = attempt,
                maxAttempts = maxAttempts,
            )

        status == MediationFlow.COMPLETED ->
            MediationGenerationState(phase = MediationGenerationPhase.COMPLETED)

        else -> MediationGenerationState(phase = MediationGenerationPhase.IDLE)
    }
}

/**
 * 生成中/失败的等待文案（不写死在 Composable 里，便于两个页面共用同一口径）。
 */
fun waitHeadlineOf(state: MediationGenerationState, summarizing: Boolean): String = when (state.phase) {
    MediationGenerationPhase.GENERATING ->
        if (summarizing) "AI 正在整理你们说定的内容…" else "AI 正在准备你们的改写…"
    MediationGenerationPhase.RETRYING ->
        "刚刚没成功，正在自动重试" + if (state.maxAttempts > 0) {
            // ${...} 的花括号是必须的：`$state.maxAttempts` 只会插值 `$state`，
            // 后面跟着字面量 ".maxAttempts"——那会把整个数据类的 toString()
            // 渲染到用户脸上（本行由 MediationGenerationUiTest 守）。
            "（第 ${state.attempt.coerceAtLeast(1)}/${state.maxAttempts} 次）"
        } else {
            ""
        }
    MediationGenerationPhase.FAILED -> "这次没生成成功"
    else -> ""
}

/**
 * 轮询节奏（整改 B4.1-5）。
 *
 * 旧实现是 `3s × 30 = 90s`，注释还写着「够长到覆盖一次 120s 超时的 LLM 调用」——
 * **这个说法是错的**：单次 LLM 超时就有 120s，何况任务还有最多 3 次尝试与
 * 指数退避（30s、60s），总窗口轻松超过 3 分钟。90s 一到，客户端就把**还在正常
 * 生成**的服务端判成了「比平时久」，用户以为坏了。
 *
 * 现在改成**有上限的退避轮询**：
 *
 * - 前几次密集（3s、4s…）——多数生成十几秒就完了，快问快答体验最好；
 * - 之后逐步拉长到 [MAX_INTERVAL_MS]——长任务不再把请求打成一秒一发；
 * - 总窗口 [TOTAL_BUDGET_MS] 覆盖「单次 120s 超时 × 3 次尝试 + 指数退避 + 网络抖动」；
 * - 到时**不判失败**，只停止轮询并提示「仍在处理中，可稍后回来查看」——
 *   状态在服务端，重进一定看得到。
 */
object MediationPolling {
    /** 第一次轮询的间隔：生成常见耗时在 10~20s，起步要密。 */
    const val FIRST_INTERVAL_MS = 3_000L

    /** 间隔上限：再长会让"刚刚好"的结果多等很久。 */
    const val MAX_INTERVAL_MS = 15_000L

    /**
     * 总等待窗口：8 分钟。
     *
     * 口径 = 单次 LLM 超时 120s × 最多 3 次尝试（360s）+ 指数退避 30s + 60s（90s）
     * + 调度与网络抖动余量（约 30s）。取 8 分钟而不是"算出来的 480s 整"，
     * 是为了留出 worker 被重启/租约回收后重新领取的时间。
     */
    const val TOTAL_BUDGET_MS = 8 * 60_000L

    /** 每次调用给下一次的间隔：翻倍但不超过 [MAX_INTERVAL_MS]。 */
    fun nextIntervalMs(attempt: Int): Long {
        val doubled = FIRST_INTERVAL_MS shl attempt.coerceIn(0, 10)
        return doubled.coerceAtMost(MAX_INTERVAL_MS)
    }

    /**
     * 等「人」（邀请页/输入页）的间隔：比生成慢一档。
     *
     * 对方接受邀请、写完感受都是**分钟级**的人的动作，3s 一发纯属浪费；
     * 但也不能慢到「对方接受了还要等半分钟才看见」，所以固定 5s。
     */
    const val HUMAN_WAIT_INTERVAL_MS = 5_000L

    /** 是否已经用完等待窗口。 */
    fun budgetExhausted(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_BUDGET_MS

    /**
     * 「等**人**」的窗口：30 分钟。
     *
     * 邀请页/输入页等的是对方接受邀请、写下自己的感受——这跟等 LLM 是两件事，
     * 拿生成窗口（8 分钟）去限制它会把「对方还在上班」误判成异常。但也不能
     * 无限轮询：窗口用尽只**暂停**轮询并提示可以稍后回来，用户点一下就能继续等
     * （见各 VM 的 `resumeWaiting`），状态始终以服务端为准。
     */
    const val HUMAN_WAIT_TOTAL_BUDGET_MS = 30 * 60_000L

    /** 是否已经用完等「人」的窗口。 */
    fun humanWaitExhausted(elapsedMs: Long): Boolean = elapsedMs >= HUMAN_WAIT_TOTAL_BUDGET_MS

    /**
     * 窗口用尽时的提示。**不写「失败」**：服务端还在跑，只是我们不再等下去了。
     */
    const val BUDGET_EXHAUSTED_NOTICE =
        "还在处理中，比平时久一些。可以先去做别的，回来从「调解回看」进来看结果"

    /** 等「人」的窗口用尽时的提示：**同样不写「失败」**，对方只是还没回应。 */
    const val HUMAN_WAIT_PAUSED_NOTICE =
        "已经等了一会儿了。可以先去做别的，回来点「继续等待」看看对方回应了没有"
}

/**
 * 生成相关动作（失败/超时的出路）。
 *
 * 这些是**页面级**动作（不是 AI 回答下方的行动行），所以独立于
 * `AiAction` 那套枚举：那边的动作挂在一条助手消息上，这边的动作挂在会话状态上。
 */
enum class MediationAction(val label: String) {
    /** 手动重试失败的那一步（服务端不新建会话、不丢已有输入）。 */
    RETRY("重试"),

    /** 重新发起一场调解（旧会话保留，仍在历史里）。 */
    RESTART("重新发起"),
}

/**
 * 当前状态下该给哪些出路（纯函数，可单测）。
 *
 * 规则：
 * - 终态失败 → 重试（服务端允许时）+ 重新发起；
 * - 还在自动重试 → 不给按钮（点了也只是立刻重排，UI 上一堆按钮反而让人以为坏了），
 *   但给「重新发起」这条硬出路；
 * - 其它状态 → 无。
 */
fun generationActionsFor(state: MediationGenerationState): List<MediationAction> = buildList {
    if (state.phase == MediationGenerationPhase.FAILED) {
        if (state.canRetry) add(MediationAction.RETRY)
        add(MediationAction.RESTART)
    } else if (state.phase == MediationGenerationPhase.RETRYING) {
        add(MediationAction.RESTART)
    }
}
