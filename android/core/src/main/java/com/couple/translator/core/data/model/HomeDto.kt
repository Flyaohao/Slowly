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
}
