package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoupleRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    /**
     * 从 HTTP 异常响应体中解析后端返回的错误消息
     */
    private fun parseHttpError(e: retrofit2.HttpException): String {
        return try {
            val body = e.response()?.errorBody()?.string() ?: return e.message ?: "请求失败"
            // 手动解析 JSON 提取 message 字段
            // 后端格式: {"detail": {"code": 30003, "message": "不能绑定自己"}}
            val messageRegex = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
            val matches = messageRegex.findAll(body).toList()
            // 取最后一个 message 匹配（detail 内的）
            val msg = matches.lastOrNull()?.groupValues?.get(1)
            msg ?: e.message ?: "请求失败"
        } catch (_: Exception) {
            e.message ?: "请求失败"
        }
    }

    private fun extractError(e: Exception): String {
        return if (e is retrofit2.HttpException) parseHttpError(e) else (e.message ?: "请求失败")
    }

    suspend fun generateInviteCode(): Result<CoupleDto.InviteCodeResponse?> {
        return try {
            val response = apiService.generateInviteCode()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractError(e)))
        }
    }

    suspend fun bindCouple(inviteCode: String): Result<CoupleDto.CoupleRelationResponse?> {
        return try {
            val response = apiService.bindCouple(CoupleDto.BindRequest(inviteCode))
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractError(e)))
        }
    }

    suspend fun getCoupleInfo(): Result<CoupleDto.CoupleRelationResponse?> {
        return try {
            val response = apiService.getCoupleInfo()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractError(e)))
        }
    }

    suspend fun updateSpace(name: String?, themeColor: String?): Result<CoupleDto.CoupleRelationResponse?> {
        return try {
            val response = apiService.updateCoupleSpace(CoupleDto.SpaceUpdateRequest(name, themeColor))
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractError(e)))
        }
    }

    suspend fun requestUnbind(): Result<Unit> {
        return try {
            val response = apiService.requestUnbind()
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractError(e)))
        }
    }

    suspend fun cancelUnbind(): Result<Unit> {
        return try {
            val response = apiService.cancelUnbind()
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractError(e)))
        }
    }

    suspend fun confirmUnbind(): Result<Unit> {
        return try {
            val response = apiService.confirmUnbind()
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractError(e)))
        }
    }
}
