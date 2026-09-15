package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.feature.couple.network.CoupleApiService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MuseumRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    /** 上传藏品配图，返回相对 URL（如 /uploads/museum/x.jpg）。 */
    suspend fun uploadImage(bytes: ByteArray, filename: String): Result<String> {
        return try {
            val mime = when (filename.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val part = MultipartBody.Part.createFormData(
                "file", filename, bytes.toRequestBody(mime.toMediaType()),
            )
            val response = apiService.uploadMuseumImage(part)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data.imageUrl)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
