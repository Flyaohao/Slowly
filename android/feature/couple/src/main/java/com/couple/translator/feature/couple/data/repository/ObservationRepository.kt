package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.network.ApiResponse
import com.couple.translator.feature.couple.data.model.ObservationDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 军师观察卡 Repository（V1 聚合版，设计文档 2026-09-28 §七）。
 *
 * 错误语义与其它 couple 模块一致：信封 code 非 0（如 30005 未绑定）
 * 一律转 [Result.failure]，调用方按「读不到 ≠ 没有」独立降级。
 */
@Singleton
class ObservationRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun getObservation(): Result<ObservationDto.ObservationResponse?> =
        runCatchingApi { apiService.getObservation() }

    suspend fun ack(signature: String): Result<Unit?> =
        runCatchingApi { apiService.ackObservation(ObservationDto.AckRequest(signature)) }

    private inline fun <T> runCatchingApi(block: () -> ApiResponse<T>): Result<T?> {
        return try {
            val response = block()
            if (response.isSuccess) Result.success(response.data)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
