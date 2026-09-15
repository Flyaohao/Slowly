package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object AvatarDto {

    @JsonClass(generateAdapter = true)
    data class AvatarResponse(
        val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        val name: String,
        @Json(name = "body_color") val bodyColor: String? = null,
        @Json(name = "face_config") val faceConfig: Map<String, Int>? = null,
        @Json(name = "outfit_config") val outfitConfig: Map<String, String>? = null,
        @Json(name = "voice_style") val voiceStyle: String = "gentle",
        @Json(name = "background_url") val backgroundUrl: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AvatarUpdateRequest(
        val name: String? = null,
        @Json(name = "face_config") val faceConfig: Map<String, Int>? = null,
    )

    @JsonClass(generateAdapter = true)
    data class VoiceStyleRequest(
        @Json(name = "voice_style") val voiceStyle: String,
    )
}
