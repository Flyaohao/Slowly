package com.couple.translator.feature.single.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object SelfPracticeDto {

    @JsonClass(generateAdapter = true)
    data class SelfPracticeResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String,
        @Json(name = "practice_type") val practiceType: String,
        @Json(name = "description") val description: String,
        @Json(name = "guidance") val guidance: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SelfPracticeRecordResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "practice_id") val practiceId: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "status") val status: String,
        @Json(name = "content") val content: String? = null,
        @Json(name = "reflection") val reflection: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SelfPracticeRecordListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<SelfPracticeRecordResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class SubmitSelfPracticeRequest(
        @Json(name = "content") val content: String? = null,
        @Json(name = "reflection") val reflection: String? = null,
    )
}
