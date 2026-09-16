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

    /** 读通知偏好（邮件通知开关 + 收件邮箱 + 服务端是否具备发信条件） */
    suspend fun getNotificationPref(): Result<UserDto.NotificationPrefResponse> {
        return try {
            val response = apiService.getNotificationPref()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 写通知偏好，返回服务端确认后的最新值（不乐观更新，避免本地与服务端不一致） */
    suspend fun setEmailNotify(enabled: Boolean): Result<UserDto.NotificationPrefResponse> {
        return try {
            val response = apiService.updateNotificationPref(
                UserDto.NotificationPrefUpdateRequest(emailNotifyEnabled = enabled)
            )
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
