package com.couple.translator.core.network

import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.data.model.AuthDto
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.data.model.UserDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 共享 API 服务
 * 包含 Auth、User、Home、Questionnaire、Profile、AI 等共享接口
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

    @GET("api/v1/profiles/history")
    suspend fun getProfileHistory(): ApiResponse<List<ProfileDto.RelationshipProfileResponse>>

    @GET("api/v1/profiles/me/ai-report")
    suspend fun getAiReport(): ApiResponse<ProfileDto.AiReportResponse>

    // AI
    @POST("api/v1/ai/chat")
    suspend fun aiChat(@Body body: AiDto.ChatRequest): ApiResponse<AiDto.ChatResponse>

    @GET("api/v1/ai/sessions")
    suspend fun getAiSessions(): ApiResponse<List<AiDto.SessionResponse>>

    @GET("api/v1/ai/sessions/{id}/messages")
    suspend fun getSessionMessages(@Path("id") sessionId: Long): ApiResponse<List<AiDto.MessageResponse>>

    @POST("api/v1/ai/sessions/{id}/feedback")
    suspend fun submitFeedback(
        @Path("id") sessionId: Long,
        @Body body: AiDto.FeedbackRequest,
    ): ApiResponse<Unit>

    @DELETE("api/v1/ai/sessions/{id}")
    suspend fun deleteSession(@Path("id") sessionId: Long): ApiResponse<Unit>

    // AI Expression Rewrite
    @POST("api/v1/ai/rewrite")
    suspend fun rewriteExpression(@Body body: AiDto.RewriteRequest): ApiResponse<AiDto.RewriteResponse>
}
