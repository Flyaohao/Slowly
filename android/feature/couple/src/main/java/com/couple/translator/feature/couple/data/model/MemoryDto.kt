package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object MemoryDto {

    @JsonClass(generateAdapter = true)
    data class MemoryItem(
        @Json(name = "id") val id: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "relation_id") val relationId: Long? = null,
        @Json(name = "memory_type") val memoryType: String,
        @Json(name = "memory_text") val memoryText: String,
        @Json(name = "visibility") val visibility: String = "private",
        @Json(name = "created_at") val createdAt: String? = null,
        // P-C3 §4.3：后端 _to_dict 早已返回，Moshi 此前静默忽略（默认值保证向后兼容）
        @Json(name = "occurred_at") val occurredAt: String? = null,
        @Json(name = "source") val source: String? = null,
        @Json(name = "source_id") val sourceId: Long? = null,
        @Json(name = "importance") val importance: Int = 0,
    )

    @JsonClass(generateAdapter = true)
    data class VisibilityUpdateRequest(
        @Json(name = "visibility") val visibility: String,
    )

    /** P-C3 §4.2：标星（2）/取消标星（0），后端只接受这两个值 */
    @JsonClass(generateAdapter = true)
    data class ImportanceUpdateRequest(
        @Json(name = "importance") val importance: Int,
    )
}
