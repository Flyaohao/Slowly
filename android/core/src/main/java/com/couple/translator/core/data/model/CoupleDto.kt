package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object CoupleDto {

    @JsonClass(generateAdapter = true)
    data class InviteCodeResponse(
        @Json(name = "invite_code") val inviteCode: String,
        @Json(name = "expires_at") val expiresAt: String,
    )

    @JsonClass(generateAdapter = true)
    data class BindRequest(
        @Json(name = "invite_code") val inviteCode: String,
    )

    @JsonClass(generateAdapter = true)
    data class CoupleRelationResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "user_a_id") val userAId: Long,
        @Json(name = "user_b_id") val userBId: Long,
        @Json(name = "status") val status: String,
        @Json(name = "bind_time") val bindTime: String? = null,
        @Json(name = "space") val space: CoupleSpace? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CoupleSpace(
        @Json(name = "id") val id: Long,
        @Json(name = "name") val name: String,
        @Json(name = "theme_color") val themeColor: String? = null,
        @Json(name = "background_url") val backgroundUrl: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SpaceUpdateRequest(
        @Json(name = "name") val name: String? = null,
        @Json(name = "theme_color") val themeColor: String? = null,
        @Json(name = "background_url") val backgroundUrl: String? = null,
    )
}
