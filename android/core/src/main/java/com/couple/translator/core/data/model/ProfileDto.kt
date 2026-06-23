package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object ProfileDto {

    @JsonClass(generateAdapter = true)
    data class RelationshipProfileResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "profile_type") val profileType: String,
        @Json(name = "confidence") val confidence: Float = 0f,
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "version") val version: Int = 1,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DimensionScoreResponse(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "dimension_key") val dimensionKey: String,
        @Json(name = "score") val score: Float = 0f,
        @Json(name = "explanation") val explanation: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class ProfileDetailResponse(
        @Json(name = "profile") val profile: RelationshipProfileResponse,
        @Json(name = "dimensions") val dimensions: List<DimensionScoreResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class CoupleProfileResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "user_a_profile_id") val userAProfileId: Long,
        @Json(name = "user_b_profile_id") val userBProfileId: Long,
        @Json(name = "conflict_pattern") val conflictPattern: String? = null,
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "user_a_profile") val userAProfile: RelationshipProfileResponse? = null,
        @Json(name = "user_b_profile") val userBProfile: RelationshipProfileResponse? = null,
        @Json(name = "user_a_dimensions") val userADimensions: List<DimensionScoreResponse> = emptyList(),
        @Json(name = "user_b_dimensions") val userBDimensions: List<DimensionScoreResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class AiReportResponse(
        @Json(name = "report") val report: String,
    )
}
