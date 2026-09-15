package com.couple.translator.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 流式生成期间的保活前台服务。
 *
 * **为什么需要它**：流式响应的生命周期绑定在 viewModelScope 上，代码本身
 * 不会在退后台时取消；真正掐断连接的是系统——APP 一退到后台，进程会被
 * 冻结（Cached Apps Freezer）或被厂商 ROM（MIUI 等）限网，socket 读不动，
 * 服务端 2s 心跳写不出去就按「客户端断连」收尾，生成被标记为 interrupted。
 *
 * 解法是生成期间把进程顶到前台优先级：挂一个 dataSync 类型的前台服务，
 * 系统既不冻结进程也不限制网络，流就能在后台风跑完。生成结束即停，
 * 不常驻、不耗电。
 */
class AiStreamKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // targetSdk 34+ 必须显式声明类型，否则 startForeground 直接抛异常
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI 生成",
            NotificationManager.IMPORTANCE_LOW, // 低优先级：不响铃不弹横幅，只在通知栏挂着
        )
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI 正在生成")
            .setContentText("内容生成中，切到后台也不会中断")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.couple.translator.action.STREAM_KEEPALIVE_STOP"
        private const val CHANNEL_ID = "ai_stream_keepalive"
        private const val NOTIFICATION_ID = 1001

        /** 服务是否活着。作为 [AiStreamKeepAlive] 的启停判据，避免重复 startForegroundService。 */
        val running = AtomicBoolean(false)
    }
}

/**
 * 保活服务的启停入口。ViewModel 持有 applicationContext 调这两个方法即可，
 * 不直接碰 Service 类型的 Intent 细节。
 */
object AiStreamKeepAlive {

    /**
     * 开启保活。幂等：服务已在跑就不会再发一次 start 命令。
     *
     * 注意 startForegroundService 有「必须在前台调用」的限制——好在所有
     * 流式生成都是用户点按钮触发的，调用时机天然在前台。
     */
    fun start(context: Context) {
        if (AiStreamKeepAliveService.running.get()) return
        val app = context.applicationContext
        try {
            app.startForegroundService(Intent(app, AiStreamKeepAliveService::class.java))
        } catch (_: Exception) {
            // 极端情况下（系统拒绝后台启动等）保活失败不应影响生成本身，
            // 最坏结果只是退后台会中断，与没有这个服务时一致。
        }
    }

    /** 停止保活并撤掉通知。生成结束/中断/用户叫停时都要调。 */
    fun stop(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, AiStreamKeepAliveService::class.java))
    }
}
