package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.network.SharedApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRepository @Inject constructor(
    private val apiService: SharedApiService,
) {
    suspend fun chat(request: AiDto.ChatRequest): Result<AiDto.ChatResponse> {
        return try {
            val response = apiService.aiChat(request)
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessions(): Result<List<AiDto.SessionResponse>> {
        return try {
            val response = apiService.getAiSessions()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessionMessages(sessionId: Long): Result<List<AiDto.MessageResponse>> {
        return try {
            val response = apiService.getSessionMessages(sessionId)
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitFeedback(
        sessionId: Long,
        feedback: AiDto.FeedbackRequest,
    ): Result<Unit> {
        return try {
            val response = apiService.submitFeedback(sessionId, feedback)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSession(sessionId: Long): Result<Unit> {
        return try {
            val response = apiService.deleteSession(sessionId)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rewriteExpression(text: String, context: String? = null): Result<AiDto.RewriteResponse> {
        return try {
            val response = apiService.rewriteExpression(AiDto.RewriteRequest(text, context))
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
