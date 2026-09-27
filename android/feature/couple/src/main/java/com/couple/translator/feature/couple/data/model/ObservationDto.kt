package com.couple.translator.feature.couple.data.model

/**
 * 军师观察卡（V1 聚合版，《军师主动观察》设计文档 2026-09-28）。
 *
 * V1 无新 LLM 调用：观察正文由服务端从「最近一次已结算调解书 + 关系画像
 * 摘要」拼装；`signature` 是正文签名的服务端凭据，ack 时原样回传。
 */
object ObservationDto {

    /** 引用素材来源（F-3 拍板：MVP 只展示来源文字，不跳转详情）。 */
    data class Citation(
        val type: String? = null,
        val id: Long? = null,
        val title: String? = null,
    )

    data class ObservationResponse(
        /** null = 冷启动（没有任何可拼装素材），前端显示引导语。 */
        val content: String? = null,
        val observed_at: String? = null,
        val citation: Citation? = null,
        val signature: String? = null,
        val has_new: Boolean = false,
    )

    data class AckRequest(val signature: String)
}
