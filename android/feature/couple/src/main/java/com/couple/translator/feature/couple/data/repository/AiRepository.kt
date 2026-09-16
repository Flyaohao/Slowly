package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.network.GenerationStreamDecoder
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.network.generationStreamFlow
import com.couple.translator.core.network.sseFrames
import com.couple.translator.feature.couple.network.CoupleApiService
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
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

    /** 表达改写 / 画像报告等新流式端点共用的通用解码器（ai_generation 协议） */
    private val generationDecoder = GenerationStreamDecoder(moshi)
    private val reviewAdapter by lazy { moshi.adapter(AiDto.ReviewResult::class.java) }
    private val mapAdapter by lazy { moshi.adapter(Map::class.java) }

    /**
     * 流式「帮我表达」（表达改写）。
     *
     * 正文按 5 个风格逐段打字机下发；`Finished.structured` 里的
     * `rewrites` 是结构化版本数组（每项含 style/content）。
     */
    fun rewriteExpressionStream(text: String, context: String? = null): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.rewriteExpressionStream(AiDto.RewriteRequest(text, context))
        }

    /** 流式「AI 画像报告」（纯 Markdown 长文，无结构化字段）。 */
    fun profileReportStream(): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.profileReportStream()
        }

    /**
     * 流式「关系复盘」。
     *
     * 与画像报告的区别：`Finished.structured` 里有 8 个结构化字段
     * （触发点 / 双方需求 / 误解处 / 升级话术 / 降温话术 / 下次可用表达），
     * 由 `ReviewViewModel.parseReview()` 收口成 DTO。
     */
    fun reviewStream(description: String, context: String? = null): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.reviewStream(AiDto.ReviewRequest(description, context))
        }

    /**
     * 流式「双视角对照总结」（纯 Markdown 长文，无结构化字段）。
     *
     * 按事件回读：同一个事件只保留最新一份总结，换事件互不覆盖。
     */
    fun dualSummaryStream(eventId: Long): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.dualSummaryStream(AiDto.DualSummaryRequest(eventId))
        }

    /** 回读某个事件上次的双视角总结；`null` 表示还没生成过。 */
    suspend fun getSavedDualSummary(eventId: Long): Result<AiDto.GenerationPayload?> {
        return try {
            val response = apiService.getGeneration(DUAL_SUMMARY_KIND, "dual_event", eventId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 流式「关系练习 AI 整理」（纯 Markdown 长文，无结构化字段）。
     * 按练习记录回读：同一条记录只保留最新一份整理。
     */
    fun practiceSummaryStream(recordId: Long): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.practiceSummaryStream(AiDto.PracticeSummaryRequest(recordId))
        }

    /** 回读某条练习记录上次的 AI 整理；`null` 表示还没整理过。 */
    suspend fun getSavedPracticeSummary(recordId: Long): Result<AiDto.GenerationPayload?> {
        return try {
            val response = apiService.getGeneration(PRACTICE_SUMMARY_KIND, "practice_record", recordId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 回读上次的关系复盘（ai_generation 覆盖式只留最新一条）。
     * 返回 `null` 表示还没复盘过——这是正常情况，不是错误。
     */
    suspend fun getSavedReview(): Result<AiDto.GenerationPayload?> {
        return try {
            val response = apiService.getGeneration(REVIEW_KIND, "none", null)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 把 `Finished.structured` 的通用 Map 收口成 [AiDto.ReviewResult]。
     *
     * 走 Moshi 而不是手写取字段：手写容易漏掉「模型把数组写成字符串」这类
     * 变形，而且字段增删时要改两处。
     */
    fun parseReview(structured: Map<String, Any?>?): AiDto.ReviewResult? {
        if (structured.isNullOrEmpty()) return null
        return try {
            reviewAdapter.fromJson(mapAdapter.toJson(structured))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 与后端 `ai_generation.generation_kind` 一致，改这里必须同步后端 */
        const val REVIEW_KIND = "relationship_review"
        const val DUAL_SUMMARY_KIND = "dual_summary"
        const val PRACTICE_SUMMARY_KIND = "practice_summary"
    }

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
     * 报文解析交给 `core.network.sseFrames()`——信件解读、量表分析等场景走的是
     * 同一套 SSE 报文格式，各自抄一份解析器迟早会抄出不一致。
     * 这里只负责把通用帧翻译成聊天语义的事件。
     *
     * `body.use { }` 不是可省的：它保证流被取消或异常时立刻关闭 HTTP 连接。
     */
    fun chatStream(request: AiDto.ChatRequest): Flow<AiDto.ChatStreamEvent> = flow {
        val response = try {
            apiService.aiChatStream(request)
        } catch (e: Exception) {
            emit(AiDto.ChatStreamEvent.Failure(50000, "网络异常：${e.message ?: "连接失败"}"))
            return@flow
        }

        if (!response.isSuccessful) {
            // 与 LetterRepository 同理：errorBody 不关会漏连接
            response.errorBody()?.close()
            emit(AiDto.ChatStreamEvent.Failure(response.code(), httpHint(response.code())))
            return@flow
        }

        val body = response.body()
        if (body == null) {
            emit(AiDto.ChatStreamEvent.Failure(50000, "服务端返回空响应"))
            return@flow
        }

        body.use { rb ->
            // decode 返回 null 表示该帧无需派发（如解析失败的 thinking 帧），静默跳过
            emitAll(rb.sseFrames().mapNotNull { frame -> decode(frame.event, frame.data) })
        }
    }.catch { e ->
        // 连接中断也要收敛成终态事件，否则 UI 的 isStreaming 永远为 true
        emit(AiDto.ChatStreamEvent.Failure(50000, netHint(e)))
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

    /** Agent 问答：模型自主决定是否调用工具（查画像 / 检索理论），返回答复 + 工具调用轨迹。 */
    suspend fun agentChat(question: String, history: List<AiDto.AgentHistoryItem>? = null): Result<AiDto.AgentResponse> {
        return try {
            val response = apiService.agentChat(AiDto.AgentRequest(question, history))
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

    /** 网络层异常的口语化解释，理由见 LetterRepository.netHint 的注释。 */
    private fun netHint(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "等待响应超时，请重试"
        is java.net.SocketException -> "网络连接被中断（可能是切换了 Wi-Fi 或移动数据）"
        is java.io.IOException -> "网络读写失败，请检查网络后重试"
        else -> e.message ?: "连接已断开"
    }

    /** 中断一次正在进行的 ai_generation 生成（表达改写等流式端点共用）。 */
    suspend fun cancelGeneration(generationId: Long): Result<Boolean> {
        return try {
            val response = apiService.cancelGeneration(generationId)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data.cancelled)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
