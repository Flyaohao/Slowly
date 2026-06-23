package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.network.SharedApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val apiService: SharedApiService,
) {
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
}
