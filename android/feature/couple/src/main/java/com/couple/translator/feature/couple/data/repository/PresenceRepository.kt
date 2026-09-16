package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.PresenceDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresenceRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun getFeed(): Result<List<PresenceDto.MomentResponse>> {
        return try {
            val response = apiService.getPresenceFeed()
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

    suspend fun sendCompanionRequest(message: String? = null): Result<PresenceDto.MomentResponse?> {
        return try {
            val response = apiService.sendCompanionRequest(PresenceDto.CompanionRequest(message))
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 发布一条共享此刻（纯文本）。 */
    suspend fun shareMoment(content: String): Result<PresenceDto.MomentResponse?> {
        return try {
            val response = apiService.shareMoment(
                PresenceDto.MomentShareRequest(content = content)
            )
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 设置下次见面日期（ISO yyyy-MM-dd），返回更新后的日期。 */
    suspend fun setMeetDate(date: String): Result<PresenceDto.MeetDateResponse?> {
        return try {
            val response = apiService.setMeetDate(PresenceDto.MeetDateUpdate(date))
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
