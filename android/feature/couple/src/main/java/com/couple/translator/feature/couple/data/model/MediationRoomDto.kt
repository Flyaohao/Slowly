package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** 共同调解室（设计文档 2026-09-28）：房间 / 三方消息 / 事件卡 / 调解书。 */
object MediationRoomDto {

    // ---------- 请求 ----------

    @JsonClass(generateAdapter = true)
    data class CreateRoomRequest(
        @Json(name = "name") val name: String,
        @Json(name = "event_time") val eventTime: String,
        @Json(name = "cause_text") val causeText: String,
        @Json(name = "process_text") val processText: String,
        @Json(name = "current_text") val currentText: String,
        @Json(name = "style_key") val styleKey: String,
    )

    @JsonClass(generateAdapter = true)
    data class PostMessageRequest(
        @Json(name = "content") val content: String,
        @Json(name = "mention") val mention: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class SupplementRequest(
        @Json(name = "content") val content: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class EndVoteRequest(
        @Json(name = "action") val action: String,
    )

    @JsonClass(generateAdapter = true)
    data class AdvisorStreamRequest(
        @Json(name = "token") val token: String,
    )

    // ---------- 响应 ----------

    @JsonClass(generateAdapter = true)
    data class PostMessageResponse(
        @Json(name = "message_id") val messageId: Long = 0,
        @Json(name = "advisor_claimed") val advisorClaimed: Boolean = false,
        @Json(name = "advisor_busy") val advisorBusy: Boolean = false,
        @Json(name = "token") val token: String? = null,
    )

    /** 补充（§五.2）响应：与 postMessage 同构，token 用于本端直开军师流。 */
    @JsonClass(generateAdapter = true)
    data class SupplementResponse(
        @Json(name = "advisor_claimed") val advisorClaimed: Boolean = false,
        @Json(name = "token") val token: String? = null,
    )

    /** 军师风格（§六 注册表制；后端 list_styles 直出）。 */
    @JsonClass(generateAdapter = true)
    data class StyleItem(
        @Json(name = "key") val key: String = "",
        @Json(name = "label") val label: String = "",
        @Json(name = "description") val description: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class RoomSummary(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "name") val name: String = "",
        @Json(name = "event_time") val eventTime: String = "",
        @Json(name = "style_key") val styleKey: String = "",
        @Json(name = "status") val status: String = "active",
        @Json(name = "advisor_phase") val advisorPhase: String = "engaged",
        @Json(name = "creator_user_id") val creatorUserId: Long = 0,
        @Json(name = "last_message_at") val lastMessageAt: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RoomListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "active_count") val activeCount: Int = 0,
        @Json(name = "items") val items: List<RoomSummary> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class Settlement(
        @Json(name = "summary_text") val summaryText: String = "",
        @Json(name = "agreements") val agreements: List<String> = emptyList(),
        @Json(name = "responsibilities") val responsibilities: Responsibilities? = null,
        /** 双方称呼（后端 party_labels 按 gender 实时计算；禁前端硬编码女方/男方，D7） */
        @Json(name = "party_labels") val partyLabels: PartyLabels? = null,
        @Json(name = "generated_at") val generatedAt: String? = null,
        @Json(name = "result_label") val resultLabel: String? = null,
    ) {
        @JsonClass(generateAdapter = true)
        data class Responsibilities(
            @Json(name = "user_a") val userA: String = "",
            @Json(name = "user_b") val userB: String = "",
        )

        @JsonClass(generateAdapter = true)
        data class PartyLabels(
            @Json(name = "user_a") val userA: String = "",
            @Json(name = "user_b") val userB: String = "",
        )
    }

    /** 房间状态快照（每次短轮询随消息带回，唯一状态真源）。 */
    @JsonClass(generateAdapter = true)
    data class RoomState(
        @Json(name = "room_id") val roomId: Long = 0,
        @Json(name = "status") val status: String = "active",
        @Json(name = "advisor_phase") val advisorPhase: String = "engaged",
        @Json(name = "round_no") val roundNo: Int = 0,
        @Json(name = "waiting_reply") val waitingReply: Boolean = false,
        // 2026-10-01：默认必须是空串而不是 "user_a"。
        // 空串让上层「身份未就绪 → 不渲染气泡」的分支生效（见
        // MediationRoomChatScreen 的 resolvedMyRole）；写死 "user_a" 会让
        // 后端漏字段时 userB 静默看到对方消息被当成自己发的。
        @Json(name = "my_role") val myRole: String = "",
        @Json(name = "agree_me") val agreeMe: Boolean = false,
        @Json(name = "agree_partner") val agreePartner: Boolean = false,
        @Json(name = "end_vote_me") val endVoteMe: Boolean = false,
        @Json(name = "end_vote_partner") val endVotePartner: Boolean = false,
        @Json(name = "confirm_me") val confirmMe: Boolean? = null,
        @Json(name = "confirm_partner") val confirmPartner: Boolean? = null,
        @Json(name = "advisor_generating") val advisorGenerating: Boolean = false,
        @Json(name = "settlement") val settlement: Settlement? = null,
        @Json(name = "result") val result: String? = null,
        @Json(name = "settle_error") val settleError: String? = null,
        @Json(name = "last_message_id") val lastMessageId: Long? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RoomMessage(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "sender_type") val senderType: String = "user_a",
        @Json(name = "sender_user_id") val senderUserId: Long? = null,
        @Json(name = "content") val content: String = "",
        @Json(name = "thinking") val thinking: String? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "round_no") val roundNo: Int? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RoomMessagesResponse(
        @Json(name = "messages") val messages: List<RoomMessage> = emptyList(),
        @Json(name = "state") val state: RoomState? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RoomDetail(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "name") val name: String = "",
        @Json(name = "event_time") val eventTime: String = "",
        @Json(name = "style_key") val styleKey: String = "",
        @Json(name = "status") val status: String = "active",
        @Json(name = "advisor_phase") val advisorPhase: String = "engaged",
        @Json(name = "creator_user_id") val creatorUserId: Long = 0,
        @Json(name = "last_message_at") val lastMessageAt: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "cause_text") val causeText: String = "",
        @Json(name = "process_text") val processText: String = "",
        @Json(name = "current_text") val currentText: String = "",
        @Json(name = "style_label") val styleLabel: String = "",
        @Json(name = "state") val state: RoomState? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SimpleActionResponse(
        @Json(name = "both_voted") val bothVoted: Boolean? = null,
        @Json(name = "both_confirmed") val bothConfirmed: Boolean? = null,
        @Json(name = "advisor_aside") val advisorAside: Boolean? = null,
        @Json(name = "waiting_reply") val waitingReply: Boolean? = null,
        @Json(name = "agree_me") val agreeMe: Boolean? = null,
        @Json(name = "end_vote_me") val endVoteMe: Boolean? = null,
        @Json(name = "end_vote_partner") val endVotePartner: Boolean? = null,
        @Json(name = "status") val status: String? = null,
        @Json(name = "retry_queued") val retryQueued: Boolean? = null,
    )

    // ---------- SSE 事件（军师发言流：meta → thinking* / delta* → done | error） ----------

    sealed interface AdvisorStreamEvent {
        data class Meta(val roomId: Long, val scene: String) : AdvisorStreamEvent
        data class Thinking(val content: String) : AdvisorStreamEvent
        data class Delta(val content: String) : AdvisorStreamEvent
        data class Done(val roundNo: Int) : AdvisorStreamEvent
        data class Failure(val code: Int, val message: String) : AdvisorStreamEvent
    }
}

// ---------- 派生视图（Moshi 红线：扩展属性放 object 外） ----------

/** 房间是否仍在「活着」的状态（角标/强提醒判定）。 */
val MediationRoomDto.RoomSummary.isActiveRoom: Boolean
    get() = status == "active" || status == "settling" || status == "settlement_ready"

/** 无活动超过 7 天（D-TIMEOUT：只提醒不代判）。 */
val MediationRoomDto.RoomSummary.staleOverAWeek: Boolean
    get() {
        if (status != "active") return false
        val iso = lastMessageAt ?: createdAt ?: return false
        val t = runCatching {
            java.time.Instant.parse(iso.replace(" ", "T") + if (iso.length == 19) "Z" else "")
        }.getOrNull() ?: return false
        return java.time.Duration.between(t, java.time.Instant.now()).toDays() >= 7
    }
