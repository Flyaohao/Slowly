package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object LetterDto {

    @JsonClass(generateAdapter = true)
    data class LetterRequest(
        @Json(name = "title") val title: String? = null,
        @Json(name = "content") val content: String? = null,
        @Json(name = "letter_type") val letterType: String = "normal",
        @Json(name = "status") val status: String = "draft",
        @Json(name = "send_time") val sendTime: String? = null,
        @Json(name = "unlock_time") val unlockTime: String? = null,
        @Json(name = "is_private") val isPrivate: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class LetterResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long? = null,
        @Json(name = "sender_id") val senderId: Long,
        @Json(name = "receiver_id") val receiverId: Long,
        @Json(name = "title") val title: String? = null,
        @Json(name = "content") val content: String? = null,
        @Json(name = "letter_type") val letterType: String = "normal",
        @Json(name = "status") val status: String = "draft",
        @Json(name = "send_time") val sendTime: String? = null,
        @Json(name = "unlock_time") val unlockTime: String? = null,
        @Json(name = "is_private") val isPrivate: Boolean = false,
        @Json(name = "is_favorite") val isFavorite: Boolean = false,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class LetterListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<LetterResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class LetterUnderstanding(
        @Json(name = "summary") val summary: String = "",
        @Json(name = "key_concerns") val keyConcerns: List<String> = emptyList(),
        @Json(name = "emotion") val emotion: String = "",
        @Json(name = "expected_response") val expectedResponse: String = "",
        @Json(name = "misunderstandable") val misunderstandable: List<MisunderstandableItem> = emptyList(),
        @Json(name = "reply_suggestions") val replySuggestions: List<String> = emptyList(),
        @Json(name = "risk_level") val riskLevel: String = "normal",
    )

    @JsonClass(generateAdapter = true)
    data class MisunderstandableItem(
        @Json(name = "sentence") val sentence: String,
        @Json(name = "note") val note: String,
    )

    @JsonClass(generateAdapter = true)
    data class UnderstandLetterResponse(
        @Json(name = "letter_id") val letterId: Long = 0,
        @Json(name = "analysis") val analysis: LetterUnderstanding? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteResult(
        @Json(name = "summary") val summary: String = "",
        @Json(name = "rewritten_title") val rewrittenTitle: String = "",
        @Json(name = "rewritten_content") val rewrittenContent: String = "",
        @Json(name = "changes") val changes: String = "",
        @Json(name = "risk_level") val riskLevel: String = "normal",
    )

    @JsonClass(generateAdapter = true)
    data class ReplyVariant(
        @Json(name = "style") val style: String = "",
        @Json(name = "content") val content: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class ReplyResult(
        @Json(name = "summary") val summary: String = "",
        @Json(name = "replies") val replies: List<ReplyVariant> = emptyList(),
        @Json(name = "do_not_say") val doNotSay: String = "",
        @Json(name = "risk_level") val riskLevel: String = "normal",
    )

    @JsonClass(generateAdapter = true)
    data class UnderstandLetterRequest(
        @Json(name = "letter_id") val letterId: Long,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteLetterRequest(
        @Json(name = "letter_id") val letterId: Long,
        @Json(name = "style") val style: String,
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteLetterResponse(
        @Json(name = "letter_id") val letterId: Long = 0,
        @Json(name = "rewrite") val rewrite: RewriteResult? = null,
    ) {
        val summary: String get() = rewrite?.summary ?: ""
        val rewrittenTitle: String get() = rewrite?.rewrittenTitle ?: ""
        val rewrittenContent: String get() = rewrite?.rewrittenContent ?: ""
        val changes: String get() = rewrite?.changes ?: ""
        val riskLevel: String get() = rewrite?.riskLevel ?: "normal"
    }

    @JsonClass(generateAdapter = true)
    data class GenerateReplyRequest(
        @Json(name = "letter_id") val letterId: Long,
    )

    @JsonClass(generateAdapter = true)
    data class GenerateReplyResponse(
        @Json(name = "letter_id") val letterId: Long = 0,
        @Json(name = "reply") val reply: ReplyResult? = null,
    ) {
        val summary: String get() = reply?.summary ?: ""
        val replies: List<ReplyVariant> get() = reply?.replies ?: emptyList()
        val doNotSay: String get() = reply?.doNotSay ?: ""
        val riskLevel: String get() = reply?.riskLevel ?: "normal"
        /** 首条回信建议正文，兼容旧调用方 */
        val suggestedReply: String get() = reply?.replies?.firstOrNull()?.content ?: ""
    }

    @JsonClass(generateAdapter = true)
    data class BatchDeleteRequest(
        @Json(name = "letter_ids") val letterIds: List<Long>,
    )

    @JsonClass(generateAdapter = true)
    data class BatchDeleteResponse(
        @Json(name = "deleted_count") val deletedCount: Int = 0,
    )

    // ------------------------------------------------------------------ //
    // 流式解读（SSE）+ 结果回读
    // ------------------------------------------------------------------ //

    /** `meta` 帧。带回 `generationId`——用户点「停止生成」时要靠它告诉服务端停手。 */
    @JsonClass(generateAdapter = true)
    data class LetterStreamMeta(
        @Json(name = "generation_id") val generationId: Long = 0,
        @Json(name = "generation_kind") val generationKind: String = "",
        @Json(name = "target_id") val targetId: Long? = null,
    )

    /** `thinking` / `delta` 帧的载荷，形状相同（都是 `{"content": "..."}`）。 */
    @JsonClass(generateAdapter = true)
    data class LetterStreamChunk(
        @Json(name = "content") val content: String = "",
    )

    /** `notice` 帧：正文已说完，服务端正在整理结构化结果（`stage = "structuring"`）。 */
    @JsonClass(generateAdapter = true)
    data class LetterStreamNotice(
        @Json(name = "stage") val stage: String = "",
    )

    /** `done` 帧：终态，携带完整正文、思考过程与结构化字段。 */
    @JsonClass(generateAdapter = true)
    data class LetterStreamDone(
        @Json(name = "generation_id") val generationId: Long = 0,
        @Json(name = "status") val status: String = "done",
        @Json(name = "interrupted") val interrupted: Boolean = false,
        @Json(name = "content") val content: String = "",
        @Json(name = "thinking") val thinking: String = "",
        @Json(name = "structured_output") val structuredOutput: LetterUnderstanding? = null,
        @Json(name = "risk_level") val riskLevel: String = "normal",
    )

    @JsonClass(generateAdapter = true)
    data class LetterStreamError(
        @Json(name = "code") val code: Int = 50000,
        @Json(name = "message") val message: String = "AI 服务异常，请稍后重试",
    )

    /**
     * 生成结果的回读载荷（后端 `ai_generation` 表的一行）。
     *
     * `structuredOutput` 直接声明成 [LetterUnderstanding] 而不是通用 Map，
     * 让 Moshi 一次解析到位——否则调用方还得自己再做一次类型转换。
     */
    @JsonClass(generateAdapter = true)
    data class LetterGenerationPayload(
        @Json(name = "generation_id") val generationId: Long = 0,
        @Json(name = "status") val status: String = "",
        @Json(name = "content") val content: String = "",
        @Json(name = "thinking") val thinking: String = "",
        @Json(name = "structured_output") val structuredOutput: LetterUnderstanding? = null,
        @Json(name = "risk_level") val riskLevel: String = "normal",
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CancelGenerationResponse(
        @Json(name = "cancelled") val cancelled: Boolean = false,
    )

    /**
     * 信件解读的流式事件。
     *
     * UI 只消费它，不关心线上报文——服务端改帧名、加字段都不会波及 ViewModel。
     */
    sealed interface LetterStreamEvent {
        /** 服务端已就绪，带回 generationId */
        data class Started(val generationId: Long) : LetterStreamEvent

        /** 模型推理过程增量，只喂「深度思考」面板，不参与正文拼接 */
        data class Thinking(val content: String) : LetterStreamEvent

        /** 正文增量，逐块追加即得打字机效果 */
        data class Delta(val content: String) : LetterStreamEvent

        /** 正文结束，正在整理结构化结果 */
        data object Structuring : LetterStreamEvent

        data class Finished(
            val generationId: Long,
            val status: String,
            val interrupted: Boolean,
            val content: String,
            val thinking: String,
            val structured: LetterUnderstanding?,
            val riskLevel: String,
        ) : LetterStreamEvent

        data class Failure(val code: Int, val message: String) : LetterStreamEvent
    }
}
