package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object AnniversaryDto {

    @JsonClass(generateAdapter = true)
    data class AnniversaryResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "anniversary_date") val anniversaryDate: String,
        @Json(name = "description") val description: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CreateAnniversaryRequest(
        @Json(name = "title") val title: String,
        @Json(name = "anniversary_date") val anniversaryDate: String,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class UpdateAnniversaryRequest(
        @Json(name = "title") val title: String? = null,
        @Json(name = "anniversary_date") val anniversaryDate: String? = null,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AnniversaryListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<AnniversaryResponse> = emptyList(),
    )
}
