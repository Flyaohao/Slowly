package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediationRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    /** 契约 §2.3-1：start 无请求体，直接返回含 session_id / my_role 的会话快照。 */
    suspend fun startMediation(): Result<MediationDto.MediationSessionResponse?> {
        return try {
            val response = apiService.startMediation()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 契约 §2.3-3：邀请列表（role=invited 时为伴侣侧待接受邀请）。
     * 端点未实现（404）或形状不符 → Result.failure，调用方降级为空列表。
     */
    suspend fun getMediationList(role: String = "invited"): Result<List<MediationDto.MediationListItem>> {
        return try {
            val response = apiService.getMediationList(role)
            if (response.isSuccess) {
                Result.success(response.data?.items ?: emptyList())
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptMediation(sessionId: Long): Result<MediationDto.MediationSessionResponse?> {
        return try {
            val response = apiService.acceptMediation(sessionId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rejectMediation(sessionId: Long): Result<Unit> {
        return try {
            val response = apiService.rejectMediation(sessionId)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitInput(
        sessionId: Long,
        request: MediationDto.MediationInputRequest,
    ): Result<MediationDto.MediationSessionResponse?> {
        return try {
            val response = apiService.submitMediationInput(sessionId, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun confirmRewrite(
        sessionId: Long,
        request: MediationDto.MediationConfirmRequest,
    ): Result<MediationDto.MediationSessionResponse?> {
        return try {
            val response = apiService.confirmMediation(sessionId, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMediation(sessionId: Long): Result<MediationDto.MediationDetailResponse?> {
        return try {
            val response = apiService.getMediation(sessionId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun nextAction(
        sessionId: Long,
        request: MediationDto.MediationNextRequest,
    ): Result<MediationDto.MediationSessionResponse?> {
        return try {
            val response = apiService.mediationNext(sessionId, request)
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
