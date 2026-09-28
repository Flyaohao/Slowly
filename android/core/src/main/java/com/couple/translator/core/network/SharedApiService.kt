package com.couple.translator.core.network

import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.data.model.AdvisorDto
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.data.model.AuthDto
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.data.model.UserDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 共享 API 服务
 * 包含 Auth、User、Home、Couple、Questionnaire、Profile 等两种模式共用的接口。
 * AI 军师是情侣模式专属，其接口见 feature/couple 的 CoupleApiService。
 */
interface SharedApiService {

    // Home
    @GET("api/v1/home")
    suspend fun getHomeData(): ApiResponse<HomeDto.HomeResponse>

    // Auth
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: AuthDto.RegisterRequest): ApiResponse<AuthDto.RegisterResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: AuthDto.LoginRequest): ApiResponse<AuthDto.TokenResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body body: AuthDto.RefreshRequest): ApiResponse<AuthDto.TokenResponse>

    @POST("api/v1/auth/forgot-password")
    suspend fun forgotPassword(@Body body: AuthDto.ForgotPasswordRequest): ApiResponse<Unit>

    @POST("api/v1/auth/reset-password")
    suspend fun resetPassword(@Body body: AuthDto.ResetPasswordRequest): ApiResponse<Unit>

    // User
    @GET("api/v1/users/me")
    suspend fun getCurrentUser(): ApiResponse<UserDto.UserProfileResponse>

    @PUT("api/v1/users/me")
    suspend fun updateProfile(@Body body: UserDto.UpdateProfileRequest): ApiResponse<UserDto.UserProfileResponse>

    @POST("api/v1/users/me/private-password")
    suspend fun setPrivatePassword(@Body body: UserDto.PrivatePasswordRequest): ApiResponse<Unit>

    @POST("api/v1/users/me/private-verify")
    suspend fun verifyPrivatePassword(@Body body: UserDto.PrivatePasswordRequest): ApiResponse<UserDto.PrivateTokenResponse>

    @GET("api/v1/users/me/notification-pref")
    suspend fun getNotificationPref(): ApiResponse<UserDto.NotificationPrefResponse>

    @PUT("api/v1/users/me/notification-pref")
    suspend fun updateNotificationPref(
        @Body body: UserDto.NotificationPrefUpdateRequest,
    ): ApiResponse<UserDto.NotificationPrefResponse>

    // Questionnaire
    @GET("api/v1/questionnaires/active")
    suspend fun getActiveQuestionnaire(): ApiResponse<QuestionnaireDto.QuestionnaireResponse>

    @GET("api/v1/questionnaires/{id}/questions")
    suspend fun getQuestions(@Path("id") questionnaireId: Long): ApiResponse<List<QuestionnaireDto.QuestionResponse>>

    @POST("api/v1/questionnaires/{id}/answers")
    suspend fun saveAnswers(
        @Path("id") questionnaireId: Long,
        @Body body: QuestionnaireDto.SubmitRequest,
    ): ApiResponse<List<QuestionnaireDto.AnswerResponse>>

    @POST("api/v1/questionnaires/{id}/submit")
    suspend fun submitQuestionnaire(
        @Path("id") questionnaireId: Long,
        @Body body: QuestionnaireDto.SubmitRequest,
    ): ApiResponse<QuestionnaireDto.SubmitResponse>

    @GET("api/v1/questionnaires/me/progress")
    suspend fun getQuestionnaireProgress(): ApiResponse<QuestionnaireDto.ProgressResponse>

    @POST("api/v1/questionnaires/{id}/progress-index")
    suspend fun saveProgressIndex(
        @Path("id") questionnaireId: Long,
        @Body body: QuestionnaireDto.SaveProgressIndexRequest,
    ): ApiResponse<Unit>

    @POST("api/v1/questionnaires/{id}/analyze")
    suspend fun analyzeQuestionnaire(
        @Path("id") questionnaireId: Long,
    ): ApiResponse<QuestionnaireDto.AnalysisResponse>

    /**
     * 量表分析流式接口（SSE，与情侣侧 AI 各流式端点同一套 ai_generation 协议）。
     *
     * 返回裸响应体：SSE 不是 JSON，不能过 `ApiResponse<T>` 转换器；
     * `@Streaming` 也不能省——否则 okhttp 会把整个响应缓冲完再交给上层，
     * 打字机效果就没有了。
     */
    @Streaming
    @POST("api/v1/questionnaires/{id}/analyze/stream")
    suspend fun analyzeQuestionnaireStream(
        @Path("id") questionnaireId: Long,
    ): Response<ResponseBody>

    /**
     * AI 画像报告流式接口（SSE）。纯 Markdown 长文，无结构化字段。
     *
     * 端点挂在 couple 前缀下，但只要求登录、不要求已绑定（画像报告是个人维度，
     * 后端 relation_id 显式置空），单身模式同样可用。
     */
    @Streaming
    @POST("api/v1/couple/ai/profile-report/stream")
    suspend fun profileReportStream(): Response<ResponseBody>

    /** 回读上次的 AI 画像报告（ai_generation 覆盖式只留最新一条；data 为 null 表示还没生成过）。 */
    @GET("api/v1/couple/ai/generations/profile_report")
    suspend fun getProfileReportGeneration(
        @Query("target_type") targetType: String = "none",
    ): ApiResponse<AiDto.GenerationPayload>

    @GET("api/v1/questionnaires/history")
    suspend fun getSubmissionHistory(): ApiResponse<List<QuestionnaireDto.SubmissionResponse>>

    @GET("api/v1/questionnaires/history/{id}")
    suspend fun getSubmissionDetail(
        @Path("id") submissionId: Long,
    ): ApiResponse<QuestionnaireDto.SubmissionResponse>

    @DELETE("api/v1/questionnaires/history/{id}")
    suspend fun deleteSubmission(
        @Path("id") submissionId: Long,
    ): ApiResponse<Unit>

    // Profile
    @GET("api/v1/profiles/me")
    suspend fun getMyProfile(): ApiResponse<ProfileDto.RelationshipProfileResponse>

    @GET("api/v1/profiles/me/dimensions")
    suspend fun getMyDimensions(): ApiResponse<List<ProfileDto.DimensionScoreResponse>>

    @GET("api/v1/profiles/couple")
    suspend fun getCoupleProfile(): ApiResponse<ProfileDto.CoupleProfileResponse>

    /** 性格辅助信息：自己 + 伴侣的 MBTI/星座，服务端算好，星座缺失如实为 null。 */
    @GET("api/v1/profiles/personality")
    suspend fun getPersonality(): ApiResponse<ProfileDto.PersonalityInfoResponse>

    @GET("api/v1/profiles/history")
    suspend fun getProfileHistory(): ApiResponse<List<ProfileDto.RelationshipProfileResponse>>

    @GET("api/v1/profiles/me/ai-report")
    suspend fun getAiReport(): ApiResponse<ProfileDto.AiReportResponse>

    // ---- 观点分析（用户需求 #5）----
    /**
     * 观点分析流式接口（SSE）。产出「这段观点说明了什么 + 要不要写进画像」。
     *
     * 端点挂在 couple 前缀下，但只要求登录：观点是**个人资产**，
     * 后端 relation_id 显式允许为空，单身模式同样可用。
     */
    @Streaming
    @POST("api/v1/couple/ai/viewpoint-analysis/stream")
    suspend fun viewpointAnalysisStream(
        @Body body: AiDto.ViewpointAnalysisRequest,
    ): Response<ResponseBody>

    // ---- 画像版本与丰富（用户需求 #5）----
    /** 用一条观点补充画像：服务端派生新版本，**必须由用户确认后再调**。 */
    @POST("api/v1/profiles/me/enrich")
    suspend fun enrichProfile(
        @Body body: ProfileDto.EnrichRequest,
    ): ApiResponse<ProfileDto.EnrichResponse>

    @GET("api/v1/profiles/versions")
    suspend fun getProfileVersions(): ApiResponse<List<ProfileDto.ProfileVersionResponse>>

    @GET("api/v1/profiles/versions/{id}")
    suspend fun getProfileVersion(
        @Path("id") versionId: Long,
    ): ApiResponse<ProfileDto.ProfileVersionDetailResponse>

    /** 与 `base` 对比；base=0 表示以当前最新版本为基准。 */
    @GET("api/v1/profiles/versions/{id}/diff")
    suspend fun diffProfileVersion(
        @Path("id") versionId: Long,
        @Query("base") base: Long = 0L,
    ): ApiResponse<ProfileDto.VersionDiffResponse>

    @POST("api/v1/profiles/versions/{id}/restore")
    suspend fun restoreProfileVersion(
        @Path("id") versionId: Long,
    ): ApiResponse<ProfileDto.EnrichResponse>

    @DELETE("api/v1/profiles/versions/{id}")
    suspend fun deleteProfileVersion(
        @Path("id") versionId: Long,
    ): ApiResponse<Unit>

    // 观点 → 军师记忆 开关（2026-09-27）
    // 开关的初始状态一律由服务端现状决定，客户端不凭上次操作记在内存里——
    // 否则重进页面显示成「未计入」，用户一点就又写一条重复记忆。
    @GET("api/v1/couple/ai/memory/viewpoint/{id}")
    suspend fun getViewpointMemory(
        @Path("id") diaryId: Long,
    ): ApiResponse<List<ProfileDto.MemoryItemResponse>>

    @POST("api/v1/couple/ai/memory/viewpoint/{id}")
    suspend fun linkViewpointMemory(
        @Path("id") diaryId: Long,
        @Body body: ProfileDto.LinkMemoryRequest,
    ): ApiResponse<List<ProfileDto.MemoryItemResponse>>

    @DELETE("api/v1/couple/ai/memory/viewpoint/{id}")
    suspend fun unlinkViewpointMemory(
        @Path("id") diaryId: Long,
    ): ApiResponse<Unit>

    // 军师记忆沉淀总开关（关系级；关闭后服务端直接阻断三类蒸馏入口，连模型调用都不发）
    // 未绑定关系返回业务码 30005，由上层当作「分组隐藏」的正常态处理，不算故障。
    @GET("api/v1/couple/ai/memory/distill-switch")
    suspend fun getDistillSwitch(): ApiResponse<ProfileDto.DistillSwitchResponse>

    @PUT("api/v1/couple/ai/memory/distill-switch")
    suspend fun updateDistillSwitch(
        @Body body: ProfileDto.DistillSwitchUpdateRequest,
    ): ApiResponse<ProfileDto.DistillSwitchResponse>

    // Advisor settings（契约 §3.3，common 前缀，单双模式通用）
    @GET("api/v1/advisor/settings")
    suspend fun getAdvisorSettings(): ApiResponse<AdvisorDto.AdvisorSettings>

    @PUT("api/v1/advisor/settings")
    suspend fun updateAdvisorSettings(
        @Body body: AdvisorDto.AdvisorSettings,
    ): ApiResponse<AdvisorDto.AdvisorSettings>
}
