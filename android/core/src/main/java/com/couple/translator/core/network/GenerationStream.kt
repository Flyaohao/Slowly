package com.couple.translator.core.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import okhttp3.ResponseBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单次触发型 AI 生成的**通用**流式事件与解码基建。
 *
 * 信件解读是第一个流式化的能力，它的解码器当时私有在 `LetterRepository` 里；
 * v2.2 起信件改写 / AI 回信 / 表达改写 / 画像报告 / 量表分析全部切到同一套
 * `ai_generation` SSE 协议，解码逻辑不该再抄 N 份，于是抽到 core——
 * 情侣侧（feature:couple）与个人侧（core 的问卷结果页）都要用，
 * 放在任一 feature 模块里另一侧就够不着了。
 *
 * 与 `LetterDto.LetterStreamEvent` 的差别只有一处：`Finished.structured`
 * 是通用 `Map` 而非信件专属的 `LetterUnderstanding`——不同生成类型
 * （letter_rewrite / letter_reply / expression_rewrite / profile_report /
 * questionnaire_analysis）的结构化字段形状各不相同，由各 ViewModel 自行取用。
 *
 * 事件序列与信件解读完全一致：
 * `meta` → `thinking`* / `delta`* → `notice` → `done`（失败给 `error`）。
 */
sealed interface GenerationStreamEvent {
    /** 服务端已就绪，带回 generationId（用户点「停止」时回传给取消端点） */
    data class Started(val generationId: Long) : GenerationStreamEvent

    /** 模型推理过程增量，喂「深度思考」面板，不参与正文拼接 */
    data class Thinking(val content: String) : GenerationStreamEvent

    /** 正文增量，逐块追加即得打字机效果 */
    data class Delta(val content: String) : GenerationStreamEvent

    /** 正文结束，正在整理结构化结果 */
    data object Structuring : GenerationStreamEvent

    data class Finished(
        val generationId: Long,
        val status: String,
        val interrupted: Boolean,
        val content: String,
        val thinking: String,
        val structured: Map<String, Any?>?,
        val riskLevel: String,
    ) : GenerationStreamEvent

    data class Failure(val code: Int, val message: String) : GenerationStreamEvent
}

@JsonClass(generateAdapter = true)
data class GenerationStreamMeta(
    @Json(name = "generation_id") val generationId: Long = 0,
)

@JsonClass(generateAdapter = true)
data class GenerationStreamChunk(
    @Json(name = "content") val content: String = "",
)

@JsonClass(generateAdapter = true)
data class GenerationStreamDone(
    @Json(name = "generation_id") val generationId: Long = 0,
    @Json(name = "status") val status: String = "done",
    @Json(name = "interrupted") val interrupted: Boolean = false,
    @Json(name = "content") val content: String = "",
    @Json(name = "thinking") val thinking: String = "",
    // 不同生成类型的结构化字段形状不同，这里保持通用 Map，
    // 由 ViewModel 按 generationKind 取用
    @Json(name = "structured_output") val structuredOutput: Map<String, Any?>? = null,
    @Json(name = "risk_level") val riskLevel: String = "normal",
)

@JsonClass(generateAdapter = true)
data class GenerationStreamError(
    @Json(name = "code") val code: Int = 50000,
    @Json(name = "message") val message: String = "AI 服务异常，请稍后重试",
)

/** 单例解码器：所有走 ai_generation 协议的 SSE 帧都用它解析。 */
@Singleton
class GenerationStreamDecoder @Inject constructor(moshi: Moshi) {

    private val metaAdapter = moshi.adapter(GenerationStreamMeta::class.java)
    private val chunkAdapter = moshi.adapter(GenerationStreamChunk::class.java)
    private val doneAdapter = moshi.adapter(GenerationStreamDone::class.java)
    private val errorAdapter = moshi.adapter(GenerationStreamError::class.java)

    /**
     * 把一帧 SSE 报文解码成业务事件。
     *
     * 返回 `null` 的两种情况：未知帧名（服务端新增了事件，老客户端不该因此挂掉）、
     * 单帧解析失败（过程展示类帧丢了不影响最终结果）。
     */
    fun decode(frame: SseFrame): GenerationStreamEvent? = try {
        when (frame.event) {
            "meta" -> metaAdapter.fromJson(frame.data)
                ?.let { GenerationStreamEvent.Started(it.generationId) }

            "thinking" -> chunkAdapter.fromJson(frame.data)
                ?.let { GenerationStreamEvent.Thinking(it.content) }

            "delta" -> chunkAdapter.fromJson(frame.data)
                ?.let { GenerationStreamEvent.Delta(it.content) }

            "notice" -> GenerationStreamEvent.Structuring

            "done" -> doneAdapter.fromJson(frame.data)?.let { p ->
                GenerationStreamEvent.Finished(
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
                GenerationStreamEvent.Failure(
                    p?.code ?: 50000,
                    p?.message ?: "AI 服务异常，请稍后重试",
                )
            }

            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 通用流式请求骨架：发请求 → 校验 HTTP 状态 → 按 SSE 帧流式解码。
 *
 * `use { }` 不是可省的：它保证流被取消或异常时**立刻关闭 HTTP 连接**，
 * 服务端的写入端会跟着断掉，模型也在最多一个心跳周期内停手。
 *
 * @param decoder SSE 帧解码器（Hilt 单例注入）
 * @param request 挂起函数，返回流式响应（Retrofit `@Streaming` 端点）
 */
fun generationStreamFlow(
    decoder: GenerationStreamDecoder,
    request: suspend () -> Response<ResponseBody>,
): Flow<GenerationStreamEvent> = flow {
    val response = try {
        request()
    } catch (e: Exception) {
        emit(GenerationStreamEvent.Failure(50000, "网络异常：${e.message ?: "连接失败"}"))
        return@flow
    }

    if (!response.isSuccessful) {
        response.errorBody()?.close()
        emit(GenerationStreamEvent.Failure(response.code(), httpHint(response.code())))
        return@flow
    }

    val body = response.body()
    if (body == null) {
        emit(GenerationStreamEvent.Failure(50000, "服务端返回空响应"))
        return@flow
    }

    body.use { rb ->
        emitAll(rb.sseFrames().mapNotNull { frame -> decoder.decode(frame) })
    }
}.catch { e ->
    // 连接中断也要收敛成终态事件，否则 UI 会永远停在「生成中」
    emit(GenerationStreamEvent.Failure(50000, netHint(e)))
}.flowOn(Dispatchers.IO)

private fun httpHint(code: Int): String = when (code) {
    400 -> "请求被拒绝，请检查输入或绑定状态"
    401 -> "登录已过期，请重新登录"
    403 -> "无权访问此内容"
    404 -> "接口不存在，请升级 App"
    422 -> "请求参数不合法"
    else -> "服务异常（HTTP $code）"
}

private fun netHint(e: Throwable): String = when (e) {
    is java.net.SocketTimeoutException -> "等待响应超时，请重试"
    is java.net.SocketException -> "网络连接被中断（可能是切换了 Wi-Fi 或移动数据）"
    is java.io.IOException -> "网络读写失败，请检查网络后重试"
    else -> e.message ?: "连接已断开"
}
