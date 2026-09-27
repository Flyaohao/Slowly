package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.ProfileDto
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

    /** 军师记忆沉淀开关的操作结果。30005（未绑定关系）是正常业务态，必须和失败区分开。 */
    sealed class DistillSwitchOutcome {
        /** 已绑定关系，enabled 为服务端当前值 */
        data class Ready(val enabled: Boolean) : DistillSwitchOutcome()
        /** 未绑定关系（业务码 30005）：设置页据此整组隐藏 */
        object NoRelation : DistillSwitchOutcome()
        /** 网络失败或其他业务错误 */
        data class Failed(val message: String) : DistillSwitchOutcome()
    }

    /** 读军师记忆沉淀开关（关系级）。 */
    suspend fun getDistillSwitch(): DistillSwitchOutcome {
        return try {
            val response = apiService.getDistillSwitch()
            when {
                response.isSuccess && response.data != null ->
                    DistillSwitchOutcome.Ready(response.data.enabled)
                response.code == RELATION_NOT_BOUND ->
                    DistillSwitchOutcome.NoRelation
                else ->
                    DistillSwitchOutcome.Failed(response.message)
            }
        } catch (e: Exception) {
            DistillSwitchOutcome.Failed(e.message ?: "请求失败")
        }
    }

    /** 写军师记忆沉淀开关，以服务端返回值为准（幂等，重复设置同值无副作用）。 */
    suspend fun setDistillSwitch(enabled: Boolean): DistillSwitchOutcome {
        return try {
            val response = apiService.updateDistillSwitch(
                ProfileDto.DistillSwitchUpdateRequest(enabled = enabled)
            )
            when {
                response.isSuccess && response.data != null ->
                    DistillSwitchOutcome.Ready(response.data.enabled)
                response.code == RELATION_NOT_BOUND ->
                    DistillSwitchOutcome.NoRelation
                else ->
                    DistillSwitchOutcome.Failed(response.message)
            }
        } catch (e: Exception) {
            DistillSwitchOutcome.Failed(e.message ?: "请求失败")
        }
    }

    private companion object {
        /** 业务码：还没绑定情侣关系（军师记忆按 relation 隔离，无关系即无军师记忆） */
        const val RELATION_NOT_BOUND = 30005
    }
}
