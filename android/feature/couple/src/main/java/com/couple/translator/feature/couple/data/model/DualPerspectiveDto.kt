package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object DualPerspectiveDto {

    @JsonClass(generateAdapter = true)
    data class DualEventResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "event_time") val eventTime: String? = null,
        @Json(name = "status") val status: String = "one_side",
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CreateEventRequest(
        @Json(name = "title") val title: String,
        @Json(name = "event_time") val eventTime: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DualRecordResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "event_id") val eventId: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "content") val content: String,
        @Json(name = "visibility") val visibility: String = "hidden",
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SubmitRecordRequest(
        @Json(name = "content") val content: String,
        @Json(name = "visibility") val visibility: String = "hidden",
    )

    @JsonClass(generateAdapter = true)
    data class UpdateRecordRequest(
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class DualEventDetailResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "event_time") val eventTime: String? = null,
        @Json(name = "status") val status: String = "one_side",
        @Json(name = "records") val records: List<DualRecordResponse> = emptyList(),
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DualEventListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<DualEventResponse> = emptyList(),
    )
}
