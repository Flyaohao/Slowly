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
    )

    @JsonClass(generateAdapter = true)
    data class VisibilityUpdateRequest(
        @Json(name = "visibility") val visibility: String,
    )
}
