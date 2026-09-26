package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object AnniversaryDto {

    @JsonClass(generateAdapter = true)
    data class AnniversaryResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "title") val title: String,
        @Json(name = "anniversary_date") val anniversaryDate: String,
        /** 整改 §8.8：true = 每年重复；false = 一次性（过了就不再算「下一次」）。 */
        @Json(name = "repeat_annually") val repeatAnnually: Boolean = true,
        /**
         * 下一次发生的日期，由**服务端**算好下发。
         * 一次性且已过时为 null——此时不要自己编一个「明年」出来。
         */
        @Json(name = "next_occurrence_date") val nextOccurrenceDate: String? = null,
        /** 距下一次还有几天（服务端算）。null = 不会再发生。 */
        @Json(name = "days_until") val daysUntil: Int? = null,
        @Json(name = "description") val description: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CreateAnniversaryRequest(
        @Json(name = "title") val title: String,
        @Json(name = "anniversary_date") val anniversaryDate: String,
        @Json(name = "repeat_annually") val repeatAnnually: Boolean = true,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class UpdateAnniversaryRequest(
        @Json(name = "title") val title: String? = null,
        @Json(name = "anniversary_date") val anniversaryDate: String? = null,
        @Json(name = "repeat_annually") val repeatAnnually: Boolean? = null,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AnniversaryListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<AnniversaryResponse> = emptyList(),
    )
}
