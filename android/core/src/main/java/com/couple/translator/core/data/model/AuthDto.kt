package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object AuthDto {

    @JsonClass(generateAdapter = true)
    data class RegisterRequest(
        @Json(name = "email") val email: String,
        @Json(name = "password") val password: String,
    )

    @JsonClass(generateAdapter = true)
    data class RegisterResponse(
        @Json(name = "user_id") val userId: Long,
    )

    @JsonClass(generateAdapter = true)
    data class LoginRequest(
        @Json(name = "email") val email: String,
        @Json(name = "password") val password: String,
    )

    @JsonClass(generateAdapter = true)
    data class TokenResponse(
        @Json(name = "access_token") val accessToken: String,
        @Json(name = "refresh_token") val refreshToken: String,
        @Json(name = "mode") val mode: String = "single",
    )

    @JsonClass(generateAdapter = true)
    data class RefreshRequest(
        @Json(name = "refresh_token") val refreshToken: String,
    )

    @JsonClass(generateAdapter = true)
    data class ForgotPasswordRequest(
        @Json(name = "email") val email: String,
    )

    @JsonClass(generateAdapter = true)
    data class ResetPasswordRequest(
        @Json(name = "email") val email: String,
        @Json(name = "code") val code: String,
        @Json(name = "new_password") val newPassword: String,
    )
}
