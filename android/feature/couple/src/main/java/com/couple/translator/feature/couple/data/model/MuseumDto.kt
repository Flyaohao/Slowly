package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object MuseumDto {

    @JsonClass(generateAdapter = true)
    data class MuseumItemResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "item_type") val itemType: String,
        @Json(name = "title") val title: String,
        @Json(name = "story") val story: String? = null,
        @Json(name = "image_url") val imageUrl: String? = null,
        @Json(name = "source_id") val sourceId: Long? = null,
        @Json(name = "source_type") val sourceType: String? = null,
        @Json(name = "pinned") val pinned: Boolean = false,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CreateMuseumItemRequest(
        @Json(name = "item_type") val itemType: String,
        @Json(name = "title") val title: String,
        @Json(name = "story") val story: String? = null,
        @Json(name = "image_url") val imageUrl: String? = null,
        @Json(name = "source_id") val sourceId: Long? = null,
        @Json(name = "source_type") val sourceType: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class UpdateMuseumItemRequest(
        @Json(name = "title") val title: String? = null,
        @Json(name = "story") val story: String? = null,
        @Json(name = "image_url") val imageUrl: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MuseumImageUploadResponse(
        @Json(name = "image_url") val imageUrl: String,
    )

    @JsonClass(generateAdapter = true)
    data class MuseumListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<MuseumItemResponse> = emptyList(),
    )
}
