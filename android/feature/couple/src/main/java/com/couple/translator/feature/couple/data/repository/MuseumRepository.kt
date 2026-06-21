package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MuseumRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun createItem(request: MuseumDto.CreateMuseumItemRequest): Result<MuseumDto.MuseumItemResponse?> {
        return try {
            val response = apiService.createMuseumItem(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getItems(type: String? = null): Result<MuseumDto.MuseumListResponse?> {
        return try {
            val response = apiService.getMuseumItems(type)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getItemDetail(itemId: Long): Result<MuseumDto.MuseumItemResponse?> {
        return try {
            val response = apiService.getMuseumItemDetail(itemId)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateItem(
        itemId: Long,
        request: MuseumDto.UpdateMuseumItemRequest,
    ): Result<MuseumDto.MuseumItemResponse?> {
        return try {
            val response = apiService.updateMuseumItem(itemId, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteItem(itemId: Long): Result<Unit> {
        return try {
            val response = apiService.deleteMuseumItem(itemId)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun togglePin(itemId: Long): Result<MuseumDto.MuseumItemResponse?> {
        return try {
            val response = apiService.toggleMuseumPin(itemId)
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
