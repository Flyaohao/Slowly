package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object PracticeDto {

    @JsonClass(generateAdapter = true)
    data class PracticeResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String,
        @Json(name = "practice_type") val practiceType: String,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class PracticeRecordResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "practice_id") val practiceId: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "initiator_id") val initiatorId: Long,
        @Json(name = "status") val status: String = "initiated",
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class PracticeRecordDetailResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "practice_id") val practiceId: Long,
        @Json(name = "practice_title") val practiceTitle: String,
        @Json(name = "practice_type") val practiceType: String,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "initiator_id") val initiatorId: Long,
        @Json(name = "status") val status: String = "initiated",
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "my_submission") val mySubmission: String? = null,
        @Json(name = "partner_submission") val partnerSubmission: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SubmitPracticeRequest(
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class PracticeRecordListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<PracticeRecordResponse> = emptyList(),
    )
}
