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
}
