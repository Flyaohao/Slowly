package com.couple.translator.feature.single.network

import com.couple.translator.feature.single.data.model.DiaryDto
import com.couple.translator.feature.single.data.model.SelfPracticeDto
import com.couple.translator.core.network.ApiResponse
import com.couple.translator.core.network.PagedResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 单身模式 API 服务
 * 包含 Diary 等单身模式专属接口
 */
interface SingleApiService {

    // Diary (单身模式专属 — 路由前缀 /api/v1/single/diary)
    @POST("api/v1/single/diary")
    suspend fun createDiary(@Body body: DiaryDto.CreateDiaryRequest): ApiResponse<DiaryDto.DiaryResponse>

    @GET("api/v1/single/diary")
    suspend fun getDiaries(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("filter_type") filterType: String = "all",
    ): ApiResponse<PagedResponse<DiaryDto.DiaryResponse>>

    @GET("api/v1/single/diary/{id}")
    suspend fun getDiary(@Path("id") id: Long): ApiResponse<DiaryDto.DiaryResponse>

    @PUT("api/v1/single/diary/{id}")
    suspend fun updateDiary(
        @Path("id") id: Long,
        @Body body: DiaryDto.UpdateDiaryRequest,
    ): ApiResponse<DiaryDto.DiaryResponse>

    @DELETE("api/v1/single/diary/{id}")
    suspend fun deleteDiary(@Path("id") id: Long): ApiResponse<Unit>

    @POST("api/v1/single/diary/{id}/favorite")
    suspend fun toggleDiaryFavorite(@Path("id") id: Long): ApiResponse<DiaryDto.DiaryResponse>

    @POST("api/v1/single/diary/batch-delete")
    suspend fun batchDeleteDiaries(@Body body: DiaryDto.BatchDeleteRequest): ApiResponse<DiaryDto.BatchDeleteResponse>

    // Self Practice（单身模式专属 — 路由前缀 /api/v1/single/self-practices）
    @GET("api/v1/single/self-practices")
    suspend fun getSelfPractices(): ApiResponse<List<SelfPracticeDto.SelfPracticeResponse>>

    @POST("api/v1/single/self-practices/{id}/start")
    suspend fun startSelfPractice(@Path("id") practiceId: Long): ApiResponse<SelfPracticeDto.SelfPracticeRecordResponse>

    @POST("api/v1/single/self-practices/records/{rid}/submit")
    suspend fun submitSelfPractice(
        @Path("rid") recordId: Long,
        @Body body: SelfPracticeDto.SubmitSelfPracticeRequest,
    ): ApiResponse<SelfPracticeDto.SelfPracticeRecordResponse>

    // 后端返回的是分页包装 {total, items:[...]}，不是单条记录
    @GET("api/v1/single/self-practices/records")
    suspend fun getSelfPracticeRecords(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
    ): ApiResponse<SelfPracticeDto.SelfPracticeRecordListResponse>

    @GET("api/v1/single/self-practices/records/{rid}")
    suspend fun getSelfPracticeRecordDetail(@Path("rid") recordId: Long): ApiResponse<SelfPracticeDto.SelfPracticeRecordResponse>
}
