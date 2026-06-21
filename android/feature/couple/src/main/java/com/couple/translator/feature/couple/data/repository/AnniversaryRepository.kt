package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnniversaryRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun createAnniversary(request: AnniversaryDto.CreateAnniversaryRequest): Result<AnniversaryDto.AnniversaryResponse?> {
        return try {
            val response = apiService.createAnniversary(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAnniversaries(): Result<AnniversaryDto.AnniversaryListResponse?> {
        return try {
            val response = apiService.getAnniversaries()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAnniversary(
        id: Long,
        request: AnniversaryDto.UpdateAnniversaryRequest,
    ): Result<AnniversaryDto.AnniversaryResponse?> {
        return try {
            val response = apiService.updateAnniversary(id, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAnniversary(id: Long): Result<Unit> {
        return try {
            val response = apiService.deleteAnniversary(id)
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
