package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediationRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun startMediation(request: MediationDto.MediationStartRequest): Result<MediationDto.MediationSessionResponse?> {
        return try {
            val response = apiService.startMediation(request)
            if (response.isSuccess) {
                Result.success(response.data)
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
