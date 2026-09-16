package com.couple.translator.feature.couple.network

import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.notification.AppNotifications

/**
 * 实时事件 → 界面表现 的唯一映射表。
 *
 * 一处定义、两处消费：
 * - 前台：[RealtimeNotice.snackbarText] 弹 Snackbar；
 * - 后台/前台都算：有 [RealtimeNotice.notificationId] 的再往系统通知栏发一条。
 *
 * 为什么要收口：同一条事件的中文文案与跳转目标如果分散在两个地方写，
 * 改文案时会漏改一处，用户就会看到"Snackbar 说收到信、点通知却跳到调解页"这种错位。
 *
 * 什么事件不进通知栏（刻意为之）：
 * - `partner_moment`（对方分享了此刻状态）——高频、无回应义务，进通知栏会把提醒变成噪音；
 * - `letter_read` / `unbind_confirmed` 等结果类事件——用户本人是发起方，App 内提示足够。
 */
data class RealtimeNotice(
    val snackbarText: String,
    val notificationId: Int? = null,
    val notificationTitle: String? = null,
    val notificationText: String? = null,
    val route: String? = null,
)

fun RealtimeEvent.toNotice(): RealtimeNotice? = when (notificationType) {

    "letter_received" -> RealtimeNotice(
        snackbarText = "收到一封新信件",
        notificationId = AppNotifications.ID_LETTER_RECEIVED,
        notificationTitle = "收到一封新信件",
        notificationText = "对方给你写了一封信，点开看看",
        // 带上 letter_id 就能直达那一封（信件详情挂在**根 NavHost** 上，深链够得着）；
        // 老服务端不给 id 时退回信件列表——用户自己找得到，比"点了没反应"强。
        route = letterId?.takeIf { it > 0L }
            ?.let { "${Screen.LetterDetail.route}/$it" }
            ?: Screen.LetterList.route,
    )

    "mediation_invite" -> RealtimeNotice(
        snackbarText = "对方邀请你进行冷静沟通",
        notificationId = AppNotifications.ID_MEDIATION_INVITE,
        notificationTitle = "冷静沟通邀请",
        notificationText = "对方邀请你进行一次冷静沟通，点开回应",
        // sessionId 为 0 时说明服务端没给（老版本），此时不带 id 也能进页面，
        // 页面会按自己的逻辑兜底，比"点了没反应"强。
        route = "${Screen.MediationInvite.route}?sessionId=${sessionId ?: 0L}&isInviter=false",
    )

    "unbind_requested" -> RealtimeNotice(
        snackbarText = "对方发起了关系解绑请求",
        notificationId = AppNotifications.ID_UNBIND_REQUESTED,
        notificationTitle = "关系解绑请求",
        notificationText = "对方发起了关系解绑请求，点开查看详情",
        route = Screen.CoupleInfo.route,
    )

    "companion_request" -> RealtimeNotice(
        snackbarText = "对方发来了陪伴请求" + (content?.let { "：$it" } ?: ""),
        notificationId = AppNotifications.ID_COMPANION_REQUEST,
        notificationTitle = "对方发来了陪伴请求",
        notificationText = content ?: "对方想让你陪一会儿",
        // 陪伴请求的落点是首页那张在场感卡片，而首页属于情侣模式外壳的内层导航，
        // 根导航图够不着，所以不带路由——点开 App 即为正确落点。
        route = null,
    )

    "partner_moment" -> RealtimeNotice(
        snackbarText = "对方分享了此刻状态" + (content?.let { "：$it" } ?: ""),
    )

    "unbind_confirmed" -> RealtimeNotice(snackbarText = "关系解绑已完成")

    "unbind_cancelled" -> RealtimeNotice(snackbarText = "对方取消了解绑申请")

    else -> null
}
