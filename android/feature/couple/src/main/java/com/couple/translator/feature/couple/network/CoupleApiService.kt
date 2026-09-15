package com.couple.translator.feature.couple.network

import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.network.ApiResponse
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.model.AvatarDto
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.model.MemoryDto
import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.feature.couple.data.model.PresenceDto
import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.feature.couple.data.model.WishlistDto
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 情侣模式 API 服务
 * 包含 Couple、Letter、Mediation、Memory、DualPerspective、Museum、Practice、Anniversary、Wishlist 等情侣模式专属接口
 */
interface CoupleApiService {

    // Couple
    @POST("api/v1/couples/invite")
    suspend fun generateInviteCode(): ApiResponse<CoupleDto.InviteCodeResponse>

    @POST("api/v1/couples/bind")
    suspend fun bindCouple(@Body body: CoupleDto.BindRequest): ApiResponse<CoupleDto.CoupleRelationResponse>

    @GET("api/v1/couples/me")
    suspend fun getCoupleInfo(): ApiResponse<CoupleDto.CoupleRelationResponse>

    @PUT("api/v1/couples/me/space")
    suspend fun updateCoupleSpace(@Body body: CoupleDto.SpaceUpdateRequest): ApiResponse<CoupleDto.CoupleRelationResponse>

    @POST("api/v1/couples/unbind")
    suspend fun requestUnbind(): ApiResponse<Unit>

    @POST("api/v1/couples/unbind/cancel")
    suspend fun cancelUnbind(): ApiResponse<Unit>

    @POST("api/v1/couples/unbind/confirm")
    suspend fun confirmUnbind(): ApiResponse<Unit>

    // Letter
    @POST("api/v1/couple/letters")
    suspend fun createLetter(@Body body: LetterDto.LetterRequest): ApiResponse<LetterDto.LetterResponse>

    @GET("api/v1/couple/letters")
    suspend fun getLetters(
        @Query("letter_type") type: String? = null,
        @Query("status") status: String? = null,
        @Query("direction") direction: String? = null,
    ): ApiResponse<LetterDto.LetterListResponse>

    @GET("api/v1/couple/letters/{id}")
    suspend fun getLetter(@Path("id") id: Long): ApiResponse<LetterDto.LetterResponse>

    @PUT("api/v1/couple/letters/{id}")
    suspend fun updateLetter(
        @Path("id") id: Long,
        @Body body: LetterDto.LetterRequest,
    ): ApiResponse<LetterDto.LetterResponse>

    @DELETE("api/v1/couple/letters/{id}")
    suspend fun deleteLetter(@Path("id") id: Long): ApiResponse<Unit>

    @POST("api/v1/couple/letters/batch-delete")
    suspend fun batchDeleteLetters(@Body body: LetterDto.BatchDeleteRequest): ApiResponse<LetterDto.BatchDeleteResponse>

    @POST("api/v1/couple/letters/{id}/send")
    suspend fun sendLetter(@Path("id") id: Long): ApiResponse<LetterDto.LetterResponse>

    @POST("api/v1/couple/letters/{id}/favorite")
    suspend fun toggleFavorite(@Path("id") id: Long): ApiResponse<LetterDto.LetterResponse>

    @GET("api/v1/couple/letters/inbox")
    suspend fun getInbox(): ApiResponse<LetterDto.LetterListResponse>

    @GET("api/v1/couple/letters/drafts")
    suspend fun getDrafts(): ApiResponse<LetterDto.LetterListResponse>

    // AI Letter
    // 注意：后端这三个接口返回的是**嵌套结构**，不是扁平字段
    //   understand-letter -> {letter_id, analysis:{...}}
    //   rewrite-letter    -> {letter_id, rewrite:{...}}
    //   generate-reply    -> {letter_id, reply:{...}}
    @POST("api/v1/couple/ai/understand-letter")
    suspend fun understandLetter(@Body body: LetterDto.UnderstandLetterRequest): ApiResponse<LetterDto.UnderstandLetterResponse>

    /**
     * 信件「AI 帮我理解」流式接口（SSE）。`@Streaming` 是必需的，
     * 否则 Retrofit 会把整个响应体缓冲完才交给调用方，打字机效果直接失效。
     *
     * 事件序列：meta → thinking* / delta* → notice → result → done，失败给 error。
     */
    @Streaming
    @POST("api/v1/couple/ai/understand-letter/stream")
    suspend fun understandLetterStream(@Body body: LetterDto.UnderstandLetterRequest): Response<ResponseBody>

    /** 信件改写流式版。SSE 事件与 understandLetterStream 完全同一套（ai_generation 基建）。 */
    @Streaming
    @POST("api/v1/couple/ai/rewrite-letter/stream")
    suspend fun rewriteLetterStream(@Body body: LetterDto.RewriteLetterRequest): Response<ResponseBody>

    /** AI 回信建议流式版。SSE 事件与 understandLetterStream 完全同一套。 */
    @Streaming
    @POST("api/v1/couple/ai/generate-reply/stream")
    suspend fun generateReplyStream(@Body body: LetterDto.GenerateReplyRequest): Response<ResponseBody>

    /** 表达改写（帮我表达）流式版。SSE 事件与 understandLetterStream 完全同一套。 */
    @Streaming
    @POST("api/v1/couple/ai/rewrite/stream")
    suspend fun rewriteExpressionStream(@Body body: AiDto.RewriteRequest): Response<ResponseBody>

    /** AI 画像报告流式版（纯 Markdown 长文，无结构化字段）。 */
    @Streaming
    @POST("api/v1/couple/ai/profile-report/stream")
    suspend fun profileReportStream(): Response<ResponseBody>

    /**
     * 回读已保存的 AI 理解。
     *
     * 这是「退出再进来还能看到上次解读」的关键：进详情页先读，拿到就不再调模型。
     * `data` 为 null 表示这封信还没解读过。
     */
    @GET("api/v1/couple/ai/generations/letter_analysis")
    suspend fun getLetterUnderstanding(
        @Query("target_type") targetType: String = "letter",
        @Query("target_id") targetId: Long,
    ): ApiResponse<LetterDto.LetterGenerationPayload>

    /** 中断正在进行的生成，服务端会立刻停止向模型取数。 */
    @POST("api/v1/couple/ai/generations/{generation_id}/cancel")
    suspend fun cancelGeneration(
        @Path("generation_id") generationId: Long,
    ): ApiResponse<LetterDto.CancelGenerationResponse>

    @POST("api/v1/couple/ai/rewrite-letter")
    suspend fun rewriteLetter(@Body body: LetterDto.RewriteLetterRequest): ApiResponse<LetterDto.RewriteLetterResponse>

    @POST("api/v1/couple/ai/generate-reply")
    suspend fun generateReply(@Body body: LetterDto.GenerateReplyRequest): ApiResponse<LetterDto.GenerateReplyResponse>

    @POST("api/v1/couple/ai/agent")
    suspend fun agentChat(@Body body: AiDto.AgentRequest): ApiResponse<AiDto.AgentResponse>

    // Mediation
    @POST("api/v1/couple/ai/mediation/start")
    suspend fun startMediation(@Body body: MediationDto.MediationStartRequest): ApiResponse<MediationDto.MediationSessionResponse>

    @POST("api/v1/couple/ai/mediation/{id}/accept")
    suspend fun acceptMediation(@Path("id") sessionId: Long): ApiResponse<MediationDto.MediationSessionResponse>

    @POST("api/v1/couple/ai/mediation/{id}/reject")
    suspend fun rejectMediation(@Path("id") sessionId: Long): ApiResponse<MediationDto.MediationSessionResponse>

    @POST("api/v1/couple/ai/mediation/{id}/input")
    suspend fun submitMediationInput(
        @Path("id") sessionId: Long,
        @Body body: MediationDto.MediationInputRequest,
    ): ApiResponse<MediationDto.MediationSessionResponse>

    @POST("api/v1/couple/ai/mediation/{id}/confirm")
    suspend fun confirmMediation(
        @Path("id") sessionId: Long,
        @Body body: MediationDto.MediationConfirmRequest,
    ): ApiResponse<MediationDto.MediationSessionResponse>

    @GET("api/v1/couple/ai/mediation/{id}")
    suspend fun getMediation(@Path("id") sessionId: Long): ApiResponse<MediationDto.MediationDetailResponse>

    @POST("api/v1/couple/ai/mediation/{id}/next")
    suspend fun mediationNext(
        @Path("id") sessionId: Long,
        @Body body: MediationDto.MediationNextRequest,
    ): ApiResponse<MediationDto.MediationSessionResponse>

    // Memory
    // 注意：后端 `GET /ai/memory` 的 data 直接是数组，不是 {items:[...]} 包装对象
    @GET("api/v1/couple/ai/memory")
    suspend fun getMemories(): ApiResponse<List<MemoryDto.MemoryItem>>

    @DELETE("api/v1/couple/ai/memory/{id}")
    suspend fun deleteMemory(@Path("id") memoryId: Long): ApiResponse<Unit>

    @PUT("api/v1/couple/ai/memory/{id}/visibility")
    suspend fun updateMemoryVisibility(
        @Path("id") memoryId: Long,
        @Body body: MemoryDto.VisibilityUpdateRequest,
    ): ApiResponse<Unit>

    // Dual Perspective
    @POST("api/v1/couple/dual-perspectives")
    suspend fun createDualEvent(@Body body: DualPerspectiveDto.CreateEventRequest): ApiResponse<DualPerspectiveDto.DualEventResponse>

    @GET("api/v1/couple/dual-perspectives")
    suspend fun getDualEvents(): ApiResponse<DualPerspectiveDto.DualEventListResponse>

    @GET("api/v1/couple/dual-perspectives/{id}")
    suspend fun getDualEventDetail(@Path("id") eventId: Long): ApiResponse<DualPerspectiveDto.DualEventDetailResponse>

    @POST("api/v1/couple/dual-perspectives/{id}/records")
    suspend fun submitDualRecord(
        @Path("id") eventId: Long,
        @Body body: DualPerspectiveDto.SubmitRecordRequest,
    ): ApiResponse<DualPerspectiveDto.DualRecordResponse>

    @PUT("api/v1/couple/dual-perspectives/{id}/records/{rid}")
    suspend fun updateDualRecord(
        @Path("id") eventId: Long,
        @Path("rid") recordId: Long,
        @Body body: DualPerspectiveDto.UpdateRecordRequest,
    ): ApiResponse<DualPerspectiveDto.DualRecordResponse>

    @POST("api/v1/couple/dual-perspectives/{id}/reveal")
    suspend fun revealDualRecords(@Path("id") eventId: Long): ApiResponse<DualPerspectiveDto.DualEventDetailResponse>

    // Museum
    @POST("api/v1/couple/museum")
    suspend fun createMuseumItem(@Body body: MuseumDto.CreateMuseumItemRequest): ApiResponse<MuseumDto.MuseumItemResponse>

    @Multipart
    @POST("api/v1/couple/museum/upload-image")
    suspend fun uploadMuseumImage(@Part file: MultipartBody.Part): ApiResponse<MuseumDto.MuseumImageUploadResponse>

    // 后端查询参数名是 item_type，不是 type（此前传 type 等于不筛选）
    @GET("api/v1/couple/museum")
    suspend fun getMuseumItems(@Query("item_type") type: String? = null): ApiResponse<MuseumDto.MuseumListResponse>

    @GET("api/v1/couple/museum/{id}")
    suspend fun getMuseumItemDetail(@Path("id") itemId: Long): ApiResponse<MuseumDto.MuseumItemResponse>

    @PUT("api/v1/couple/museum/{id}")
    suspend fun updateMuseumItem(
        @Path("id") itemId: Long,
        @Body body: MuseumDto.UpdateMuseumItemRequest,
    ): ApiResponse<MuseumDto.MuseumItemResponse>

    @DELETE("api/v1/couple/museum/{id}")
    suspend fun deleteMuseumItem(@Path("id") itemId: Long): ApiResponse<Unit>

    @POST("api/v1/couple/museum/{id}/pin")
    suspend fun toggleMuseumPin(@Path("id") itemId: Long): ApiResponse<MuseumDto.MuseumItemResponse>

    // Avatar（AI 形象）
    @GET("api/v1/couple/avatars/me")
    suspend fun getMyAvatar(): ApiResponse<AvatarDto.AvatarResponse>

    @PUT("api/v1/couple/avatars/me")
    suspend fun updateMyAvatar(@Body body: AvatarDto.AvatarUpdateRequest): ApiResponse<AvatarDto.AvatarResponse>

    @POST("api/v1/couple/avatars/me/voice-style")
    suspend fun setVoiceStyle(@Body body: AvatarDto.VoiceStyleRequest): ApiResponse<AvatarDto.AvatarResponse>

    // Presence（在场感）
    @GET("api/v1/couple/presence/feed")
    suspend fun getPresenceFeed(): ApiResponse<List<PresenceDto.MomentResponse>>

    @POST("api/v1/couple/presence/moment")
    suspend fun shareMoment(@Body body: PresenceDto.MomentShareRequest): ApiResponse<PresenceDto.MomentResponse>

    @POST("api/v1/couple/presence/companion-request")
    suspend fun sendCompanionRequest(@Body body: PresenceDto.CompanionRequest): ApiResponse<PresenceDto.MomentResponse>

    // Practice
    @GET("api/v1/couple/practices")
    suspend fun getPractices(): ApiResponse<List<PracticeDto.PracticeResponse>>

    @POST("api/v1/couple/practices/{id}/start")
    suspend fun startPractice(@Path("id") practiceId: Long): ApiResponse<PracticeDto.PracticeRecordResponse>

    @POST("api/v1/couple/practices/records/{rid}/submit")
    suspend fun submitPractice(
        @Path("rid") recordId: Long,
        @Body body: PracticeDto.SubmitPracticeRequest,
    ): ApiResponse<PracticeDto.PracticeRecordResponse>

    @GET("api/v1/couple/practices/records")
    suspend fun getPracticeRecords(): ApiResponse<PracticeDto.PracticeRecordListResponse>

    @GET("api/v1/couple/practices/records/{rid}")
    suspend fun getPracticeRecordDetail(@Path("rid") recordId: Long): ApiResponse<PracticeDto.PracticeRecordDetailResponse>

    // Anniversary
    @POST("api/v1/couple/anniversaries")
    suspend fun createAnniversary(@Body body: AnniversaryDto.CreateAnniversaryRequest): ApiResponse<AnniversaryDto.AnniversaryResponse>

    @GET("api/v1/couple/anniversaries")
    suspend fun getAnniversaries(): ApiResponse<AnniversaryDto.AnniversaryListResponse>

    @PUT("api/v1/couple/anniversaries/{id}")
    suspend fun updateAnniversary(
        @Path("id") id: Long,
        @Body body: AnniversaryDto.UpdateAnniversaryRequest,
    ): ApiResponse<AnniversaryDto.AnniversaryResponse>

    @DELETE("api/v1/couple/anniversaries/{id}")
    suspend fun deleteAnniversary(@Path("id") id: Long): ApiResponse<Unit>

    // Wishlist
    @POST("api/v1/couple/wishlists")
    suspend fun createWishlist(@Body body: WishlistDto.CreateWishlistRequest): ApiResponse<WishlistDto.WishlistResponse>

    @GET("api/v1/couple/wishlists")
    suspend fun getWishlists(): ApiResponse<WishlistDto.WishlistListResponse>

    @PUT("api/v1/couple/wishlists/{id}")
    suspend fun updateWishlist(
        @Path("id") id: Long,
        @Body body: WishlistDto.UpdateWishlistRequest,
    ): ApiResponse<WishlistDto.WishlistResponse>

    @POST("api/v1/couple/wishlists/{id}/complete")
    suspend fun completeWishlist(@Path("id") id: Long): ApiResponse<WishlistDto.WishlistResponse>

    @DELETE("api/v1/couple/wishlists/{id}")
    suspend fun deleteWishlist(@Path("id") id: Long): ApiResponse<Unit>

    // ---------- AI 翻译官 ----------
    // 2026-09-14：这 6 个接口此前留在 core/SharedApiService 里，路径仍是旧的
    // `api/v1/ai/...`，而后端 v2.0 只注册 `api/v1/couple/ai/...`，线上实测 404。
    // AI 翻译官只存在于情侣模式，因此按 v2.0 分层迁到本接口。

    @POST("api/v1/couple/ai/chat")
    suspend fun aiChat(@Body body: AiDto.ChatRequest): ApiResponse<AiDto.ChatResponse>

    /**
     * SSE 流式对话。`@Streaming` 是必需的：否则 Retrofit 会把整个响应体缓冲完
     * 才交给调用方，打字机效果直接失效。
     */
    @Streaming
    @POST("api/v1/couple/ai/chat/stream")
    suspend fun aiChatStream(@Body body: AiDto.ChatRequest): Response<ResponseBody>

    @GET("api/v1/couple/ai/sessions")
    suspend fun getAiSessions(): ApiResponse<List<AiDto.SessionResponse>>

    /**
     * 场景清单。客户端不再硬编码场景，改为启动时拉一次。
     * 这是「后端加了场景、客户端却不知道」这类问题的根治手段。
     */
    @GET("api/v1/couple/ai/scenes")
    suspend fun getAiScenes(): ApiResponse<List<AiDto.SceneResponse>>

    @GET("api/v1/couple/ai/sessions/{id}/messages")
    suspend fun getSessionMessages(@Path("id") sessionId: Long): ApiResponse<List<AiDto.MessageResponse>>

    @POST("api/v1/couple/ai/sessions/{id}/feedback")
    suspend fun submitFeedback(
        @Path("id") sessionId: Long,
        @Body body: AiDto.FeedbackRequest,
    ): ApiResponse<Unit>

    @DELETE("api/v1/couple/ai/sessions/{id}")
    suspend fun deleteSession(@Path("id") sessionId: Long): ApiResponse<Unit>

    @POST("api/v1/couple/ai/rewrite")
    suspend fun rewriteExpression(@Body body: AiDto.RewriteRequest): ApiResponse<AiDto.RewriteResponse>
}
