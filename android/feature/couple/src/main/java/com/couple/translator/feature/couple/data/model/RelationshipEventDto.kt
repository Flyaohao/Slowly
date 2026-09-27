package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 关系历史事件（用户手动记录，供军师引用）。
 *
 * 与「纪念日」的区别是语义：纪念日是可重复的日期锚点，这里是一条一次性的
 * 具体事件，带发生时刻、经过与**作用判定**。
 *
 * `reason` 与 `polarity` 是后端的**落库门槛**（缺失或敷衍会被 20001 拒绝）：
 * 没有方向的事件对 AI 是噪声，拿它推断关系状态就是把偶发当模式。
 */
object RelationshipEventDto {

    /** 作用方向：只有积极 / 消极两档。 */
    object Polarity {
        const val POSITIVE = "positive"
        const val NEGATIVE = "negative"
    }

    /** 写入原因的最小长度，与后端 `REASON_MIN_LEN` 保持一致。 */
    const val REASON_MIN_LEN = 8

    @JsonClass(generateAdapter = true)
    data class RelationshipEventResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "event_time") val eventTime: String,
        @Json(name = "description") val description: String? = null,
        @Json(name = "reason") val reason: String,
        @Json(name = "polarity") val polarity: String,
        @Json(name = "created_by_user_id") val createdByUserId: Long? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CreateEventRequest(
        @Json(name = "title") val title: String,
        @Json(name = "event_time") val eventTime: String,
        @Json(name = "description") val description: String? = null,
        @Json(name = "reason") val reason: String,
        @Json(name = "polarity") val polarity: String,
    )

    @JsonClass(generateAdapter = true)
    data class UpdateEventRequest(
        @Json(name = "title") val title: String? = null,
        @Json(name = "event_time") val eventTime: String? = null,
        @Json(name = "description") val description: String? = null,
        @Json(name = "reason") val reason: String? = null,
        @Json(name = "polarity") val polarity: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class EventListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<RelationshipEventResponse> = emptyList(),
    )
}
