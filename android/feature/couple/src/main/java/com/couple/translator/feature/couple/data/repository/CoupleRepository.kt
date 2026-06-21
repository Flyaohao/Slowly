package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoupleRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun generateInviteCode(): Result<CoupleDto.InviteCodeResponse?> {
        return try {
            val response = apiService.generateInviteCode()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
            Result.failure(e)
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
            Result.failure(e)
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
            Result.failure(e)
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
            Result.failure(e)
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
            Result.failure(e)
        }
    }
}
