package com.couple.translator.feature.couple.network

import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.network.ApiResponse
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.model.MemoryDto
import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.feature.couple.data.model.WishlistDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

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
    @POST("api/v1/couple/ai/understand-letter")
    suspend fun understandLetter(@Body body: LetterDto.UnderstandLetterRequest): ApiResponse<LetterDto.LetterUnderstanding>

    @POST("api/v1/couple/ai/rewrite-letter")
    suspend fun rewriteLetter(@Body body: LetterDto.RewriteLetterRequest): ApiResponse<LetterDto.RewriteLetterResponse>

    @POST("api/v1/couple/ai/generate-reply")
    suspend fun generateReply(@Body body: LetterDto.GenerateReplyRequest): ApiResponse<LetterDto.GenerateReplyResponse>

    // Mediation
    @POST("api/v1/couple/ai/mediation/start")
    suspend fun startMediation(@Body body: MediationDto.MediationStartRequest): ApiResponse<MediationDto.MediationSessionResponse>

    @POST("api/v1/couple/ai/mediation/{id}/accept")
    suspend fun acceptMediation(@Path("id") sessionId: Long): ApiResponse<MediationDto.MediationSessionResponse>

    @POST("api/v1/couple/ai/mediation/{id}/reject")
    suspend fun rejectMediation(@Path("id") sessionId: Long): ApiResponse<Unit>

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
    @GET("api/v1/couple/ai/memory")
    suspend fun getMemories(): ApiResponse<MemoryDto.MemoryListResponse>

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

    @GET("api/v1/couple/museum")
    suspend fun getMuseumItems(@Query("type") type: String? = null): ApiResponse<MuseumDto.MuseumListResponse>

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
}
