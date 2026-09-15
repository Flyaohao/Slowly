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
 */
data class RealtimeEvent(
    val notificationType: String,
    val content: String?,
)

/**
 * 实时通道（WebSocket）客户端管理器。
 *
 * 服务端端点：/api/v1/couple/ai/ws?token=<access_token>
 * 协议约定（与后端 app/api/v1/couple/ws.py 对齐）：
 * - 服务端 30s 静默后下发 {"type":"ping"}，客户端必须回 {"type":"pong"}，
 *   60s 内不回即判死连接
 * - 服务端事件帧：{"type":"notification","notification_type":"...","data":{...}}
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
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (obj.optString("type")) {
            "ping" -> webSocket.send("{\"type\":\"pong\"}")
            "notification" -> {
                val nt = obj.optString("notification_type")
                if (nt.isNotBlank()) {
                    val content = obj.optJSONObject("data")?.optString("content")
                    _events.tryEmit(RealtimeEvent(notificationType = nt, content = content))
                }
            }
        }
    }
}
