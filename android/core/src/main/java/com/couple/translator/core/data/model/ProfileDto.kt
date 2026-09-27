package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object ProfileDto {

    @JsonClass(generateAdapter = true)
    data class RelationshipProfileResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "profile_type") val profileType: String,
        @Json(name = "confidence") val confidence: Float = 0f,
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "version") val version: Int = 1,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DimensionScoreResponse(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "dimension_key") val dimensionKey: String,
        @Json(name = "score") val score: Float = 0f,
        @Json(name = "explanation") val explanation: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class ProfileDetailResponse(
        @Json(name = "profile") val profile: RelationshipProfileResponse,
        @Json(name = "dimensions") val dimensions: List<DimensionScoreResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class CoupleProfileResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "relation_id") val relationId: Long,
        @Json(name = "user_a_profile_id") val userAProfileId: Long,
        @Json(name = "user_b_profile_id") val userBProfileId: Long,
        @Json(name = "conflict_pattern") val conflictPattern: String? = null,
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "user_a_profile") val userAProfile: RelationshipProfileResponse? = null,
        @Json(name = "user_b_profile") val userBProfile: RelationshipProfileResponse? = null,
        @Json(name = "user_a_dimensions") val userADimensions: List<DimensionScoreResponse> = emptyList(),
        @Json(name = "user_b_dimensions") val userBDimensions: List<DimensionScoreResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class AiReportResponse(
        @Json(name = "report") val report: String,
    )

    // ============ 画像版本与观点补充（用户需求 #5）============
    // 画像的每一次变更都是一个版本：重新做问卷、观点补充、撤回、手动保存。
    // 前端只做「整版本」粒度的操作（看/比/撤回/删），不提供维度级改写——
    // 维度分是问卷与观点共同作用的产物，让用户直接改一格，之后任何解释都无从谈起。

    @JsonClass(generateAdapter = true)
    data class ProfileVersionResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "version") val version: Int,
        @Json(name = "profile_type") val profileType: String = "",
        @Json(name = "confidence") val confidence: Float = 0f,
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "origin") val origin: String = "questionnaire",
        /** 服务端给的中文来源标签（问卷 / 观点补充 / 撤回 / 手动保存）。 */
        @Json(name = "origin_label") val originLabel: String = "问卷",
        @Json(name = "origin_note") val originNote: String? = null,
        @Json(name = "source_viewpoint_id") val sourceViewpointId: Long? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class VersionDimensionResponse(
        @Json(name = "dimension_key") val dimensionKey: String,
        @Json(name = "label") val label: String = "",
        @Json(name = "score") val score: Float = 0f,
        @Json(name = "explanation") val explanation: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class ProfileVersionDetailResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "version") val version: Int,
        @Json(name = "profile_type") val profileType: String = "",
        @Json(name = "confidence") val confidence: Float = 0f,
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "origin") val origin: String = "questionnaire",
        @Json(name = "origin_label") val originLabel: String = "问卷",
        @Json(name = "origin_note") val originNote: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "dimensions") val dimensions: List<VersionDimensionResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class VersionDiffItemResponse(
        @Json(name = "dimension_key") val dimensionKey: String,
        @Json(name = "label") val label: String = "",
        @Json(name = "base_score") val baseScore: Float? = null,
        @Json(name = "target_score") val targetScore: Float? = null,
        @Json(name = "delta") val delta: Float? = null,
    )

    @JsonClass(generateAdapter = true)
    data class VersionDiffResponse(
        @Json(name = "items") val items: List<VersionDiffItemResponse> = emptyList(),
    )

    /**
     * 观点丰富画像的请求。
     *
     * **不含分数**：只提交「方向 + 强度」，具体移动多少分由服务端按幅度规则算
     * （单次 ≤12、相对问卷基线累计 ≤20）。客户端也不能在这里自行判定该不该写
     * ——置信度门槛与维度白名单都在服务端。
     */
    @JsonClass(generateAdapter = true)
    data class EnrichRequest(
        @Json(name = "viewpoint_id") val viewpointId: Long,
        @Json(name = "dimensions") val dimensions: List<String>,
        @Json(name = "summary") val summary: String,
        @Json(name = "directions") val directions: Map<String, EnrichDirection>,
        @Json(name = "confidence") val confidence: Float,
    )

    @JsonClass(generateAdapter = true)
    data class EnrichDirection(
        @Json(name = "direction") val direction: String,
        @Json(name = "strength") val strength: String = "mild",
    )

    @JsonClass(generateAdapter = true)
    data class EnrichResponse(
        @Json(name = "profile_id") val profileId: Long = 0,
        @Json(name = "version") val version: Int = 0,
        @Json(name = "origin") val origin: String = "",
        @Json(name = "origin_note") val originNote: String? = null,
        /** 撤回时才有：被恢复的那个版本号。 */
        @Json(name = "restored_from") val restoredFrom: Int? = null,
        @Json(name = "pruned") val pruned: Int = 0,
    )
}
