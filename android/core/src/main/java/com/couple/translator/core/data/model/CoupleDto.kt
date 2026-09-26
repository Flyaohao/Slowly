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
        // 收敛期新增（契约 §2.6-2）：解绑冷却门控。后端 API 未暴露前为 null，
        // FE 据此降级为旧行为（确认按钮仍显示，由服务端 30004/30006 拦截）。
        @Json(name = "unbind_requested_at") val unbindRequestedAt: String? = null,
        @Json(name = "unbind_requested_by") val unbindRequestedBy: Long? = null,
        // 收敛期新增（契约 §3.5）：关系 tab 的在一起天数。未落地前为 null → FE 回退 GET /home 的 relation.love_days。
        @Json(name = "love_days") val loveDays: Int? = null,
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
