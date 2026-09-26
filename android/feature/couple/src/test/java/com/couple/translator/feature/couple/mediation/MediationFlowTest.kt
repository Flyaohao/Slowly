package com.couple.translator.feature.couple.mediation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调解流程裁决点的回归测试（整改 §8.5-2 / §8.5-5 / §8.5-7）。
 *
 * 这些断言为什么值得单独写：调解此前**每一步都跳过**——第一方提交后直接进确认页、
 * 一人确认后直接进结果页。代码能编译、路由都存在、接口都能调，只是用户看到的
 * 是空白改写和一份只有自己确认过的总结。这类错误不会报警，只会让走查失败，
 * 所以把「什么时候**不能**去哪」钉成断言。
 */
class MediationFlowTest {

    // ------------------------------------------------------------------ #
    // §8.5-2 提交后：第一方必须停在等待态
    // ------------------------------------------------------------------ #

    @Test
    fun `我是第一方提交后停在等待态而不是确认页`() {
        // 我提交了，服务端仍是 inputting（对方还没写）——改写根本没生成
        assertEquals(MediationStep.WAITING_PARTNER, MediationFlow.afterSubmit("inputting"))
    }

    @Test
    fun `对方先提交时我提交后直接进改写等待`() {
        // 双方都写完了，服务端在后台改写
        assertEquals(MediationStep.WAITING_REWRITE, MediationFlow.afterSubmit("rewriting"))
        assertEquals(MediationStep.CONFIRM, MediationFlow.afterSubmit("confirming"))
    }

    @Test
    fun `提交后任何尚未生成改写的状态都不得进确认页`() {
        // 回归：这条不变式是 §8.5 走查失败的直接原因——
        // 「提交成功就跳确认页」在 inputting 下会渲染一张没有改写内容的页面。
        listOf("inviting", "accepted", "inputting").forEach { status ->
            val step = MediationFlow.afterSubmit(status)
            assertFalse("$status 不该进确认页", step == MediationStep.CONFIRM)
            assertFalse("$status 不该进结果页", step == MediationStep.RESULT)
        }
    }

    @Test
    fun `未知状态按最保守的等待处理`() {
        assertEquals(MediationStep.WAITING_PARTNER, MediationFlow.afterSubmit("weird_status"))
    }

    // ------------------------------------------------------------------ #
    // 整改 B4.1-4 失败态：必须离开等待，且落到**有出路**的页面
    // ------------------------------------------------------------------ #

    @Test
    fun `改写失败不再留在等待态`() {
        // 回归：`rewrite_failed` 此前没有分支，落进 else → 和「对方还没写完」
        // 走同一条路。页面继续显示「等对方写下 TA 的感受」，而服务端其实已经停了
        // ——用户等的是一个永远不会来的改写。这是「失败伪装成一直 processing」。
        assertFalse(
            "失败绝不能落成等待",
            MediationFlow.afterSubmit("rewrite_failed") == MediationStep.WAITING_PARTNER,
        )
        assertFalse(
            MediationFlow.afterSubmit("rewrite_failed") == MediationStep.WAITING_REWRITE,
        )
        assertEquals(MediationStep.FAILED_REWRITE, MediationFlow.afterSubmit("rewrite_failed"))
        assertEquals(MediationStep.FAILED_SUMMARY, MediationFlow.afterSubmit("summary_failed"))
    }

    @Test
    fun `等待循环读到失败态立刻交还用户`() {
        assertEquals(MediationStep.FAILED_REWRITE, MediationFlow.whileWaiting("rewrite_failed"))
        assertEquals(MediationStep.FAILED_SUMMARY, MediationFlow.whileWaiting("summary_failed"))
    }

    @Test
    fun `失败落点必须是能给出重试的那一页`() {
        // FAILED_REWRITE → 确认页（MediationConfirmScreen 带失败卡片与「重试」）；
        // FAILED_SUMMARY → 结果页（同样带失败卡片与「重试」）。
        // 不新开一个「失败页」：失败不是流程里的新一步，是同一步的另一种形态。
        assertEquals(MediationStep.FAILED_REWRITE, MediationFlow.afterSubmit("rewrite_failed"))
        assertEquals(MediationStep.FAILED_SUMMARY, MediationFlow.afterSubmit("summary_failed"))
    }

    @Test
    fun `总结失败时人也该被送到结果页而不是干等`() {
        // 双方都确认过了 → 服务端进 summarizing → 失败。人此刻停在确认页上
        // 只会看到「双方都确认了」的既成事实，出路在结果页（那里有重试）。
        assertEquals(
            MediationStep.RESULT,
            MediationFlow.afterConfirm("summary_failed", myConfirmed = true, partnerConfirmed = true),
        )
    }

    @Test
    fun `失败态不是生成中`() {
        // 生成中的集合必须**不含**失败：把它算作"生成中"就等于允许客户端
        // 无限轮询一个已经停下来的状态。
        assertFalse("rewrite_failed" in MediationFlow.GENERATING_STATUSES)
        assertFalse("summary_failed" in MediationFlow.GENERATING_STATUSES)
        assertEquals(setOf("rewrite_failed", "summary_failed"), MediationFlow.FAILURE_STATUSES)
        // 失败态也不该被判成「还没到结果」而一直轮询
        assertFalse(MediationFlow.resultReady("rewrite_failed"))
        assertFalse(MediationFlow.resultReady("summary_failed"))
    }

    // ------------------------------------------------------------------ #
    // §8.5-1 / §8.5-7 等待中：按服务端状态推进，重进可恢复
    // ------------------------------------------------------------------ #

    @Test
    fun `对方接受后双方各自去写自己的部分`() {
        assertEquals(MediationStep.INPUT, MediationFlow.whileWaiting("accepted"))
        assertEquals(MediationStep.INPUT, MediationFlow.whileWaiting("inputting"))
    }

    @Test
    fun `对方还没回应时停在邀请页`() {
        assertEquals(MediationStep.STAY, MediationFlow.whileWaiting("inviting"))
    }

    @Test
    fun `等待期间对方一路走完也能一次跳到位`() {
        // 断线/离开期间对方写完了、双方确认完了——重进不该把我丢回输入页
        assertEquals(MediationStep.RESULT, MediationFlow.whileWaiting("completed"))
        assertEquals(MediationStep.CONFIRM, MediationFlow.whileWaiting("confirming"))
    }

    @Test
    fun `仍然邀请中才需要继续轮询`() {
        assertTrue(MediationFlow.stillInviting("inviting"))
        listOf("accepted", "inputting", "rewriting", "confirming", "summarizing", "completed")
            .forEach { assertFalse("$it 不该继续等 invite", MediationFlow.stillInviting(it)) }
    }

    // ------------------------------------------------------------------ #
    // §8.5-5 确认：按人计，双方齐了才有总结
    // ------------------------------------------------------------------ #

    @Test
    fun `只有我确认时停在等待对方确认`() {
        assertEquals(
            MediationStep.WAITING_PARTNER_CONFIRM,
            MediationFlow.afterConfirm("confirming", myConfirmed = true, partnerConfirmed = false),
        )
    }

    @Test
    fun `只有对方确认时我不该被推走`() {
        // 对方点了确认、我还没点：留在确认页让我自己表态，不能替我做决定
        assertEquals(
            MediationStep.STAY,
            MediationFlow.afterConfirm("confirming", myConfirmed = false, partnerConfirmed = true),
        )
    }

    @Test
    fun `双方都确认才进结果页`() {
        assertEquals(
            MediationStep.RESULT,
            MediationFlow.afterConfirm("summarizing", myConfirmed = true, partnerConfirmed = true),
        )
    }

    @Test
    fun `单人确认加生成中不得当成双方确认的结果`() {
        // 回归：曾经「我确认了 → 服务端开始生成总结」就被当成走完，
        // 于是用户看到一份对方从没确认过的总结。
        assertEquals(
            MediationStep.WAITING_PARTNER_CONFIRM,
            MediationFlow.afterConfirm("confirming", myConfirmed = true, partnerConfirmed = false),
        )
    }

    @Test
    fun `服务端已进总结或已完成时确认页不再拦人`() {
        // 断线重进：对方早已确认、总结都生成好了，我停在确认页就错了
        assertEquals(
            MediationStep.RESULT,
            MediationFlow.afterConfirm("summarizing", myConfirmed = false, partnerConfirmed = false),
        )
        assertEquals(
            MediationStep.RESULT,
            MediationFlow.afterConfirm("completed", myConfirmed = false, partnerConfirmed = false),
        )
    }

    // ------------------------------------------------------------------ #
    // §8.5-8 生成中：客户端只等待，不报错也不空白
    // ------------------------------------------------------------------ #

    @Test
    fun `生成中的两个状态都被识别为生成中`() {
        assertEquals(setOf("rewriting", "summarizing"), MediationFlow.GENERATING_STATUSES)
    }

    @Test
    fun `只有 completed 才算真的可以回看`() {
        assertTrue(MediationFlow.resultReady("completed"))
        listOf("inviting", "accepted", "inputting", "rewriting", "confirming", "summarizing")
            .forEach { assertFalse("$it 还不算结果就绪", MediationFlow.resultReady(it)) }
    }
}
