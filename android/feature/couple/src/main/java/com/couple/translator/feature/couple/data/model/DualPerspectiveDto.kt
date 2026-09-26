package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object DualPerspectiveDto {

    @JsonClass(generateAdapter = true)
    data class DualEventResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "event_time") val eventTime: String? = null,
        @Json(name = "status") val status: String = "one_side",
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CreateEventRequest(
        @Json(name = "title") val title: String,
        /**
         * 后端 schema `DualPerspectiveEventCreate.event_time` 是**必填的 datetime**，
         * 传 null 会直接 422。UI 里虽是选填输入框，但由 ViewModel 归一化后兜底为当前时间。
         */
        @Json(name = "event_time") val eventTime: String,
        /**
         * 整改 §8.6：邀请语。非空 = 创建后通知伴侣「一起来写」；
         * 空（Moshi 不序列化 null）= 用户自己先记着，不打扰对方。
         */
        @Json(name = "invite_message") val inviteMessage: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DualRecordResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "event_id") val eventId: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "content") val content: String,
        @Json(name = "visibility") val visibility: String = "hidden",
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SubmitRecordRequest(
        @Json(name = "content") val content: String,
        @Json(name = "visibility") val visibility: String = "hidden",
    )

    @JsonClass(generateAdapter = true)
    data class UpdateRecordRequest(
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class DualEventDetailResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "event_time") val eventTime: String? = null,
        @Json(name = "status") val status: String = "one_side",
        @Json(name = "records") val records: List<DualRecordResponse> = emptyList(),
        /**
         * P0-1 服务端过滤（契约 §2.1）：reveal 前 viewer 只拿到自己的 record，
         * 对方已提交与否由该字段告知（只增不减）。
         * null = 旧后端未返回该字段 → FE 走旧行为，不崩。
         */
        @Json(name = "partner_submitted") val partnerSubmitted: Boolean? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DualEventListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<DualEventResponse> = emptyList(),
    )
}
