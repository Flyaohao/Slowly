package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PracticeRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun getPractices(): Result<List<PracticeDto.PracticeResponse>?> {
        return try {
            val response = apiService.getPractices()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startPractice(practiceId: Long): Result<PracticeDto.PracticeRecordResponse?> {
        return try {
            val response = apiService.startPractice(practiceId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitPractice(
        practiceId: Long,
        recordId: Long,
        request: PracticeDto.SubmitPracticeRequest,
    ): Result<PracticeDto.PracticeRecordResponse?> {
        return try {
            val response = apiService.submitPractice(practiceId, recordId, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecords(): Result<PracticeDto.PracticeRecordListResponse?> {
        return try {
            val response = apiService.getPracticeRecords()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecordDetail(recordId: Long): Result<PracticeDto.PracticeRecordDetailResponse?> {
        return try {
            val response = apiService.getPracticeRecordDetail(recordId)
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
