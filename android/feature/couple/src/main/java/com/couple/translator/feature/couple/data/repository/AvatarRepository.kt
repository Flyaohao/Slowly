package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.AvatarDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun getMyAvatar(): Result<AvatarDto.AvatarResponse?> {
        return try {
            val response = apiService.getMyAvatar()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAvatar(request: AvatarDto.AvatarUpdateRequest): Result<AvatarDto.AvatarResponse?> {
        return try {
            val response = apiService.updateMyAvatar(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setVoiceStyle(style: String): Result<AvatarDto.AvatarResponse?> {
        return try {
            val response = apiService.setVoiceStyle(AvatarDto.VoiceStyleRequest(style))
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
