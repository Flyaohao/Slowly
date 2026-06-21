package com.couple.translator.feature.single.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object DiaryDto {

    @JsonClass(generateAdapter = true)
    data class CreateDiaryRequest(
        @Json(name = "title") val title: String,
        @Json(name = "content") val content: String,
        @Json(name = "mood") val mood: String? = null,
        @Json(name = "weather") val weather: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class UpdateDiaryRequest(
        @Json(name = "title") val title: String? = null,
        @Json(name = "content") val content: String? = null,
        @Json(name = "mood") val mood: String? = null,
        @Json(name = "weather") val weather: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DiaryResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String,
        @Json(name = "content") val content: String,
        @Json(name = "mood") val mood: String? = null,
        @Json(name = "weather") val weather: String? = null,
        @Json(name = "is_favorite") val isFavorite: Boolean = false,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class BatchDeleteRequest(
        @Json(name = "ids") val ids: List<Long>,
    )

    @JsonClass(generateAdapter = true)
    data class BatchDeleteResponse(
        @Json(name = "deleted_count") val deletedCount: Int,
    )
}
