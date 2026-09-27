package com.couple.translator.feature.couple.mediation

/**
 * 调解流程的**唯一裁决点**（整改 §8.5-2 / §8.5-5 / §8.5-7）。
 *
 * 为什么单独抽出来：调解的「现在该在哪个页面」此前是散落在各个 Screen 的
 * `LaunchedEffect` 里的 if——提交成功就跳确认页、确认成功就跳结果页。
 * 那两个 if 正是 §8.5 走查失败的地方：
 * - 第一方提交后不该进确认页（对方还没写，改写也没生成），该进等待态；
 * - 一个人确认后不该进结果页（总结还没生成，另一侧从没确认过）。
 *
 * 抽成纯函数后这些判断不依赖 Compose / 网络，可以直接单测（见同目录测试），
 * 也保证「断线重进按服务端状态恢复」与「正常走流程」走的是同一套判断——
 * 恢复逻辑另写一份的话，两条路径迟早会分叉。
 */
object MediationFlow {

    /** 服务端在后台生成（§8.5-8）：客户端只显示等待，不报错也不空白。 */
    val GENERATING_STATUSES = setOf("rewriting", "summarizing")

    /**
     * 生成失败的终态（整改 B4.1-4）：`rewrite_failed` / `summary_failed`。
     *
     * 这两个状态此前没有任何分支认识它们，于是落进 `afterSubmit` 的 `else`——
     * 和「对方还没写完」走同一条路。结果是**失败被伪装成一直在处理**：
     * 用户对着等待页转圈，永远等不到结果，也没有任何可点的出路。
     * 现在所有等待循环都必须在看到它们时停下来交还用户。
     */
    val FAILURE_STATUSES = setOf("rewrite_failed", "summary_failed")

    /** 已见终局（§8.5-6）：completed 仍可回看，但不再有任何推进动作。 */
    const val COMPLETED = "completed"

    /**
     * 安全终止（整改 B4.3 P0-2）：高风险语境下 AI **主动终止**这次调解。
     *
     * 它必须是**独立终态**而不是 completed 的马甲：两者对用户的含义完全相反
     * ——「你们谈完了」vs「这次不能替你们谈下去，先照顾好自己」。混成一个
     * 状态，结果页就会给安全终止配上一整排「继续沟通 / 结束调解」，
     * 而 `continue` 还会把它重新拉回正常流程。
     *
     * 后端 `MEDIATION_STATUSES` 里的同名值；客户端**只认这一个字符串**，
     * 不做任何模糊匹配（认错方向的代价不对称）。
     */
    const val SAFETY_BLOCKED = "safety_blocked"

    /** 终态集合：不再有任何推进动作，只能回看。 */
    val TERMINAL_STATUSES = setOf(COMPLETED, SAFETY_BLOCKED)

    /**
     * 调解列表行尾的状态标签（纯函数，可单测）。
     *
     * 为什么列表必须区分：`history` 列表只筛 `completed`，安全终止那一场
     * **不会**出现在这里；但它出现在 `mine` / `all` 里，用户会看到一行。
     * 若那一行也写「已完成」，就等于告诉用户「你们把这件事谈开了」——
     * 而事实是 AI 拒绝了这次调解。所以这里必须显示「已安全终止」。
     */
    fun historyStatusLabel(mediationStatus: String): String? = when (mediationStatus) {
        COMPLETED -> "已完成"
        SAFETY_BLOCKED -> "已安全终止"
        else -> null
    }

    /**
     * 结果页是否该渲染「下一步」推进动作。
     *
     * **只有正常谈完的 completed 才给**。安全终止是终态：它可回看安全提示，
     * 但不得出现「继续沟通 / 结束调解 / 重试生成」——那些动作要么被服务端
     * 拒绝（continue 对 safety_blocked 返回 50003），要么语义错误
     * （「结束」一场已经被终止的调解）。
     */
    fun showsNextStepActions(mediationStatus: String): Boolean = mediationStatus == COMPLETED

    /**
     * 结果页是否可以显示「重试生成」。
     *
     * 安全终止**不是失败**（后端刻意不写 `mediation_failure_code`）：重试
     * 只会再触发一次同样的高风险判定。把它渲染成「生成失败，可重试」是
     * 在鼓励用户绕开安全阻断。
     */
    fun allowsRetry(mediationStatus: String): Boolean =
        mediationStatus !in TERMINAL_STATUSES

    /**
     * 提交输入后该去哪。
     *
     * - `waiting_partner`：我是**第一方**，服务端仍是 inputting → 停在等待态，
     *   等对方也写完（§8.5-2 的硬要求，此前直接跳确认页，用户会看到空白改写）。
     * - `waiting_rewrite`：双方都写完了，服务端在后台生成改写 → 等待态。
     * - `confirm`：改写已就绪 → 确认页。
     */
    fun afterSubmit(mediationStatus: String): MediationStep = when (mediationStatus) {
        "inputting", "accepted", "inviting" -> MediationStep.WAITING_PARTNER
        "rewriting" -> MediationStep.WAITING_REWRITE
        "confirming" -> MediationStep.CONFIRM
        "summarizing", COMPLETED -> MediationStep.RESULT
        // 整改 B4.3 P0-2：安全终止也要落到结果页——那一页要显示安全提示，
        // 并**不给**任何推进动作（见 [showsNextStepActions]）。留在等待态
        // 会让用户对着一个永远不会来的改写继续等。
        SAFETY_BLOCKED -> MediationStep.RESULT
        // 整改 B4.1-4：失败必须离开等待态。留在这里的话页面会一直显示
        // 「等对方写完 / AI 正在生成」，而服务端其实已经停了——用户等一个
        // 永远不来的结果。落点是**能给出重试**的那个页面。
        "rewrite_failed" -> MediationStep.FAILED_REWRITE
        "summary_failed" -> MediationStep.FAILED_SUMMARY
        else -> MediationStep.WAITING_PARTNER
    }

    /**
     * 等待中的页面轮询到某个状态后该去哪（§8.5-1 邀请页 / §8.5-7 重进恢复）。
     *
     * 邀请页与「等对方写完」页共用：两者等的都是「服务端状态变了」，
     * 只是起点不同。`isInviter` 只影响邀请页里「还没接受」的那一段文案，
     * 一旦对方接受（状态离开 inviting）就走同一条路。
     */
    fun whileWaiting(mediationStatus: String): MediationStep = when (mediationStatus) {
        // 对方接受 → 双方各自去写自己的部分
        "accepted", "inputting" -> MediationStep.INPUT
        "rewriting" -> MediationStep.WAITING_REWRITE
        "confirming" -> MediationStep.CONFIRM
        "summarizing", COMPLETED -> MediationStep.RESULT
        // P0-2：等待循环里读到安全终止必须立刻离开等待（去结果页看安全提示）
        SAFETY_BLOCKED -> MediationStep.RESULT
        // 整改 B4.1-4：等待循环里读到失败态必须**立刻离开等待**，去能重试的页面。
        // 旧实现里这两个状态没有分支，等待页会一直转圈到轮询窗口用尽为止。
        "rewrite_failed" -> MediationStep.FAILED_REWRITE
        "summary_failed" -> MediationStep.FAILED_SUMMARY
        // 仍停在 inviting：继续等（对方还没回应）
        else -> MediationStep.STAY
    }

    /**
     * 确认页上点了「确认准确」之后该去哪（§8.5-5）。
     *
     * **只有双方都确认**才谈得上结果页；只我确认时留在本页显示「等对方确认」。
     * `pending` 用来兜住「我确认了、服务端也在 summarizing、但对方确认状态
     * 这一帧还没读到」的中间态——宁可多等一轮，也不能让用户看到一场
     * 只有他一个人确认过的总结。
     */
    fun afterConfirm(
        mediationStatus: String,
        myConfirmed: Boolean,
        partnerConfirmed: Boolean,
    ): MediationStep = when {
        mediationStatus == "summarizing" || mediationStatus == COMPLETED ->
            MediationStep.RESULT
        // P0-2：总结阶段被判高风险 → 安全终止，同样落到结果页（看安全提示）
        mediationStatus == SAFETY_BLOCKED -> MediationStep.RESULT
        // 整改 B4.1-4：总结失败时人也该看到**结果页上的失败态**（那里有重试），
        // 而不是停在一张「双方都确认过了」的确认页上——那场调解确实已经走进
        // 总结这一步了，出路在结果页。
        mediationStatus == "summary_failed" -> MediationStep.RESULT
        myConfirmed && partnerConfirmed -> MediationStep.RESULT
        myConfirmed -> MediationStep.WAITING_PARTNER_CONFIRM
        else -> MediationStep.STAY
    }

    /** 结果页：总结没生成完就继续轮询，生成完才算真的可以回看。 */
    fun resultReady(mediationStatus: String): Boolean =
        mediationStatus == COMPLETED

    /**
     * 结果页是否该继续轮询。
     *
     * P0-2：`safety_blocked` **不再轮询**——它是终态，服务端不会再有产出。
     * 旧实现（`isCompleted = status == "completed"`）会让安全终止的会话
     * 永远停在「正在生成」的等待里，直到轮询窗口用尽，然后告诉用户
     * 「还在处理中」——而服务端早就停了。
     */
    fun shouldKeepPolling(mediationStatus: String): Boolean =
        mediationStatus == "summarizing"

    /** 邀请页是否需要继续轮询（对方还没回应）。 */
    fun stillInviting(mediationStatus: String): Boolean = mediationStatus == "inviting"
}

/** 调解流程里的落点。 */
enum class MediationStep {
    /** 停在当前页继续等（对方还没回应 / 状态没变）。 */
    STAY,

    /** 去输入页写自己的部分。 */
    INPUT,

    /** 等待态：我是第一方，等对方也写完。 */
    WAITING_PARTNER,

    /** 等待态：AI 正在生成改写。 */
    WAITING_REWRITE,

    /** 等待态：我已确认，等对方确认。 */
    WAITING_PARTNER_CONFIRM,

    /** 改写确认页。 */
    CONFIRM,

    /** 结果/回看页。 */
    RESULT,

    /**
     * 改写生成失败（整改 B4.1-4）：落到**确认页**——那一页已经具备完整的
     * [MediationGenerationState] 失败态与「重试」按钮（见 `MediationConfirmScreen`）。
     * 不新开一个「失败页」：失败不是流程里的新一步，是同一步的另一种形态。
     */
    FAILED_REWRITE,

    /**
     * 总结生成失败（整改 B4.1-4）：落到**结果页**——同理，
     * `MediationResultScreen` 已带失败态与重试。
     */
    FAILED_SUMMARY,
}
