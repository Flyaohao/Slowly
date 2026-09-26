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

    /** 已见终局（§8.5-6）：completed 仍可回看，但不再有任何推进动作。 */
    const val COMPLETED = "completed"

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
        myConfirmed && partnerConfirmed -> MediationStep.RESULT
        myConfirmed -> MediationStep.WAITING_PARTNER_CONFIRM
        else -> MediationStep.STAY
    }

    /** 结果页：总结没生成完就继续轮询，生成完才算真的可以回看。 */
    fun resultReady(mediationStatus: String): Boolean =
        mediationStatus == COMPLETED

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
}
