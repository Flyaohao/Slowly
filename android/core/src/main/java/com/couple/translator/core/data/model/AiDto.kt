package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * AI 翻译官相关 DTO。
 *
 * 字段名与 `backend/app/schemas/ai_schema.py` 及后端实际返回的 dict 严格对齐。
 * 2026-09-14 修正了此前三处不一致（都会导致线上失败）：
 * 1. 请求正文键名是 `message` 而非 `content`，写错后端直接 422
 * 2. `/ai/chat` 返回 `{session_id, message: {...}}` 嵌套结构，不是扁平结构
 * 3. 会话消息列表不含 `session_id`（只有外层接口知道），故该字段可空
 */
object AiDto {

    // ---------- 请求 ----------

    @JsonClass(generateAdapter = true)
    data class ChatRequest(
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "message") val message: String,
        /**
         * P-B §1.6：回答深度档位 quick / deep / expert，缺省 deep。
         * 字段名必须是 `chat_mode`（`mode` 是后端输出通道，撞名会错乱）；
         * 缺省 deep 与后端白名单回落一致，不传 = 旧客户端行为零回归。
         */
        @Json(name = "chat_mode") val chatMode: String = "deep",
    )

    @JsonClass(generateAdapter = true)
    data class RewriteRequest(
        @Json(name = "text") val text: String,
        @Json(name = "context") val context: String? = null,
    )

    /** 关系复盘请求：一次争吵/冷战/和好的经过 + 可选背景。 */
    @JsonClass(generateAdapter = true)
    data class ReviewRequest(
        @Json(name = "description") val description: String,
        @Json(name = "context") val context: String? = null,
    )

    /** 双视角对照总结请求：要总结的事件 id（双方都已提交才可用）。 */
    @JsonClass(generateAdapter = true)
    data class DualSummaryRequest(
        @Json(name = "event_id") val eventId: Long,
    )

    /** 关系练习 AI 整理请求：要整理的练习记录 id。 */
    @JsonClass(generateAdapter = true)
    data class PracticeSummaryRequest(
        @Json(name = "record_id") val recordId: Long,
    )

    /** 回忆卡片请求：anniversary / wishlist 条目。 */
    @JsonClass(generateAdapter = true)
    data class MemoryCardRequest(
        @Json(name = "target_type") val targetType: String,
        @Json(name = "target_id") val targetId: Long,
    )

    /**
     * 关系复盘的结构化结果。
     *
     * 字段名与后端 `schemas/ai_output.ReviewOutput` 逐字一致，改名会同时打断
     * 落库回读与卡片渲染两侧（见过太多「接口 200、字段全空」就是这么来的）。
     */
    @JsonClass(generateAdapter = true)
    data class ReviewResult(
        @Json(name = "summary") val summary: String = "",
        @Json(name = "trigger") val trigger: String = "",
        @Json(name = "own_need") val ownNeed: String = "",
        @Json(name = "partner_need") val partnerNeed: String = "",
        @Json(name = "misunderstanding") val misunderstanding: String = "",
        @Json(name = "escalation_phrases") val escalationPhrases: List<String> = emptyList(),
        @Json(name = "deescalation_phrases") val deescalationPhrases: List<String> = emptyList(),
        @Json(name = "next_time_scripts") val nextTimeScripts: List<String> = emptyList(),
        @Json(name = "risk_level") val riskLevel: String = "normal",
    )

    /**
     * 通用的「单次触发生成物」回读载荷。
     *
     * 刻意用 `Map` 承载 structured_output：不同 generation_kind 的结构化字段
     * 形状各不相同（信件解读 / 表达改写 / 关系复盘 …），用具体 data class 会
     * 在字段不匹配时把值静默解析成空对象。由各 ViewModel 自行按 key 取用。
     */
    @JsonClass(generateAdapter = true)
    data class GenerationPayload(
        @Json(name = "generation_id") val generationId: Long = 0,
        @Json(name = "status") val status: String = "",
        @Json(name = "content") val content: String = "",
        @Json(name = "thinking") val thinking: String = "",
        @Json(name = "structured_output") val structuredOutput: Map<String, Any?>? = null,
        @Json(name = "risk_level") val riskLevel: String = "normal",
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class FeedbackRequest(
        @Json(name = "rating") val rating: Int,
        @Json(name = "feedback_tag") val feedbackTag: String? = null,
        @Json(name = "feedback_text") val feedbackText: String? = null,
    )

    // ---------- 响应 ----------

    /**
     * `GET /api/v1/couple/ai/scenes` 的单条场景。
     *
     * 这是场景清单的**唯一权威来源**：客户端此前有 5 份互相不同步的硬编码清单，
     * 后端加了场景客户端也不知道（`letter_understand` 就是这样变成不可达的）。
     */
    @JsonClass(generateAdapter = true)
    data class SceneResponse(
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "name") val name: String,
        @Json(name = "description") val description: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class ChatResponse(
        @Json(name = "session_id") val sessionId: Long,
        @Json(name = "message") val message: MessageResponse,
        /** P0-5：军师判断依据（画像/记忆/理论）；blocked 时为 null */
        @Json(name = "evidence") val evidence: EvidencePayload? = null,
    ) {
        // 便捷访问器：让调用方写法无需改动
        val messageId: Long get() = message.id
        val role: String get() = message.role
        val content: String get() = message.content
        val structuredOutput: StructuredOutput? get() = message.structuredOutput
        val riskLevel: String? get() = message.riskLevel
    }

    /**
     * P0-5「我依据了什么」：一次回答用到的三块依据。
     *
     * 由后端 `_preprocess` 组装、零新增 LLM 调用——画像卡与 prompt 同源，
     * 记忆/理论是本轮真实召回结果。流式走独立 `evidence` 帧，非流式挂在
     * [ChatResponse.evidence]。
     */
    @JsonClass(generateAdapter = true)
    data class EvidencePayload(
        @Json(name = "scene_key") val sceneKey: String = "",
        @Json(name = "self_profile_card") val selfProfileCard: String = "",
        @Json(name = "partner_profile_card") val partnerProfileCard: String = "",
        @Json(name = "relationship_pattern") val relationshipPattern: String = "",
        @Json(name = "recalled_memories") val recalledMemories: List<EvidenceMemory> = emptyList(),
        @Json(name = "theory_chunks") val theoryChunks: List<EvidenceTheory> = emptyList(),
        @Json(name = "avatar_name") val avatarName: String = "",
        @Json(name = "voice_style") val voiceStyle: String = "",
        @Json(name = "voice_style_label") val voiceStyleLabel: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class EvidenceMemory(
        @Json(name = "content") val content: String = "",
        @Json(name = "source") val source: String = "",
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class EvidenceTheory(
        @Json(name = "title") val title: String = "",
        @Json(name = "snippet") val snippet: String = "",
        @Json(name = "score") val score: Double = 0.0,
    )

    /**
     * 结构化输出（各场景共用的一张宽表）。
     *
     * 后端每个 scene 有独立的 Pydantic 输出模型，序列化后落到同一个
     * `structured_output` 字段里，因此这里必须覆盖**所有场景的并集**——
     * 少一个字段，对应页面就会静默拿不到值（2026-09-14 冷战页即因此全空）。
     * 与后端 `app/schemas/ai_output.py` 的 `SCENE_OUTPUT_MODELS` 保持一一对应。
     */
    @JsonClass(generateAdapter = true)
    data class StructuredOutput(
        @Json(name = "summary") val summary: String? = null,
        @Json(name = "emotion_validation") val emotionValidation: String? = null,
        @Json(name = "partner_possible_meaning") val partnerPossibleMeaning: String? = null,
        @Json(name = "suggested_reply") val suggestedReply: String? = null,
        @Json(name = "do_not_say") val doNotSay: String? = null,
        @Json(name = "next_step") val nextStep: String? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "rewrites") val rewrites: List<RewriteItem>? = null,
        @Json(name = "theory_refs") val theoryRefs: List<String>? = null,
        // ---- 冷战开解（scene_key = cold_war）专属字段 ----
        @Json(name = "goal_analysis") val goalAnalysis: String? = null,
        @Json(name = "face_vs_need") val faceVsNeed: String? = null,
        @Json(name = "approach") val approach: String? = null,
        @Json(name = "approach_reason") val approachReason: String? = null,
        @Json(name = "opening_lines") val openingLines: List<String>? = null,
        @Json(name = "avoid_reminders") val avoidReminders: List<String>? = null,
        // ---- 信件解读（scene_key = letter_understand）专属字段 ----
        // 注意别和信件页的 letter_analysis 场景搞混：那个走 LetterDto.LetterUnderstanding
        @Json(name = "surface_meaning") val surfaceMeaning: String? = null,
        @Json(name = "underlying_need") val underlyingNeed: String? = null,
        @Json(name = "emotion_tone") val emotionTone: String? = null,
        // ---- 非场景业务字段 ----
        /**
         * 推理模型的思考过程（后端 `structured_output.thinking`）。
         * 流式期间通过 `thinking` 事件实时下发，落库时随 structured_output 一并保存。
         * 不是所有场景都有（非推理模型 / 非流式链路为 null）。
         */
        @Json(name = "thinking") val thinking: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteItem(
        @Json(name = "style") val style: String,
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class SessionResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "title") val title: String? = null,
        @Json(name = "privacy_level") val privacyLevel: String = "private",
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
        // P0-10B：后端已返回，Moshi 此前静默忽略——补进 DTO 供列表副标题/已结束标签
        @Json(name = "message_count") val messageCount: Int = 0,
        @Json(name = "status") val status: String? = null,
        @Json(name = "last_message_at") val lastMessageAt: String? = null,
        @Json(name = "segment_reason") val segmentReason: String? = null,
    )

    /**
     * P0-10B：GET /ai/sessions/active 的 data。
     * resumable=true → 直接续接；false 且 sessionId 非空 → 「最近一段」仅展示；
     * sessionId=null → 该 scope 无 active 会话。
     */
    @JsonClass(generateAdapter = true)
    data class ActiveSessionResponse(
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "title") val title: String? = null,
        @Json(name = "message_count") val messageCount: Int = 0,
        @Json(name = "last_message_at") val lastMessageAt: String? = null,
        @Json(name = "resumable") val resumable: Boolean = false,
    )

    /** P0-10B：POST /sessions/{id}/close 的 data */
    @JsonClass(generateAdapter = true)
    data class CloseSessionResponse(
        @Json(name = "closed") val closed: Boolean = false,
        @Json(name = "session_id") val sessionId: Long = 0,
    )

    @JsonClass(generateAdapter = true)
    data class MessageResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "role") val role: String,
        @Json(name = "content") val content: String,
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "structured_output") val structuredOutput: StructuredOutput? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteResponse(
        @Json(name = "original") val original: String,
        @Json(name = "versions") val versions: List<RewriteVersion>,
        @Json(name = "summary") val summary: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RewriteVersion(
        @Json(name = "style") val style: String,
        @Json(name = "content") val content: String,
    )

    // ---------- SSE 流式（/api/v1/couple/ai/chat/stream）----------
    // 帧格式：`event: <name>\ndata: <json>\n\n`
    // 事件序列：meta -> delta* -> done；失败给 error

    @JsonClass(generateAdapter = true)
    data class StreamMetaPayload(
        @Json(name = "session_id") val sessionId: Long,
        @Json(name = "scene_key") val sceneKey: String,
        @Json(name = "rag_hit") val ragHit: Int = 0,
    )

    @JsonClass(generateAdapter = true)
    data class StreamDeltaPayload(
        @Json(name = "content") val content: String,
    )

    /**
     * `thinking` 帧：推理模型的思考过程增量。
     *
     * 与 [StreamDeltaPayload] 分开成一个事件类型，是因为两者用途完全不同：
     * delta 是**回答正文**（要落库、要当答案读），thinking 只是**过程的可见化**
     * （不落正文，只喂给「深度思考」面板）。混在一起会让正文被思考污染。
     *
     * 推理模型实测思考首帧约 0.5s、正文首帧 18s+，这个通道是消除空屏的关键。
     */
    @JsonClass(generateAdapter = true)
    data class StreamThinkingPayload(
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class StreamDonePayload(
        @Json(name = "session_id") val sessionId: Long = 0,
        @Json(name = "message_id") val messageId: Long = 0,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "blocked") val blocked: Boolean = false,
        @Json(name = "content") val content: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class StreamErrorPayload(
        @Json(name = "code") val code: Int = 50000,
        @Json(name = "message") val message: String = "AI 服务异常，请稍后重试",
    )

    /** 客户端侧事件抽象：UI 只消费它，不关心 wire format */
    sealed interface ChatStreamEvent {
        data class Meta(val sessionId: Long, val sceneKey: String, val ragHit: Int) : ChatStreamEvent
        data class Delta(val content: String) : ChatStreamEvent

        /** 思考过程增量，喂给「深度思考」面板；不参与正文拼接 */
        data class Thinking(val content: String) : ChatStreamEvent

        /** P0-5：正文完整后的判断依据帧（evidence → done），不参与正文拼接 */
        data class Evidence(val payload: EvidencePayload) : ChatStreamEvent

        data class Done(
            val sessionId: Long,
            val messageId: Long,
            val riskLevel: String?,
            val blocked: Boolean,
            val content: String,
        ) : ChatStreamEvent

        data class Failure(val code: Int, val message: String) : ChatStreamEvent
    }
}
