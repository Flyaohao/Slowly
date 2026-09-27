package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * AI 军师相关 DTO。
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
        /** 事件发生时间（选填，`YYYY-MM-DD` / ISO8601）。格式写错后端不报错、只回落创建时间。 */
        @Json(name = "event_time") val eventTime: String? = null,
    )

    /** 「后来怎么样了」（§8.7 后续结果）：回填之后回访任务随之消失。 */
    @JsonClass(generateAdapter = true)
    data class ReviewOutcomeRequest(
        @Json(name = "outcome") val outcome: String,
    )

    /**
     * 一次复盘的**留档**（整改 §8.7，对应 `ai_relationship_review` 表）。
     *
     * 与 [ReviewResult] 分开的原因：`ReviewResult` 是**一次生成**的结构化输出，
     * 这张是**一条历史**——含用户原始输入（「重新复盘一次」要恢复它）、六项留档
     * 字段、以及后续结果与回访时间。字段名与
     * `backend/app/services/relationship_review_service.to_payload` 逐字对应。
     */
    @JsonClass(generateAdapter = true)
    data class ReviewRecord(
        @Json(name = "review_id") val reviewId: Long = 0,
        /** streaming / done / interrupted / failed */
        @Json(name = "status") val status: String = "",
        /** 用户当初输入的经过原文（历史回看时的「当时发生了什么」） */
        @Json(name = "description") val description: String = "",
        @Json(name = "context") val context: String = "",
        // ---- 契约 §8.7 六项 ----
        @Json(name = "summary") val summary: String = "",
        @Json(name = "trigger") val trigger: String = "",
        @Json(name = "own_need") val ownNeed: String = "",
        @Json(name = "partner_need") val partnerNeed: String = "",
        @Json(name = "suggested_expression") val suggestedExpression: String = "",
        @Json(name = "event_time") val eventTime: String? = null,
        // ---- 后续结果 ----
        @Json(name = "outcome") val outcome: String? = null,
        @Json(name = "outcome_at") val outcomeAt: String? = null,
        /** 回访到点时间；已回填结果后被清空（回访任务随之消失） */
        @Json(name = "recall_at") val recallAt: String? = null,
        // ---- 生成侧 ----
        @Json(name = "content") val content: String = "",
        @Json(name = "structured_output") val structuredOutput: Map<String, Any?>? = null,
        @Json(name = "risk_level") val riskLevel: String = "normal",
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    ) {
        /** 是否拿到了可展示的结果（含正文兜底），与 `relationship_review_service` 同口径。 */
        val hasResult: Boolean get() = summary.isNotBlank() || content.isNotBlank()
    }

    /** `GET /ai/review/history` 的列表项（只给列表需要的字段，详情再取）。 */
    @JsonClass(generateAdapter = true)
    data class ReviewHistoryItem(
        @Json(name = "review_id") val reviewId: Long = 0,
        @Json(name = "status") val status: String = "",
        @Json(name = "summary") val summary: String = "",
        @Json(name = "event_time") val eventTime: String? = null,
        @Json(name = "outcome") val outcome: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class ReviewHistoryResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<ReviewHistoryItem> = emptyList(),
        @Json(name = "page") val page: Int = 1,
        @Json(name = "page_size") val pageSize: Int = 20,
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
     *
     * ⚠️ 整改 §8.7：**不复用本类型做关系复盘**。复盘详情走
     * `GET /ai/review/{review_id}`（`ReviewRecord`）——`ai_generation` 是按
     * key 覆盖式的，靠它回读历史只能读到最新一条，这正是复盘「只存最新一次」
     * 的病灶。本类型保留给「一件事只留一份产物」的场景（画像报告等）。
     */
    @JsonClass(generateAdapter = true)
    data class GenerationPayload(
        @Json(name = "generation_id") val generationId: Long = 0,
        @Json(name = "status") val status: String = "",
        @Json(name = "content") val content: String = "",
        @Json(name = "structured_output") val structuredOutput: Map<String, Any?>? = null,
        @Json(name = "risk_level") val riskLevel: String = "normal",
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    /**
     * 反馈请求（整改契约 §8.3）。
     *
     * 全部字段可空：Moshi 默认不序列化 null 字段，因此「不传」= 服务端保持原值
     * （`ai_repo.create_feedback` 的 upsert 语义是 None=不改），这正是「先采纳、
     * 稍后回填结果」两次调用落在**同一行**上的前提。
     */
    @JsonClass(generateAdapter = true)
    data class FeedbackRequest(
        /** 1-5 分；「有帮助 / 没帮助」按钮映射为 5 / 1 */
        @Json(name = "rating") val rating: Int? = null,
        @Json(name = "feedback_tag") val feedbackTag: String? = null,
        @Json(name = "feedback_text") val feedbackText: String? = null,
        /** 建议是否被采用；null = 不改（不是「未采用」） */
        @Json(name = "adopted") val adopted: Boolean? = null,
        /** 实际结果 / 对方的反应（§8.3 回访） */
        @Json(name = "outcome") val outcome: String? = null,
        /**
         * 反馈落在哪条 AI 消息上；不传则服务端回落到「会话最后一条 AI 消息」。
         * 带 message_id 才能给**历史消息**补填采用/结果（服务端校验归属，越权 50002）。
         */
        @Json(name = "message_id") val messageId: Long? = null,
    )

    /** 反馈回读（提交响应 + 会话消息列表的 `feedback` 字段）。 */
    @JsonClass(generateAdapter = true)
    data class FeedbackOut(
        @Json(name = "message_id") val messageId: Long = 0L,
        @Json(name = "rating") val rating: Int? = null,
        @Json(name = "adopted") val adopted: Boolean? = null,
        @Json(name = "outcome") val outcome: String? = null,
        @Json(name = "feedback_tag") val feedbackTag: String? = null,
        @Json(name = "feedback_text") val feedbackText: String? = null,
    )

    /** `GET /ai/feedback/pending` 的 data（§8.3 待回访列表，服务端已去重） */
    @JsonClass(generateAdapter = true)
    data class PendingFeedbackResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<PendingFeedbackItem> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class PendingFeedbackItem(
        @Json(name = "session_id") val sessionId: Long = 0L,
        @Json(name = "scene_key") val sceneKey: String = "",
        @Json(name = "title") val title: String? = null,
        @Json(name = "rating") val rating: Int? = null,
        @Json(name = "adopted") val adopted: Boolean? = null,
        /** 恒为 null（筛选条件就是 outcome 为空），保留字段只为形状稳定 */
        @Json(name = "outcome") val outcome: String? = null,
        @Json(name = "message_id") val messageId: Long = 0L,
        @Json(name = "created_at") val createdAt: String? = null,
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
        // P-C2 §5：本轮省略了什么（分层预算裁剪说明）。默认空列表——
        // 旧后端不回此字段时 Moshi 走默认值，面板整栏不显示。
        @Json(name = "omitted") val omitted: List<String> = emptyList(),
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
        // ---- 调解建议动作（整改 B4.1-6）----
        /**
         * 军师是否建议「发起双人调解」（后端 `private_advisor` / `partner_translate`
         * 的输出字段 `suggest_mediation`）。
         *
         * **这里只有「建议」，没有 id**：会话由后端在用户真实点击后创建
         * （`POST /ai/mediation/start`），模型凭空产出的 id 是假的、照着跳必然 404；
         * 更不能让模型代替用户发出邀请——那是产品动作，也是隐私红线。
         *
         * 此前客户端声明的是 `@Json(ignore = true) val mediationId`，一个**永远为 null
         * 的非线路字段**：入口条件 `structured?.mediationId != null` 在生产里恒不成立，
         * 只有测试手工构造得出来，等于把「入口缺失」盖住了。
         */
        @Json(name = "suggest_mediation") val suggestMediation: Boolean = false,
        // ---- 整改 B4.3 P1-7：行动行路由的三个**结构化**输入 ----
        //
        // 为什么必须由模型给、而不是客户端按 sceneKey 猜：同一个 sceneKey 下
        // 意图可以不同——`private_advisor` 既可能在做冲突分析（该给调解），
        // 也可能只是情绪倾诉（只该给「继续对话」）。按 sceneKey 一刀切会把
        // 这两种完全不同的处置混成一件事。
        //
        // 也**禁止**从正文里做关键词猜测：正文是模型自由生成的，拿它当路由
        // 依据等于把「该不该把两个人关进同一场会话」交给一次字符串匹配。
        /**
         * 本次回答的意图（后端 `ActionIntent`）。null / 非法值一律按 unknown
         * 处理 → 只保留「复制原回答」这一条最无害的出路（fail closed）。
         */
        @Json(name = "intent") val intent: String? = null,
        /**
         * 是否**明确建议**邀请伴侣补充双视角。
         *
         * 默认 false 且必须显式为 true 才给这个动作：把伴侣拉进一次不必要的
         * 双人作业是打扰，而「缺省给」意味着任何一次解析失败都会打扰到对方。
         */
        @Json(name = "suggest_dual_perspective") val suggestDualPerspective: Boolean = false,
        /** 这次对话是否**值得存档为一次关系复盘**（有可复用的模式/触发点才 true）。 */
        @Json(name = "review_worthy") val reviewWorthy: Boolean = false,
        /**
         * 原始结构化 Map（不参与序列化）：行动行需要读后端某场景独有的键
         * （opening_lines / rewrites / event_id …），逐个补字段会漏；由
         * `AiRepository.parseStructured()` 填充，卡片按需读取。
         */
        @Json(ignore = true) val raw: Map<String, Any?>? = null,
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
        // P-C3 §3.1：上下文用量三键（服务端 SESSION_BUDGET_TOKENS 下发，客户端不硬编码 6000）
        @Json(name = "token_total") val tokenTotal: Int = 0,
        @Json(name = "budget") val budget: Int = 6000,
        @Json(name = "archive_reason") val archiveReason: String? = null,
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
        /**
         * 整改契约 §8.3：这条消息上**我的**反馈（别人看不到）。
         * 仅 assistant 消息可能非空；已填 outcome 时用于「回看已填结果」。
         */
        @Json(name = "feedback") val feedback: FeedbackOut? = null,
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
        // P-C3 §3.2：用量刷新点（meta/done/进页面，不逐帧）
        @Json(name = "token_total") val tokenTotal: Int = 0,
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
        // P-C3 §3.2：落库后的最新用量（0 = 服务端未带/落库失败，客户端不覆盖）
        @Json(name = "token_total") val tokenTotal: Int = 0,
        /**
         * 整改契约 §8.2：流式正文提取出的**场景结构化行动字段**
         * （suggested_reply / do_not_say / next_step / opening_lines …）。
         * 用 `Map` 而非 [StructuredOutput]：不同场景键不同，宽表会静默丢新键。
         * 提取失败/超时 → 服务端不带此键 → null → 卡片降级为纯正文。
         */
        @Json(name = "structured") val structured: Map<String, Any?>? = null,
    )

    @JsonClass(generateAdapter = true)
    data class StreamErrorPayload(
        @Json(name = "code") val code: Int = 50000,
        @Json(name = "message") val message: String = "AI 服务异常，请稍后重试",
    )

    /** 客户端侧事件抽象：UI 只消费它，不关心 wire format */
    sealed interface ChatStreamEvent {
        data class Meta(
            val sessionId: Long,
            val sceneKey: String,
            val ragHit: Int,
            val tokenTotal: Int = 0,
        ) : ChatStreamEvent
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
            /** P-C3 §3.2：落库后最新用量；0 表示服务端未带，不覆盖现值 */
            val tokenTotal: Int = 0,
            /** §8.2：done 帧带来的场景结构化字段；null = 提取失败/超时，卡片降级 */
            val structured: Map<String, Any?>? = null,
        ) : ChatStreamEvent

        data class Failure(val code: Int, val message: String) : ChatStreamEvent
    }
}
