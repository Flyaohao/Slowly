package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object MediationDto {

    @JsonClass(generateAdapter = true)
    data class MediationStartRequest(
        @Json(name = "partner_user_id") val partnerUserId: Long,
    )

    @JsonClass(generateAdapter = true)
    data class MediationSessionResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "session_type") val sessionType: String = "mediation",
        @Json(name = "partner_user_id") val partnerUserId: Long? = null,
        @Json(name = "mediation_status") val mediationStatus: String = "inviting",
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationInputRequest(
        @Json(name = "feeling") val feeling: String,
        @Json(name = "trigger") val trigger: String,
        @Json(name = "wish_understood") val wishUnderstood: String,
        @Json(name = "wish_next") val wishNext: String,
    )

    @JsonClass(generateAdapter = true)
    data class MediationConfirmRequest(
        @Json(name = "confirmed") val confirmed: Boolean,
        @Json(name = "supplement") val supplement: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationRewrite(
        @Json(name = "original") val original: String,
        @Json(name = "rewritten") val rewritten: String,
    )

    @JsonClass(generateAdapter = true)
    data class MediationDetailResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "mediation_status") val mediationStatus: String,
        @Json(name = "my_rewrite") val myRewrite: MediationRewrite? = null,
        @Json(name = "partner_rewrite") val partnerRewrite: MediationRewrite? = null,
        @Json(name = "common_points") val commonPoints: List<String> = emptyList(),
        @Json(name = "diff_points") val diffPoints: List<String> = emptyList(),
        @Json(name = "next_actions") val nextActions: List<String> = emptyList(),
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationNextRequest(
        @Json(name = "action") val action: String,
    )

    @JsonClass(generateAdapter = true)
    data class MediationMessage(
        @Json(name = "type") val type: String,
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "status") val status: String? = null,
        @Json(name = "data") val data: MediationDetailResponse? = null,
    )
}
