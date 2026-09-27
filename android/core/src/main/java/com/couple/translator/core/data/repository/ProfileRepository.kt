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

    // ============ 观点分析 & 画像版本（用户需求 #5）============

    /**
     * 流式「观点分析」：读懂用户主动写下的一段观点，判断能不能写进画像。
     *
     * ⚠️ 它只产出**建议**。真正写入要用户在前端确认后调 [enrichProfile]——
     * 这条分界是刻意的：让模型直接决定画像怎么变，等于把核心资产交给一次生成。
     */
    fun viewpointAnalysisStream(viewpointId: Long): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.viewpointAnalysisStream(AiDto.ViewpointAnalysisRequest(viewpointId))
        }

    /**
     * 用一条观点补充画像。
     *
     * 请求里**没有分数**，只有方向与强度；门槛（置信度）、白名单（维度 key）、
     * 幅度（单次 ≤12 / 累计 ≤20）全部由服务端判定，客户端不重复实现——
     * 两处各写一套规则，早晚会分叉。
     */
    suspend fun enrichProfile(
        request: ProfileDto.EnrichRequest,
    ): Result<ProfileDto.EnrichResponse> {
        return try {
            val response = apiService.enrichProfile(request)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 画像历史版本（新→旧）。 */
    suspend fun getProfileVersions(): Result<List<ProfileDto.ProfileVersionResponse>> {
        return try {
            val response = apiService.getProfileVersions()
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 某个版本的完整内容（含全部维度分）。 */
    suspend fun getProfileVersion(versionId: Long): Result<ProfileDto.ProfileVersionDetailResponse> {
        return try {
            val response = apiService.getProfileVersion(versionId)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 版本差异；`base` 省略表示以当前最新版本为基准。 */
    suspend fun diffProfileVersion(
        versionId: Long,
        base: Long = 0L,
    ): Result<ProfileDto.VersionDiffResponse> {
        return try {
            val response = apiService.diffProfileVersion(versionId, base)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 撤回到某个历史版本。
     *
     * 服务端是**派生式**撤回（把目标版本的内容再派生一个新版本），
     * 所以这个动作本身也可再被撤回——不会出现「撤错了就回不去」。
     */
    suspend fun restoreProfileVersion(versionId: Long): Result<ProfileDto.EnrichResponse> {
        return try {
            val response = apiService.restoreProfileVersion(versionId)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 删除一个历史版本（当前版本与正被关系画像使用的版本删不掉）。 */
    suspend fun deleteProfileVersion(versionId: Long): Result<Unit> {
        return try {
            val response = apiService.deleteProfileVersion(versionId)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
