package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * AI 翻译官相关 DTO。
 *
 * 字段名与 `backend/app/schemas/ai_schema.py` 及后端实际返回的 dict 严格对齐。
 * 2026-09-14 修正了此前三处不一致（都会导致线上失败）：
 * 1. 请求正文键名是 `message` 而非 `content`，写错后端直接 422
 * 2. `/ai/chat` 返回 `{session_id, message: {...}}` 嵌套结构，不是扁平结构
 * 3. 会话消息列表不含 `session_id`（只有外层接口知道），故该字段可空
 */
object AiDto {

    // ---------- 请求 ----------

    @JsonClass(generateAdapter = true)
    data class ChatRequest(
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "message") val message: String,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteRequest(
        @Json(name = "text") val text: String,
        @Json(name = "context") val context: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class FeedbackRequest(
        @Json(name = "rating") val rating: Int,
        @Json(name = "feedback_tag") val feedbackTag: String? = null,
        @Json(name = "feedback_text") val feedbackText: String? = null,
    )

    // ---------- 响应 ----------

    @JsonClass(generateAdapter = true)
    data class ChatResponse(
        @Json(name = "session_id") val sessionId: Long,
        @Json(name = "message") val message: MessageResponse,
    ) {
        // 便捷访问器：让调用方写法无需改动
        val messageId: Long get() = message.id
        val role: String get() = message.role
        val content: String get() = message.content
        val structuredOutput: StructuredOutput? get() = message.structuredOutput
        val riskLevel: String? get() = message.riskLevel
    }

    @JsonClass(generateAdapter = true)
    data class StructuredOutput(
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "emotion_validation") val emotionValidation: String? = null,
        @Json(name = "partner_possible_meaning") val partnerPossibleMeaning: String? = null,
        @Json(name = "suggested_reply") val suggestedReply: String? = null,
        @Json(name = "do_not_say") val doNotSay: String? = null,
        @Json(name = "next_step") val nextStep: String? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "suggested_actions") val suggestedActions: List<String>? = null,
        @Json(name = "rewrites") val rewrites: List<RewriteItem>? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteItem(
        @Json(name = "style") val style: String,
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class SessionResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "title") val title: String? = null,
        @Json(name = "privacy_level") val privacyLevel: String = "private",
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MessageResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "role") val role: String,
        @Json(name = "content") val content: String,
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "structured_output") val structuredOutput: StructuredOutput? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteResponse(
        @Json(name = "original") val original: String,
        @Json(name = "versions") val versions: List<RewriteVersion>,
        @Json(name = "summary") val summary: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteVersion(
        @Json(name = "style") val style: String,
        @Json(name = "content") val content: String,
    )

    // ---------- SSE 流式（/api/v1/couple/ai/chat/stream）----------
    // 帧格式：`event: <name>\ndata: <json>\n\n`
    // 事件序列：meta -> delta* -> done；失败给 error

    @JsonClass(generateAdapter = true)
    data class StreamMetaPayload(
        @Json(name = "session_id") val sessionId: Long,
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "rag_hit") val ragHit: Int = 0,
    )

    @JsonClass(generateAdapter = true)
    data class StreamDeltaPayload(
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class StreamDonePayload(
        @Json(name = "session_id") val sessionId: Long = 0,
        @Json(name = "message_id") val messageId: Long = 0,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "blocked") val blocked: Boolean = false,
        @Json(name = "content") val content: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class StreamErrorPayload(
        @Json(name = "code") val code: Int = 50000,
        @Json(name = "message") val message: String = "AI 服务异常，请稍后重试",
    )

    /** 客户端侧事件抽象：UI 只消费它，不关心 wire format */
    sealed interface ChatStreamEvent {
        data class Meta(val sessionId: Long, val sceneKey: String, val ragHit: Int) : ChatStreamEvent
        data class Delta(val content: String) : ChatStreamEvent
        data class Done(
            val sessionId: Long,
            val messageId: Long,
            val riskLevel: String?,
            val blocked: Boolean,
            val content: String,
        ) : ChatStreamEvent

        data class Failure(val code: Int, val message: String) : ChatStreamEvent
    }
}
