package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object ProfileDto {

    @JsonClass(generateAdapter = true)
    data class RelationshipProfileResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "user_id") val userId: Long,
        @Json(name = "profile_type") val profileType: String,
        @Json(name = "profile_type_label") val profileTypeLabel: String? = null,
        @Json(name = "confidence") val confidence: Float = 0f,
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "version") val version: Int = 1,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class DimensionScoreResponse(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "dimension_key") val dimensionKey: String,
        @Json(name = "label") val label: String? = null,
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
        /**
         * 用户明确选择「就算军师把握不足也计入」时置真。
         *
         * 置信度门槛是**建议性的**：AI 判断只是建议，最终决定权在用户手里。
         * 但必须由用户的那一次点击带出来，客户端不能默认替他做主——否则
         * 这道门槛会悄悄失效。
         */
        @Json(name = "force") val force: Boolean = false,
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
        /**
         * 补充**之前**那一版的 id——「不计入」要撤回时，撤回目标就是它。
         * 让客户端自己去翻历史列表猜「上一版是谁」既脆弱又容易撤错。
         */
        @Json(name = "previous_profile_id") val previousProfileId: Long? = null,
        @Json(name = "origin") val origin: String = "",
        @Json(name = "origin_note") val originNote: String? = null,
        /** 撤回时才有：被恢复的那个版本号。 */
        @Json(name = "restored_from") val restoredFrom: Int? = null,
        @Json(name = "pruned") val pruned: Int = 0,
    )

    // ============ 性格辅助信息（人格画像页展示区，2026-09-28）============

    /**
     * 一个人的性格辅助信息（MBTI + 星座）。
     *
     * 星座由服务端按生日/时辰/出生地算好，客户端零计算；生日原值不随响应下发
     * （伴侣侧隐私最小化）。任何一项缺失都如实为 null，[filled] 供页面走
     * 「去资料页补充」引导分支。
     */
    @JsonClass(generateAdapter = true)
    data class PersonalityEntryResponse(
        @Json(name = "mbti") val mbti: String? = null,
        @Json(name = "mbti_name") val mbtiName: String? = null,
        @Json(name = "mbti_description") val mbtiDescription: String? = null,
        @Json(name = "zodiac") val zodiac: String? = null,
        @Json(name = "moon_sign") val moonSign: String? = null,
        @Json(name = "rising_sign") val risingSign: String? = null,
        @Json(name = "filled") val filled: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class PersonalityInfoResponse(
        @Json(name = "reference_note") val referenceNote: String? = null,
        @Json(name = "me") val me: PersonalityEntryResponse? = null,
        /** 未绑定伴侣时为 null（正常情况，页面降级为提示文案）。 */
        @Json(name = "partner") val partner: PersonalityEntryResponse? = null,
    )

    // ============ 观点 → 军师记忆 开关（2026-09-27）============

    /**
     * 一条军师记忆。只声明详情页要用的字段——服务端返回更多键也不影响，
     * Moshi 会忽略未声明的。
     */
    @JsonClass(generateAdapter = true)
    data class MemoryItemResponse(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "memory_type") val memoryType: String = "",
        @Json(name = "memory_text") val memoryText: String = "",
        @Json(name = "visibility") val visibility: String = "private",
        @Json(name = "source") val source: String? = null,
        @Json(name = "source_id") val sourceId: Long? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    /** 计入记忆时带上 AI 建议的类型；服务端会按白名单二次校验。 */
    @JsonClass(generateAdapter = true)
    data class LinkMemoryRequest(
        @Json(name = "memory_type") val memoryType: String? = null,
    )

    // ============ 军师记忆沉淀总开关（关系级，2026-09-27）============

    /**
     * 开关当前值。管的是 AI 自动沉淀（chat 蒸馏 / 会话摘要 / 事件蒸馏），
     * 观点里手动「计入军师记忆」是显式单条操作，不走这个开关。
     */
    @JsonClass(generateAdapter = true)
    data class DistillSwitchResponse(
        @Json(name = "enabled") val enabled: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class DistillSwitchUpdateRequest(
        @Json(name = "enabled") val enabled: Boolean,
    )
}
