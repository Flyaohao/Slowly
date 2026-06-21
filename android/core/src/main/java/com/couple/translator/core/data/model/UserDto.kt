package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object UserDto {

    @JsonClass(generateAdapter = true)
    data class UserProfileResponse(
        @Json(name = "user_id") val userId: Long,
        @Json(name = "email") val email: String,
        @Json(name = "nickname") val nickname: String? = null,
        @Json(name = "avatar_url") val avatarUrl: String? = null,
        @Json(name = "gender") val gender: String? = null,
        @Json(name = "birthday") val birthday: String? = null,
        @Json(name = "city") val city: String? = null,
        @Json(name = "signature") val signature: String? = null,
        @Json(name = "love_anniversary") val loveAnniversary: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class UpdateProfileRequest(
        @Json(name = "nickname") val nickname: String? = null,
        @Json(name = "gender") val gender: String? = null,
        @Json(name = "birthday") val birthday: String? = null,
        @Json(name = "city") val city: String? = null,
        @Json(name = "signature") val signature: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class PrivatePasswordRequest(
        @Json(name = "password") val password: String,
    )

    @JsonClass(generateAdapter = true)
    data class PrivateTokenResponse(
        @Json(name = "token") val token: String,
    )
}
