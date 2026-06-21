package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.UserDto
import com.couple.translator.core.network.SharedApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val apiService: SharedApiService,
) {
    suspend fun getCurrentUser(): Result<UserDto.UserProfileResponse> {
        return try {
            val response = apiService.getCurrentUser()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProfile(request: UserDto.UpdateProfileRequest): Result<UserDto.UserProfileResponse> {
        return try {
            val response = apiService.updateProfile(request)
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setPrivatePassword(password: String): Result<Unit> {
        return try {
            val response = apiService.setPrivatePassword(UserDto.PrivatePasswordRequest(password))
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyPrivatePassword(password: String): Result<String> {
        return try {
            val response = apiService.verifyPrivatePassword(UserDto.PrivatePasswordRequest(password))
            if (response.isSuccess && response.data != null) {
                Result.success(response.data.token)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
