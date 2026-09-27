package com.couple.translator.feature.couple.network

import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.notification.AppNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时事件 → Snackbar / 系统通知 的唯一映射表测试。
 *
 * 这张表的价值全在「一处定义、两处消费」：文案与跳转目标写偏一格，
 * 用户就会看到"Snackbar 说收到信、点通知却跳到调解页"。分支多且都是字符串，
 * 改文案时极易漏改，所以每个分支都钉一条。
 */
class RealtimeNoticeTest {

    private fun event(
        type: String,
        content: String? = null,
        letterId: Long? = null,
        sessionId: Long? = null,
        eventId: Long? = null,
    ) = RealtimeEvent(
        notificationType = type,
        content = content,
        letterId = letterId,
        sessionId = sessionId,
        eventId = eventId,
    )

    // ---------- 收信 ----------

    @Test
    fun `收信带 letter_id 时直达那一封`() {
        val notice = event("letter_received", letterId = 9L).toNotice()!!
        assertEquals("收到一封新信件", notice.snackbarText)
        assertEquals(AppNotifications.ID_LETTER_RECEIVED, notice.notificationId)
        assertEquals("${Screen.LetterDetail.route}/9", notice.route)
    }

    @Test
    fun `收信缺 letter_id 时退回深度表达信箱`() {
        assertEquals(
            BottomTab.Mailbox.route,
            event("letter_received").toNotice()!!.route,
        )
        // 老服务端可能给 0 而不是不给
        assertEquals(
            BottomTab.Mailbox.route,
            event("letter_received", letterId = 0L).toNotice()!!.route,
        )
    }

    @Test
    fun `收信通知文案里不带信件内容`() {
        // data.content 只在陪伴请求里用；收信事件必须完全忽略它，
        // 否则信件摘要会明文出现在锁屏通知栏上
        val notice = event("letter_received", content = "这是一封很私密的信").toNotice()!!
        assertFalse(notice.notificationText!!.contains("很私密"))
        assertFalse(notice.notificationTitle!!.contains("很私密"))
    }

    // ---------- 调解邀请 ----------

    @Test
    fun `调解邀请带上会话 id 与受邀者标记`() {
        val route = event("mediation_invite", sessionId = 77L).toNotice()!!.route!!
        assertTrue(route.startsWith(Screen.MediationInvite.route))
        assertTrue(route.contains("sessionId=77"))
        assertTrue(route.contains("isInviter=false"))
    }

    @Test
    fun `调解邀请缺会话 id 时兜底为 0`() {
        // 带个 0 进去，页面自己能兜底；不带 id 会连页面都进不去
        assertTrue(event("mediation_invite").toNotice()!!.route!!.contains("sessionId=0"))
    }

    // ---------- 解绑 ----------

    @Test
    fun `解绑请求通知栏可达并跳关系信息页`() {
        val notice = event("unbind_requested").toNotice()!!
        assertEquals(AppNotifications.ID_UNBIND_REQUESTED, notice.notificationId)
        assertEquals(Screen.CoupleInfo.route, notice.route)
    }

    // ---------- 陪伴请求 ----------

    @Test
    fun `陪伴请求带上对方留言`() {
        val notice = event("companion_request", content = "陪我待一会儿").toNotice()!!
        assertTrue(notice.snackbarText.contains("陪我待一会儿"))
        assertEquals("陪我待一会儿", notice.notificationText)
        // 落点在情侣外壳的内层导航里，根导航图够不着 → 不带路由，
        // 点开 App 即为正确落点（首页那张在场感卡片）
        assertNull(notice.route)
    }

    @Test
    fun `陪伴请求没带留言时用兜底文案`() {
        val notice = event("companion_request").toNotice()!!
        assertFalse(notice.snackbarText.endsWith("："))
        assertEquals("对方想让你陪一会儿", notice.notificationText)
    }

    // ---------- 双视角邀请（整改 §8.6） ----------

    @Test
    fun `双视角邀请带事件 id 时直达那件事`() {
        // §8.0：「能返回」——对方收到邀请后必须点得到那件事本身，
        // 否则只能自己在列表里翻。event_id 与 letter_id/session_id 是同一套约定。
        val notice = event("dual_invite", content = "我想听听你怎么想的", eventId = 88L).toNotice()!!
        assertEquals(AppNotifications.ID_DUAL_INVITE, notice.notificationId)
        assertEquals("${Screen.DualPerspectiveDetail.route}/88", notice.route)
    }

    @Test
    fun `双视角邀请缺事件 id 时退回列表页而不是点了没反应`() {
        val notice = event("dual_invite").toNotice()!!
        assertEquals(Screen.DualPerspectiveList.route, notice.route)
        // 老服务端可能给 0 而不是不给
        assertEquals(Screen.DualPerspectiveList.route, event("dual_invite", eventId = 0L).toNotice()!!.route)
    }

    @Test
    fun `双视角邀请的邀请语进文案但绝不进正文字段`() {
        // 邀请语是发起人写给伴侣看的话；双方各自的**视角正文**由服务端过滤把守，
        // 不可能进通知。这里钉住的是「别把 content 当成视角内容来展示」。
        val notice = event("dual_invite", content = "我想听听你怎么想的").toNotice()!!
        assertTrue(notice.snackbarText.contains("我想听听你怎么想的"))
        assertEquals("我想听听你怎么想的", notice.notificationText)
    }

    @Test
    fun `双视角邀请没带邀请语时用兜底文案`() {
        val notice = event("dual_invite").toNotice()!!
        assertFalse(notice.snackbarText.endsWith("："))
        assertTrue(notice.notificationText!!.isNotBlank())
    }

    // ---------- 刻意不进通知栏的事件 ----------

    @Test
    fun `此刻状态只弹 Snackbar 不进通知栏`() {
        // 高频且无回应义务，进通知栏会把提醒变成噪音
        val notice = event("partner_moment", content = "在路上").toNotice()!!
        assertNull(notice.notificationId)
        assertTrue(notice.snackbarText.contains("在路上"))
    }

    @Test
    fun `结果类事件只弹 Snackbar`() {
        assertNull(event("unbind_confirmed").toNotice()!!.notificationId)
        assertNull(event("unbind_cancelled").toNotice()!!.notificationId)
    }

    // ---------- 容错 ----------

    @Test
    fun `未知事件返回 null 不弹任何东西`() {
        assertNull(event("some_future_event").toNotice())
    }

    @Test
    fun `有通知栏的分支文案都不为空`() {
        val types = listOf(
            "letter_received", "mediation_invite", "unbind_requested", "companion_request",
            "dual_invite",
        )
        for (type in types) {
            val notice = event(type).toNotice() ?: error("$type 应有序表")
            assertTrue("$type 缺通知标题", !notice.notificationTitle.isNullOrBlank())
            assertTrue("$type 缺通知正文", !notice.notificationText.isNullOrBlank())
            assertTrue("$type 缺 Snackbar 文案", notice.snackbarText.isNotBlank())
        }
    }

    @Test
    fun `五个通知 id 两两不同`() {
        // 同 id 的新通知会覆盖旧的（连收三封信在通知栏里只留一条）；
        // id 撞车则会让两类事件互相覆盖
        val ids = listOf(
            AppNotifications.ID_LETTER_RECEIVED,
            AppNotifications.ID_MEDIATION_INVITE,
            AppNotifications.ID_UNBIND_REQUESTED,
            AppNotifications.ID_COMPANION_REQUEST,
            AppNotifications.ID_DUAL_INVITE,
        )
        assertEquals(ids.size, ids.toSet().size)
    }
}
