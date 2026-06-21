package com.couple.translator.core.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "code") val code: Int = 0,
    @Json(name = "message") val message: String = "success",
    @Json(name = "data") val data: T? = null,
) {
    val isSuccess: Boolean get() = code == 0
}

@JsonClass(generateAdapter = true)
data class PagedResponse<T>(
    @Json(name = "total") val total: Int = 0,
    @Json(name = "items") val items: List<T> = emptyList(),
)
