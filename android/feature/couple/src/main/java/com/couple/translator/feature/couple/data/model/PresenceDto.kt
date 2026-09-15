package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object PresenceDto {

    @JsonClass(generateAdapter = true)
    data class MomentResponse(
        val id: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "moment_type") val momentType: String,
        val content: String,
        @Json(name = "image_url") val imageUrl: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MomentShareRequest(
        val content: String,
        @Json(name = "moment_type") val momentType: String = "text",
        @Json(name = "image_url") val imageUrl: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CompanionRequest(
        val message: String? = null,
    )
}
