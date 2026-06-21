package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.MemoryDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun getMemories(): Result<List<MemoryDto.MemoryItem>> {
        return try {
            val response = apiService.getMemories()
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data.items)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMemory(memoryId: Long): Result<Unit> {
        return try {
            val response = apiService.deleteMemory(memoryId)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateVisibility(memoryId: Long, visibility: String): Result<Unit> {
        return try {
            val response = apiService.updateMemoryVisibility(
                memoryId,
                MemoryDto.VisibilityUpdateRequest(visibility),
            )
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
