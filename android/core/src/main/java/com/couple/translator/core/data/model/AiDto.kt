package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object AiDto {

    @JsonClass(generateAdapter = true)
    data class ChatRequest(
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class ChatResponse(
        @Json(name = "session_id") val sessionId: Long,
        @Json(name = "message_id") val messageId: Long,
        @Json(name = "role") val role: String = "assistant",
        @Json(name = "content") val content: String,
        @Json(name = "structured_output") val structuredOutput: StructuredOutput? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
    )

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
        @Json(name = "session_id") val sessionId: Long,
        @Json(name = "role") val role: String,
        @Json(name = "content") val content: String,
        @Json(name = "structured_output") val structuredOutput: StructuredOutput? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class FeedbackRequest(
        @Json(name = "rating") val rating: Int,
        @Json(name = "feedback_tag") val feedbackTag: String? = null,
        @Json(name = "feedback_text") val feedbackText: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteRequest(
        @Json(name = "text") val text: String,
        @Json(name = "context") val context: String? = null,
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
}
