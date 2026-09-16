package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.network.GenerationStreamDecoder
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.network.SharedApiService
import com.couple.translator.core.network.generationStreamFlow
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val apiService: SharedApiService,
    moshi: Moshi,
) {
    private val generationDecoder = GenerationStreamDecoder(moshi)
    suspend fun getMyProfile(): Result<ProfileDto.RelationshipProfileResponse?> {
        return try {
            val response = apiService.getMyProfile()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyDimensions(): Result<List<ProfileDto.DimensionScoreResponse>> {
        return try {
            val response = apiService.getMyDimensions()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCoupleProfile(): Result<ProfileDto.CoupleProfileResponse?> {
        return try {
            val response = apiService.getCoupleProfile()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getProfileHistory(): Result<List<ProfileDto.RelationshipProfileResponse>> {
        return try {
            val response = apiService.getProfileHistory()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAiReport(): Result<String> {
        return try {
            val response = apiService.getAiReport()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data.report)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 流式「AI 画像报告」（纯 Markdown 长文，无结构化字段）。
     *
     * 与旧同步端点 [getAiReport]（`GET /profiles/me/ai-report`，阻塞式一次性返回）
     * 相比：流式版本走 ai_generation 协议，正文打字机可见、可中断、落库可回读。
     * 新页面一律走流式，旧端点保留兼容。
     */
    fun profileReportStream(): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.profileReportStream()
        }

    /** 回读上次的 AI 画像报告；data 为 null 表示还没生成过，是正常情况。 */
    suspend fun getSavedProfileReport(): Result<AiDto.GenerationPayload?> {
        return try {
            val response = apiService.getProfileReportGeneration()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
