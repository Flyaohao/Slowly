package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.network.SharedApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRepository @Inject constructor(
    private val apiService: SharedApiService,
) {
    suspend fun getHomeData(): Result<HomeDto.HomeResponse> {
        return try {
            val response = apiService.getHomeData()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
