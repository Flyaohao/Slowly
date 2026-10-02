package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 军师观察卡（V1 聚合版，《军师主动观察》设计文档 2026-09-28）。
 *
 * V1 无新 LLM 调用：观察正文由服务端从「最近一次已结算调解书 + 关系画像
 * 摘要」拼装；`signature` 是正文签名的服务端凭据，ack 时原样回传。
 *
 * ## 为什么三个类都要 `@JsonClass(generateAdapter = true)`（2026-10-02）
 *
 * `ObservationResponse` 是 Retrofit 接口 `CoupleApiService.getObservation()`
 * 的返回类型（`CoupleApiService.kt:449`），**会被 Moshi 真实反序列化**。
 * 这三个类原先零注解，于是落到 `NetworkModule.kt:41` 注册的
 * `KotlinJsonAdapterFactory` 走反射；release 开启 R8 后泛型签名被抹，
 * 反射拿不到 `ParameterizedType`，登录后首次拉观察卡即抛
 * `Class cannot be cast to ParameterizedType`。
 *
 * debug 包不混淆，所以这个缺陷在 debug 上完全复现不出来 —— 只有 release 才崩。
 * 加注解走 Moshi codegen 生成适配器，不依赖反射，也不依赖 R8 保留规则。
 *
 * `proguard-rules.pro` 里另有一层 keep 兜底，但**根因修在这里**。
 */
object ObservationDto {

    /** 引用素材来源（F-3 拍板：MVP 只展示来源文字，不跳转详情）。 */
    @JsonClass(generateAdapter = true)
    data class Citation(
        @Json(name = "type") val type: String? = null,
        @Json(name = "id") val id: Long? = null,
        @Json(name = "title") val title: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class ObservationResponse(
        /** null = 冷启动（没有任何可拼装素材），前端显示引导语。 */
        @Json(name = "content") val content: String? = null,
        /**
         * Kotlin 惯例是驼峰，但后端 `observation_service.py:120` 返回的
         * key 就是下划线的 `observed_at`，**不能改名** → 用 `@Json` 显式绑定，
         * 属性名本身改成驼峰以符合 Kotlin 惯例。
         */
        @Json(name = "observed_at") val observedAt: String? = null,
        @Json(name = "citation") val citation: Citation? = null,
        @Json(name = "signature") val signature: String? = null,
        @Json(name = "has_new") val hasNew: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class AckRequest(@Json(name = "signature") val signature: String)
}