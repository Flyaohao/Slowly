package com.couple.translator.feature.couple.network

import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.network.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实时事件（服务端经 WS 推来的 notification 帧的瘦身投影）。
 *
 * notification_type 对应后端 NotificationType 的取值：
 * unbind_requested / unbind_confirmed / letter_received / mediation_invite /
 * partner_moment / companion_request / system_notice ...
 *
 * 除事件类型本身外，只额外保留三个**跳转必需**的标识（信件 id、调解会话 id、
 * 双视角事件 id）：系统通知的点击跳转要用到它们，而 data 里的其余字段
 * （发送者昵称、信件标题等）是纯展示内容，不该进通知（隐私），所以不往这里搬。
 */
data class RealtimeEvent(
    val notificationType: String,
    val content: String?,
    val letterId: Long? = null,
    val sessionId: Long? = null,
    /** 整改 §8.6：双视角事件 id，用于把「邀请 TA 一起写」的通知点到那件事上。 */
    val eventId: Long? = null,
)

/**
 * 调解状态帧（契约 §2.3-4）：服务端在会话状态变更时推
 * `{"type":"mediation_status","session_id":N,"status":"..."}`，动作本身仍走 REST。
 *
 * 客户端此前**收到就丢**（`when` 里没有这一支），于是调解各页只能靠自己轮询，
 * 服务端明明推了状态、界面却不响应——§8.5-1「发起方等待页必须能获知对方接受」
 * 也就落不了地。这里把它接出来，轮询退居兜底。
 */
data class MediationStatusFrame(val sessionId: Long, val status: String)

/** 入站帧的解析结果（纯数据，便于单测；见 [parseIncomingFrame]）。 */
internal sealed interface IncomingFrame {
    /** 服务端心跳，须回 pong。 */
    data object Ping : IncomingFrame
    /** 业务通知帧。 */
    data class Notification(val event: RealtimeEvent) : IncomingFrame
    /** 调解状态帧。 */
    data class MediationStatus(val sessionId: Long, val status: String) : IncomingFrame
    /** 与本端无关或解析不出内容的帧。 */
    data object Ignored : IncomingFrame
}

/**
 * 解析一帧入站文本。抽成纯函数是**为了能单测**：解析错一格的代价是
 * 「通知弹出来了但点进去落错页」或者「状态帧被静默丢弃」，
 * 而后者（调解状态帧）在修复前正是无人察觉的静默失败。
 */
internal fun parseIncomingFrame(text: String): IncomingFrame {
    val obj = try {
        JSONObject(text)
    } catch (_: Exception) {
        return IncomingFrame.Ignored
    }
    return when (obj.optString("type")) {
        "ping" -> IncomingFrame.Ping
        "notification" -> {
            val nt = obj.optString("notification_type")
            if (nt.isBlank()) {
                IncomingFrame.Ignored
            } else {
                val data = obj.optJSONObject("data")
                IncomingFrame.Notification(
                    RealtimeEvent(
                        notificationType = nt,
                        content = data?.optString("content"),
                        letterId = data?.optLong("letter_id")?.takeIf { it > 0L },
                        sessionId = data?.optLong("session_id")?.takeIf { it > 0L },
                        eventId = data?.optLong("event_id")?.takeIf { it > 0L },
                    )
                )
            }
        }
        "mediation_status" -> {
            val sid = obj.optLong("session_id")
            val status = obj.optString("status")
            if (sid > 0L && status.isNotBlank()) {
                IncomingFrame.MediationStatus(sid, status)
            } else {
                IncomingFrame.Ignored
            }
        }
        else -> IncomingFrame.Ignored
    }
}

/**
 * 实时通道（WebSocket）客户端管理器。
 *
 * 服务端端点：/api/v1/couple/ai/ws?token=<access_token>
 * 协议约定（与后端 app/api/v1/couple/ws.py 对齐）：
 * - 服务端 30s 静默后下发 {"type":"ping"}，客户端必须回 {"type":"pong"}，
 *   60s 内不回即判死连接
 * - 服务端事件帧：{"type":"notification","notification_type":"...","data":{...}}
 * - 调解状态帧：{"type":"mediation_status","session_id":N,"status":"..."}
 *
 * 生命周期：进入情侣模式（CoupleShell 挂载）时 start，退出/注销时 stop。
 * 断线自动重连（指数退避 5s→60s 封顶，连接成功即复位）；App 切后台不断开——
 * 服务端会持续清理死连接，重连成本低于反复握手。
 */
@Singleton
class RealtimeSocketManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _statusFrames = MutableSharedFlow<MediationStatusFrame>(extraBufferCapacity = 16)
    val statusFrames: SharedFlow<MediationStatusFrame> = _statusFrames.asSharedFlow()

    @Volatile
    private var started = false

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var attempt = 0

    fun start() {
        if (started) return
        started = true
        scope.launch { connectLoop() }
    }

    fun stop() {
        started = false
        socket?.close(1000, "client stop")
        socket = null
    }

    private suspend fun connectLoop() {
        while (started) {
            val token = tokenStore.getToken()
            if (token.isNullOrBlank()) {
                // 未登录：不连也不重试，等下次进入情侣模式再 start
                started = false
                return
            }

            val url = NetworkModule.DEFAULT_BASE_URL
                .replace("http", "ws")   // http→ws，https→wss
                .trimEnd('/') + "/api/v1/couple/ai/ws?token=$token"

            val request = Request.Builder().url(url).build()
            val connected = kotlinx.coroutines.CompletableDeferred<Boolean>()

            socket = okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        attempt = 0
                        connected.complete(true)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text, webSocket)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connected.complete(false)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connected.complete(false)
                    }
                },
            )

            // 等本次连接结束（成功建立后由 onFailure/onClosed 唤醒）
            connected.await()
            socket = null

            if (!started) return
            attempt += 1
            val backoffMs = (5000L * attempt).coerceAtMost(60_000L)
            delay(backoffMs)
        }
    }

    private fun handleMessage(text: String, webSocket: WebSocket) {
        when (val frame = parseIncomingFrame(text)) {
            IncomingFrame.Ping -> webSocket.send("{\"type\":\"pong\"}")
            is IncomingFrame.Notification -> _events.tryEmit(frame.event)
            is IncomingFrame.MediationStatus ->
                _statusFrames.tryEmit(MediationStatusFrame(frame.sessionId, frame.status))
            IncomingFrame.Ignored -> Unit
        }
    }
}
