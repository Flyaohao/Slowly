package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object HomeDto {

    @JsonClass(generateAdapter = true)
    data class HomeResponse(
        @Json(name = "relation") val relation: RelationInfo? = null,
        @Json(name = "avatar") val avatar: AvatarInfo? = null,
        @Json(name = "pending_letters") val pendingLetters: List<PendingLetter> = emptyList(),
        @Json(name = "pending_letter_count") val pendingLetterCount: Int = 0,
        @Json(name = "active_mediation") val activeMediation: MediationInfo? = null,
        @Json(name = "future_letter") val futureLetter: FutureLetterInfo? = null,
        @Json(name = "recent_museum_items") val recentMuseumItems: List<MuseumItemInfo> = emptyList(),
        @Json(name = "recommended_practices") val recommendedPractices: List<PracticeInfo> = emptyList(),
        @Json(name = "upcoming_anniversary") val upcomingAnniversary: AnniversaryInfo? = null,
        @Json(name = "space") val space: SpaceInfo? = null,
        /**
         * 收敛期新增（契约 §3.1）：军师首页任务卡，按优先级排序。
         * 后端未落地前为 null/空 → FE 不渲染任何卡片（优雅降级，旧字段全部保留）。
         */
        @Json(name = "task_cards") val taskCards: List<TaskCard> = emptyList(),
        // 单身模式顶层字段
        @Json(name = "user_nickname") val userNickname: String? = null,
        @Json(name = "user_avatar_url") val userAvatarUrl: String? = null,
        @Json(name = "recent_diaries") val recentDiaries: List<RecentDiary> = emptyList(),
        @Json(name = "has_profile") val hasProfile: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class RelationInfo(
        @Json(name = "user_nickname") val userNickname: String? = null,
        @Json(name = "user_avatar_url") val userAvatarUrl: String? = null,
        @Json(name = "partner_nickname") val partnerNickname: String? = null,
        @Json(name = "partner_avatar_url") val partnerAvatarUrl: String? = null,
        @Json(name = "love_days") val loveDays: Int? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AvatarInfo(
        @Json(name = "id") val id: Long,
        @Json(name = "name") val name: String? = null,
        @Json(name = "body_color") val bodyColor: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class PendingLetter(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String? = null,
        @Json(name = "sender_id") val senderId: Long? = null,
        @Json(name = "send_time") val sendTime: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationInfo(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class FutureLetterInfo(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String? = null,
        @Json(name = "unlock_time") val unlockTime: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MuseumItemInfo(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String,
        @Json(name = "item_type") val itemType: String,
        @Json(name = "story") val story: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class PracticeInfo(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String,
        @Json(name = "practice_type") val practiceType: String,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AnniversaryInfo(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String,
        @Json(name = "anniversary_date") val anniversaryDate: String? = null,
        /** 整改 §8.8：true = 每年重复；false = 一次性。 */
        @Json(name = "repeat_annually") val repeatAnnually: Boolean = true,
        /** 下一次发生的日期（服务端算）；一次性且已过时不会出现在这里。 */
        @Json(name = "next_occurrence_date") val nextOccurrenceDate: String? = null,
        @Json(name = "days_until") val daysUntil: Int = 0,
    )

    @JsonClass(generateAdapter = true)
    data class SpaceInfo(
        @Json(name = "name") val name: String? = null,
        @Json(name = "theme_color") val themeColor: String? = null,
        @Json(name = "next_meet_date") val nextMeetDate: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RecentDiary(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String? = null,
        @Json(name = "mood") val mood: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    /**
     * 军师首页任务卡（契约 §3.1）。
     * [type] ∈ mediation_invite / dual_perspective / pending_letter / feedback_outcome / questionnaire；
     * [route] 为可直接导航的根路由字符串（如 `mediation_invite?sessionId=12`），空则卡片不可点。
     * 全字段带默认值：后端形状漂移时 Moshi 解析失败整卡降级为空列表，不会崩页面。
     */
    @JsonClass(generateAdapter = true)
    data class TaskCard(
        @Json(name = "type") val type: String = "",
        @Json(name = "id") val id: Long = 0L,
        @Json(name = "title") val title: String = "",
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "route") val route: String? = null,
    )
}
