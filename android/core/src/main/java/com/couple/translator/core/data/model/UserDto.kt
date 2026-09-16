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
        @Json(name = "email_notify_enabled") val emailNotifyEnabled: Boolean = false,
    )

    /**
     * 通知偏好。
     *
     * emailReady = 服务端 SMTP 是否配置好。没配好时开关应置灰而不是让用户白开一次
     * —— 开了也发不出去，那是最伤信任的一种"假功能"。
     */
    @JsonClass(generateAdapter = true)
    data class NotificationPrefResponse(
        @Json(name = "email_notify_enabled") val emailNotifyEnabled: Boolean = false,
        @Json(name = "email") val email: String = "",
        @Json(name = "email_ready") val emailReady: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class NotificationPrefUpdateRequest(
        @Json(name = "email_notify_enabled") val emailNotifyEnabled: Boolean,
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
