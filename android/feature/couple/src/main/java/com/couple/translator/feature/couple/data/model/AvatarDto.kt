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
        /** P-B §4.4：auto=画像自动选择 / manual=用户手选过（控制「由画像自动选择」提示行） */
        @Json(name = "voice_style_source") val voiceStyleSource: String = "auto",
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
