package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 军师设置（契约 §3.3）：`GET/PUT /api/v1/advisor/settings`，GET → PUT 同构。
 *
 * 后端端点未落地前 GET 会 404/解析失败，FE 侧以 [defaults] 兜底渲染、
 * 保存失败时明确提示——不静默吞错（见 AdvisorSettingsViewModel）。
 */
object AdvisorDto {

    /** 可选值与契约一致：detail_level=brief|standard|detailed，proactivity=passive|moderate|active */
    @JsonClass(generateAdapter = true)
    data class AdvisorSettings(
        /** 军师对用户的称呼（新列） */
        @Json(name = "address_name") val addressName: String = "",
        /** 详细程度：brief | standard | detailed */
        @Json(name = "detail_level") val detailLevel: String = "standard",
        /** 主动程度：passive | moderate | active */
        @Json(name = "proactivity") val proactivity: String = "moderate",
        /** 是否显示判断依据 */
        @Json(name = "show_evidence") val showEvidence: Boolean = true,
        /** 语气（复用 avatar.voice_style，透传） */
        @Json(name = "voice_style") val voiceStyle: String = "gentle",
    ) {
        companion object {
            fun defaults(): AdvisorSettings = AdvisorSettings()
        }
    }
}
