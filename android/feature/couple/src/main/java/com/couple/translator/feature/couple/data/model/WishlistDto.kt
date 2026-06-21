package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object WishlistDto {

    @JsonClass(generateAdapter = true)
    data class WishlistResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "description") val description: String? = null,
        @Json(name = "status") val status: String = "pending",
        @Json(name = "completed_at") val completedAt: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CreateWishlistRequest(
        @Json(name = "title") val title: String,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class UpdateWishlistRequest(
        @Json(name = "title") val title: String? = null,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class WishlistListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<WishlistResponse> = emptyList(),
    )
}
