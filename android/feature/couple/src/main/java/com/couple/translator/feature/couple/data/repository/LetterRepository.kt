package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.network.SseFrame
import com.couple.translator.core.network.sseFrames
import com.couple.translator.feature.couple.data.model.LetterDto
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

@Singleton
class LetterRepository @Inject constructor(
    private val apiService: CoupleApiService,
    private val moshi: Moshi,
) {

    private val metaAdapter by lazy { moshi.adapter(LetterDto.LetterStreamMeta::class.java) }
    private val chunkAdapter by lazy { moshi.adapter(LetterDto.LetterStreamChunk::class.java) }
    private val doneAdapter by lazy { moshi.adapter(LetterDto.LetterStreamDone::class.java) }
    private val errorAdapter by lazy { moshi.adapter(LetterDto.LetterStreamError::class.java) }

    /** 信件改写 / AI 回信等新流式端点共用的通用解码器（同一套 ai_generation 协议） */
    private val generationDecoder = GenerationStreamDecoder(moshi)
    suspend fun createLetter(request: LetterDto.LetterRequest): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.createLetter(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLetters(
        type: String? = null,
        status: String? = null,
        direction: String? = null,
    ): Result<LetterDto.LetterListResponse?> {
        return try {
            val response = apiService.getLetters(type, status, direction)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLetter(id: Long): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.getLetter(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateLetter(id: Long, request: LetterDto.LetterRequest): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.updateLetter(id, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteLetter(id: Long): Result<Unit> {
        return try {
            val response = apiService.deleteLetter(id)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun batchDeleteLetters(letterIds: List<Long>): Result<Int> {
        return try {
            val response = apiService.batchDeleteLetters(LetterDto.BatchDeleteRequest(letterIds))
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data.deletedCount)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendLetter(id: Long): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.sendLetter(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleFavorite(id: Long): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.toggleFavorite(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getInbox(): Result<LetterDto.LetterListResponse?> {
        return try {
            val response = apiService.getInbox()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDrafts(): Result<LetterDto.LetterListResponse?> {
        return try {
            val response = apiService.getDrafts()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun understandLetter(letterId: Long): Result<LetterDto.LetterUnderstanding?> {
        return try {
            val response = apiService.understandLetter(LetterDto.UnderstandLetterRequest(letterId))
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data.analysis)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 流式「AI 帮我理解」。
     *
     * 与 [understandLetter] 的区别：那个是「转圈十几秒 → 整段蹦出来」，
     * 这个是逐字出结果，且中途可以叫停。
     *
     * `use { }` 不是可省的：它保证流被取消或异常时**立刻关闭 HTTP 连接**，
     * 服务端的写入端会跟着断掉，模型也在最多一个心跳周期内停手。
     * 否则连接会挂在那里，服务端还继续把回答拉完，token 白烧。
     */
    fun understandLetterStream(letterId: Long): Flow<LetterDto.LetterStreamEvent> = flow {
        val response = try {
            apiService.understandLetterStream(LetterDto.UnderstandLetterRequest(letterId))
        } catch (e: Exception) {
            emit(LetterDto.LetterStreamEvent.Failure(50000, "网络异常：${e.message ?: "连接失败"}"))
            return@flow
        }

        if (!response.isSuccessful) {
            // errorBody 同样是「已打开的响应体」，不关就会漏一个连接：
            // OkHttp 会在之后的某次 GC 上打印 "A connection ... was leaked"。
            response.errorBody()?.close()
            emit(LetterDto.LetterStreamEvent.Failure(response.code(), httpHint(response.code())))
            return@flow
        }

        val body = response.body()
        if (body == null) {
            emit(LetterDto.LetterStreamEvent.Failure(50000, "服务端返回空响应"))
            return@flow
        }

        body.use { rb ->
            emitAll(rb.sseFrames().mapNotNull { frame -> decodeLetterFrame(frame) })
        }
    }.catch { e ->
        // 连接中断也要收敛成终态事件，否则 UI 会永远停在「生成中」
        emit(LetterDto.LetterStreamEvent.Failure(50000, netHint(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 回读这封信上次保存的 AI 理解。
     *
     * 返回 `success(null)` 表示「从来没解读过」，这是正常情况而非错误——
     * 详情页据此决定要不要在用户点击时才去调模型。
     */
    suspend fun getSavedUnderstanding(letterId: Long): Result<LetterDto.LetterGenerationPayload?> {
        return try {
            val response = apiService.getLetterUnderstanding(targetType = "letter", targetId = letterId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 通知服务端中止生成。失败也无所谓：客户端已经不再显示它了。 */
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

    /**
     * 把一帧 SSE 报文解码成业务事件。
     *
     * 返回 `null` 的两种情况：未知帧名（服务端新增了事件，老客户端不该因此挂掉）、
     * 单帧解析失败（过程展示类帧丢了不影响最终结果）。
     */
    private fun decodeLetterFrame(frame: SseFrame): LetterDto.LetterStreamEvent? = try {
        when (frame.event) {
            "meta" -> metaAdapter.fromJson(frame.data)
                ?.let { LetterDto.LetterStreamEvent.Started(it.generationId) }

            "thinking" -> chunkAdapter.fromJson(frame.data)
                ?.let { LetterDto.LetterStreamEvent.Thinking(it.content) }

            "delta" -> chunkAdapter.fromJson(frame.data)
                ?.let { LetterDto.LetterStreamEvent.Delta(it.content) }

            "notice" -> LetterDto.LetterStreamEvent.Structuring

            "done" -> doneAdapter.fromJson(frame.data)?.let { p ->
                LetterDto.LetterStreamEvent.Finished(
                    generationId = p.generationId,
                    status = p.status,
                    interrupted = p.interrupted,
                    content = p.content,
                    thinking = p.thinking,
                    structured = p.structuredOutput,
                    riskLevel = p.riskLevel,
                )
            }

            "error" -> {
                val p = errorAdapter.fromJson(frame.data)
                LetterDto.LetterStreamEvent.Failure(
                    p?.code ?: 50000,
                    p?.message ?: "AI 服务异常，请稍后重试",
                )
            }

            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun httpHint(code: Int): String = when (code) {
        400 -> "请求被拒绝，请检查是否已绑定情侣"
        401 -> "登录已过期，请重新登录"
        403 -> "无权访问此信件"
        404 -> "信件不存在或接口未上线"
        422 -> "请求参数不合法"
        else -> "服务异常（HTTP $code）"
    }

    /**
     * 把网络层异常翻译成人能看懂的一句话。
     *
     * 真机上最常见的是 `SocketException: Software caused connection abort`——
     * 实测它与其它 App 同时报错、且紧跟着系统日志里的 `teardown wifi`，
     * 也就是手机切换了 Wi-Fi/移动数据，不是本应用的问题。这种异常类名
     * 直接甩给用户毫无意义，说清楚「网络断了、内容已保留」才有用。
     */
    private fun netHint(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "等待响应超时，请重试"
        is java.net.SocketException -> "网络连接被中断（可能是切换了 Wi-Fi 或移动数据）"
        is java.io.IOException -> "网络读写失败，请检查网络后重试"
        else -> e.message ?: "连接已断开"
    }

    suspend fun rewriteLetter(letterId: Long, style: String, content: String): Result<LetterDto.RewriteLetterResponse?> {
        return try {
            val response = apiService.rewriteLetter(LetterDto.RewriteLetterRequest(letterId, style, content))
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
     * 流式「信件改写」：正文即改写后的信件全文，逐字下发。
     * 事件协议与 [understandLetterStream] 完全同一套（ai_generation 基建）。
     */
    fun rewriteLetterStream(letterId: Long, style: String): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.rewriteLetterStream(LetterDto.RewriteLetterRequest(letterId, style, ""))
        }

    /** 流式「AI 回信建议」。 */
    fun generateReplyStream(letterId: Long): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.generateReplyStream(LetterDto.GenerateReplyRequest(letterId))
        }

    suspend fun generateReply(letterId: Long): Result<LetterDto.GenerateReplyResponse?> {
        return try {
            val response = apiService.generateReply(LetterDto.GenerateReplyRequest(letterId))
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
