package com.couple.translator.feature.single.network

import com.couple.translator.feature.single.data.model.DiaryDto
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
 * 观点（内部标识仍是 diary）API 服务。
 *
 * 2026-09-29：单身模式已删除。本接口类**保留**——「观点」是情侣模式的功能，
 * 抽屉入口与 GuideScreen 都指向它，路由前缀 `/api/v1/single/diary` 是历史命名，
 * 后端不校验模式（只用 get_current_user），所以情侣用户正常可用。
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

    // 2026-09-29：Self Practice 的 5 个方法已随单身模式删除（后端 features.py 早已冻结 10006、
    // 前端入口已注释）。保留 diary 相关方法不受影响。
}
