package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DualPerspectiveRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun createEvent(request: DualPerspectiveDto.CreateEventRequest): Result<DualPerspectiveDto.DualEventResponse?> {
        return try {
            val response = apiService.createDualEvent(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getEvents(): Result<DualPerspectiveDto.DualEventListResponse?> {
        return try {
            val response = apiService.getDualEvents()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getEventDetail(eventId: Long): Result<DualPerspectiveDto.DualEventDetailResponse?> {
        return try {
            val response = apiService.getDualEventDetail(eventId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitRecord(
        eventId: Long,
        request: DualPerspectiveDto.SubmitRecordRequest,
    ): Result<DualPerspectiveDto.DualRecordResponse?> {
        return try {
            val response = apiService.submitDualRecord(eventId, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateRecord(
        eventId: Long,
        recordId: Long,
        request: DualPerspectiveDto.UpdateRecordRequest,
    ): Result<DualPerspectiveDto.DualRecordResponse?> {
        return try {
            val response = apiService.updateDualRecord(eventId, recordId, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revealRecords(eventId: Long): Result<DualPerspectiveDto.DualEventDetailResponse?> {
        return try {
            val response = apiService.revealDualRecords(eventId)
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
