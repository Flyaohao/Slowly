package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.WishlistDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WishlistRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun createWishlist(request: WishlistDto.CreateWishlistRequest): Result<WishlistDto.WishlistResponse?> {
        return try {
            val response = apiService.createWishlist(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWishlists(): Result<WishlistDto.WishlistListResponse?> {
        return try {
            val response = apiService.getWishlists()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateWishlist(
        id: Long,
        request: WishlistDto.UpdateWishlistRequest,
    ): Result<WishlistDto.WishlistResponse?> {
        return try {
            val response = apiService.updateWishlist(id, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeWishlist(id: Long): Result<WishlistDto.WishlistResponse?> {
        return try {
            val response = apiService.completeWishlist(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteWishlist(id: Long): Result<Unit> {
        return try {
            val response = apiService.deleteWishlist(id)
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
