package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.MemoryDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    /** P-C3 §4.1：筛选参数全部可选，null = 不过滤（旧行为）。 */
    suspend fun getMemories(
        source: String? = null,
        importance: Int? = null,
        since: String? = null,
    ): Result<List<MemoryDto.MemoryItem>> {
        return try {
            val response = apiService.getMemories(
                source = source,
                importance = importance,
                since = since,
            )
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

    /** P-C3 §4.2：标星（2）/取消标星（0）。 */
    suspend fun updateImportance(memoryId: Long, importance: Int): Result<Unit> {
        return try {
            val response = apiService.updateMemoryImportance(
                memoryId,
                MemoryDto.ImportanceUpdateRequest(importance),
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
