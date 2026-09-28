package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 用户级 AI 服务配置（v5.0）：`/api/v1/users/me/ai-config` 一族端点。
 *
 * key 明文只进不出：GET 返回的 [AiKeyItem.masked] 是打码串，
 * 编辑页只展示打码值，新增 key 走 [AiConfigKeys.newKeys] 追加。
 */
object AiConfigDto {

    /** GET/PUT 响应体（key 一律打码）。data=null 表示用户尚未配置。 */
    @JsonClass(generateAdapter = true)
    data class AiConfig(
        @Json(name = "provider_type") val providerType: String = "openai",
        @Json(name = "base_url") val baseUrl: String = "",
        @Json(name = "model_name") val modelName: String = "",
        @Json(name = "embedding_base_url") val embeddingBaseUrl: String = "",
        @Json(name = "embedding_model") val embeddingModel: String = "",
        @Json(name = "embedding_dim") val embeddingDim: Int? = null,
        @Json(name = "enable_rate_limit") val enableRateLimit: Boolean = true,
        @Json(name = "keys") val keys: List<AiKeyItem> = emptyList(),
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AiKeyItem(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "masked") val masked: String = "",
        @Json(name = "label") val label: String = "",
        @Json(name = "enabled") val enabled: Boolean = true,
        @Json(name = "last_error_code") val lastErrorCode: String = "",
        @Json(name = "last_error_at") val lastErrorAt: String? = null,
    )

    /** PUT 保存请求体（D10：保存前服务端强制双连通性测试）。 */
    @JsonClass(generateAdapter = true)
    data class SaveRequest(
        @Json(name = "provider_type") val providerType: String,
        @Json(name = "base_url") val baseUrl: String,
        @Json(name = "model_name") val modelName: String,
        @Json(name = "embedding_base_url") val embeddingBaseUrl: String = "",
        @Json(name = "embedding_model") val embeddingModel: String = "",
        @Json(name = "embedding_api_key") val embeddingApiKey: String = "",
        @Json(name = "enable_rate_limit") val enableRateLimit: Boolean = true,
        @Json(name = "new_keys") val newKeys: List<String> = emptyList(),
    )

    /** POST 只测不存请求体（「测试连接」按钮）。 */
    @JsonClass(generateAdapter = true)
    data class TestRequest(
        @Json(name = "provider_type") val providerType: String,
        @Json(name = "base_url") val baseUrl: String,
        @Json(name = "model_name") val modelName: String,
        @Json(name = "embedding_base_url") val embeddingBaseUrl: String = "",
        @Json(name = "embedding_model") val embeddingModel: String = "",
        @Json(name = "embedding_api_key") val embeddingApiKey: String = "",
        @Json(name = "keys") val keys: List<String> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class TestResponse(
        @Json(name = "ok") val ok: Boolean = false,
        @Json(name = "embedding_dim") val embeddingDim: Int? = null,
    )

    @JsonClass(generateAdapter = true)
    data class KeyAddRequest(
        @Json(name = "key") val key: String,
        @Json(name = "label") val label: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class KeyAddResponse(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "masked") val masked: String = "",
        @Json(name = "label") val label: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class KeyToggleRequest(
        @Json(name = "enabled") val enabled: Boolean,
    )
}
