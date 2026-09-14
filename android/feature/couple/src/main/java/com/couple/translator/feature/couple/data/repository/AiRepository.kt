package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.data.model.AiDto
import com.couple.translator.feature.couple.network.CoupleApiService
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 翻译官数据仓库。
 *
 * 2026-09-14 从 core/data/repository 迁到本模块：
 * AI 翻译官只存在于情侣模式，接口前缀是 `api/v1/couple/ai`。
 * 留在 core 会形成「共享层调用情侣专属接口」的越层依赖，且撞上了 v2.0 漏改路径的坑。
 */
@Singleton
class AiRepository @Inject constructor(
    private val apiService: CoupleApiService,
    private val moshi: Moshi,
) {

    private val metaAdapter by lazy { moshi.adapter(AiDto.StreamMetaPayload::class.java) }
    private val deltaAdapter by lazy { moshi.adapter(AiDto.StreamDeltaPayload::class.java) }
    private val thinkingAdapter by lazy { moshi.adapter(AiDto.StreamThinkingPayload::class.java) }
    private val doneAdapter by lazy { moshi.adapter(AiDto.StreamDonePayload::class.java) }
    private val errorAdapter by lazy { moshi.adapter(AiDto.StreamErrorPayload::class.java) }

    /**
     * 拉取后端场景清单。
     *
     * 场景清单过去是客户端硬编码的（且散落 5 处、彼此不同步），
     * 导致后端新增场景客户端永远不知道。现改为以后端 `GET /ai/scenes` 为准。
     */
    suspend fun getScenes(): Result<List<AiDto.SceneResponse>> {
        return try {
            val response = apiService.getAiScenes()
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun chat(request: AiDto.ChatRequest): Result<AiDto.ChatResponse> {
        return try {
            val response = apiService.aiChat(request)
            // ApiResponse 定义在 core 模块，跨模块无法对属性做智能转换，先取到局部变量
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * SSE 流式对话。
     *
     * 为什么直接读 okio 的行而不是用 EventSource 之类的库：
     * 服务端报文是极简的三行帧（`event:` / `data:` / 空行），
     * 自己解析既能保证「收到即 emit」的实时性，也避免再引一个依赖。
     *
     * 注意 `body.use { }` 里的 `emit` 之所以合法，是因为 `use` 是 inline 函数，
     * 且整个 flow 构建块本身处于 suspend 上下文。
     */
    fun chatStream(request: AiDto.ChatRequest): Flow<AiDto.ChatStreamEvent> = flow {
        val response = try {
            apiService.aiChatStream(request)
        } catch (e: Exception) {
            emit(AiDto.ChatStreamEvent.Failure(50000, "网络异常：${e.message ?: "连接失败"}"))
            return@flow
        }

        if (!response.isSuccessful) {
            emit(AiDto.ChatStreamEvent.Failure(response.code(), httpHint(response.code())))
            return@flow
        }

        val body = response.body()
        if (body == null) {
            emit(AiDto.ChatStreamEvent.Failure(50000, "服务端返回空响应"))
            return@flow
        }

        body.use { rb ->
            val source = rb.source()
            var eventName: String? = null
            val dataBuf = StringBuilder()

            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    // 空行 = 一帧结束，此时才派发
                    line.isEmpty() -> {
                        val name = eventName
                        if (name != null) {
                            // decode 可能返回 null（该帧无需派发），此时静默跳过
                            decode(name, dataBuf.toString())?.let { emit(it) }
                        }
                        eventName = null
                        dataBuf.setLength(0)
                    }

                    line.startsWith("event:") -> eventName = line.substring(6).trim()
                    line.startsWith("data:") -> dataBuf.append(line.substring(5).trim())
                }
            }
        }
    }.catch { e ->
        // 连接中断也要收敛成终态事件，否则 UI 的 isStreaming 永远为 true
        emit(AiDto.ChatStreamEvent.Failure(50000, "流式连接中断：${e.message ?: "未知错误"}"))
    }.flowOn(Dispatchers.IO)

    suspend fun getSessions(): Result<List<AiDto.SessionResponse>> {
        return try {
            val response = apiService.getAiSessions()
            // ApiResponse 定义在 core 模块，跨模块无法对属性做智能转换，先取到局部变量
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessionMessages(sessionId: Long): Result<List<AiDto.MessageResponse>> {
        return try {
            val response = apiService.getSessionMessages(sessionId)
            // ApiResponse 定义在 core 模块，跨模块无法对属性做智能转换，先取到局部变量
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitFeedback(
        sessionId: Long,
        feedback: AiDto.FeedbackRequest,
    ): Result<Unit> {
        return try {
            val response = apiService.submitFeedback(sessionId, feedback)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSession(sessionId: Long): Result<Unit> {
        return try {
            val response = apiService.deleteSession(sessionId)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rewriteExpression(text: String, context: String? = null): Result<AiDto.RewriteResponse> {
        return try {
            val response = apiService.rewriteExpression(AiDto.RewriteRequest(text, context))
            // ApiResponse 定义在 core 模块，跨模块无法对属性做智能转换，先取到局部变量
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 把一帧 SSE 报文解码成客户端事件。
     *
     * 返回 `null` 表示该帧**不需要派发给 UI**（目前只有解析失败的 thinking 帧，
     * 它只是过程展示，丢了不影响正确性，没必要因此中断整条流）。
     */
    private fun decode(event: String, json: String): AiDto.ChatStreamEvent? {
        return try {
            when (event) {
                "meta" -> {
                    val p = metaAdapter.fromJson(json)
                    if (p == null) AiDto.ChatStreamEvent.Failure(50000, "meta 帧解析失败")
                    else AiDto.ChatStreamEvent.Meta(p.sessionId, p.sceneKey, p.ragHit)
                }

                "delta" -> {
                    val p = deltaAdapter.fromJson(json)
                    if (p == null) AiDto.ChatStreamEvent.Failure(50000, "delta 帧解析失败")
                    else AiDto.ChatStreamEvent.Delta(p.content)
                }

                "thinking" -> {
                    val p = thinkingAdapter.fromJson(json)
                    // 思考帧解析失败不该中断整条流：它只是过程展示，丢掉即可
                    if (p == null) null else AiDto.ChatStreamEvent.Thinking(p.content)
                }

                "done" -> {
                    val p = doneAdapter.fromJson(json)
                    if (p == null) AiDto.ChatStreamEvent.Failure(50000, "done 帧解析失败")
                    else AiDto.ChatStreamEvent.Done(
                        sessionId = p.sessionId,
                        messageId = p.messageId,
                        riskLevel = p.riskLevel,
                        blocked = p.blocked,
                        content = p.content,
                    )
                }

                "error" -> {
                    val p = errorAdapter.fromJson(json)
                    AiDto.ChatStreamEvent.Failure(
                        p?.code ?: 50000,
                        p?.message ?: "AI 服务异常，请稍后重试",
                    )
                }

                else -> AiDto.ChatStreamEvent.Failure(50000, "未知事件帧：$event")
            }
        } catch (e: Exception) {
            AiDto.ChatStreamEvent.Failure(50000, "流式数据解析失败：${e.message}")
        }
    }

    private fun httpHint(code: Int): String = when (code) {
        400 -> "请求被拒绝，请检查是否已绑定情侣"
        401 -> "登录已过期，请重新登录"
        403 -> "无权访问该会话"
        404 -> "接口不存在，服务端版本可能偏低"
        422 -> "请求参数不合法"
        else -> "服务异常（HTTP $code）"
    }
}
