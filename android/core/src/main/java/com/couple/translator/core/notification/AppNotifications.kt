package com.couple.translator.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 业务通知（系统通知栏）。
 *
 * 这是「通知双通道」的方案①：App 进程存活时，收到服务端的 WebSocket 事件后
 * 往通知栏弹一条，点开直达对应页面。零第三方依赖，全是系统 API。
 *
 * 与 [com.couple.translator.core.service.AiStreamKeepAliveService] 的关系：
 * 那个服务自己也建了一个渠道，但那是 `IMPORTANCE_LOW` 的保活占位（不响铃、
 * 不弹横幅，只为把进程顶到前台优先级），语义完全不同，所以渠道**必须分开**——
 * 否则用户关掉"AI 生成"的角标就顺手把业务通知一起关了。
 *
 * ⚠️ 边界（Android 机制，不是实现缺陷）：通知权限只决定"能不能弹"，
 * 不决定"消息从哪来"。消息来自 WebSocket，而 WebSocket 需要 App 进程活着。
 * 因此 App 被划掉 / 进程被回收后这里收不到任何东西——那条路只能靠邮件通道。
 */
object AppNotifications {

    /** 业务通知渠道：默认重要性，会响铃、会弹横幅，用户可在系统设置里单独调 */
    const val CHANNEL_ID = "app_business_events"

    /** 通知里携带的目标路由，MainActivity 读它做深链跳转 */
    const val EXTRA_ROUTE = "com.couple.translator.extra.DEEPLINK_ROUTE"

    /** 通知分组：同组通知在通知栏折叠，避免连收几封信把状态栏刷满 */
    private const val GROUP_KEY = "com.couple.translator.BUSINESS_EVENTS"

    /**
     * 建渠道。**必须在每次发通知前调用**（幂等）：
     * 渠道只影响之后的通知，App 升级后新增渠道若不重建就不生效。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "重要提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "收到信件、调解邀请、解绑请求时提醒你"
            }
        )
    }

    /**
     * 现在能不能弹通知。
     * - Android 13+：看运行时权限 POST_NOTIFICATIONS
     * - 更低版本：看用户在系统设置里是否关掉了本应用的通知开关
     */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 弹一条业务通知。
     *
     * @param notificationId 同一 id 的新通知会覆盖旧的——按事件类型分配固定 id，
     *        这样"对方连发三封信"在通知栏里始终是那一条，而不是三条。
     * @param route 点击后要跳转的路由（可带参数）；为 null 时只是打开 App。
     */
    fun notify(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        route: String? = null,
    ) {
        ensureChannel(context)
        if (!canPost(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(launchIntent(context, notificationId, route))
            .build()

        // 权限刚被撤销、或通知被系统拦截时 notify 会抛 SecurityException，
        // 这只是"少了一条提醒"，绝不能让它把 UI 事件流打断。
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    /** 撤掉某一类通知（例如用户刚看过信件，通知栏那条就没有意义了） */
    fun cancel(context: Context, notificationId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
    }

    /**
     * 构造「点击通知 → 打开 App 并跳到 route」的 PendingIntent。
     *
     * 用 `getLaunchIntentForPackage` 拿启动 Intent，而不是直接引用 MainActivity：
     * 这个类在 :core，MainActivity 在 :app，编译期拿不到那边的类型。
     * 顺带也更稳——启动 Intent 一定是这个包真正的入口。
     */
    private fun launchIntent(context: Context, requestCode: Int, route: String?): PendingIntent? {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                // SINGLE_TOP + CLEAR_TOP：App 在前台时复用已有 Activity 走 onNewIntent，
                // 不会把用户当前的页面栈推倒重来
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (route != null) putExtra(EXTRA_ROUTE, route)
            } ?: return null

        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---- 事件 → 通知 id 的固定映射 ----
    // 用常量而不用 hashCode()：hashCode 在进程间不保证一致，重启后同一类事件
    // 会算出不同 id，通知栏就会堆出一串重复条目。

    const val ID_LETTER_RECEIVED = 2001
    const val ID_MEDIATION_INVITE = 2002
    const val ID_UNBIND_REQUESTED = 2003
    const val ID_COMPANION_REQUEST = 2004
}
