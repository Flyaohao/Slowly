package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LetterRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun createLetter(request: LetterDto.LetterRequest): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.createLetter(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLetters(
        type: String? = null,
        status: String? = null,
        direction: String? = null,
    ): Result<LetterDto.LetterListResponse?> {
        return try {
            val response = apiService.getLetters(type, status, direction)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLetter(id: Long): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.getLetter(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateLetter(id: Long, request: LetterDto.LetterRequest): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.updateLetter(id, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteLetter(id: Long): Result<Unit> {
        return try {
            val response = apiService.deleteLetter(id)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun batchDeleteLetters(letterIds: List<Long>): Result<Int> {
        return try {
            val response = apiService.batchDeleteLetters(LetterDto.BatchDeleteRequest(letterIds))
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data.deletedCount)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendLetter(id: Long): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.sendLetter(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleFavorite(id: Long): Result<LetterDto.LetterResponse?> {
        return try {
            val response = apiService.toggleFavorite(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getInbox(): Result<LetterDto.LetterListResponse?> {
        return try {
            val response = apiService.getInbox()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDrafts(): Result<LetterDto.LetterListResponse?> {
        return try {
            val response = apiService.getDrafts()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun understandLetter(letterId: Long): Result<LetterDto.LetterUnderstanding?> {
        return try {
            val response = apiService.understandLetter(LetterDto.UnderstandLetterRequest(letterId))
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rewriteLetter(letterId: Long, style: String, content: String): Result<LetterDto.RewriteLetterResponse?> {
        return try {
            val response = apiService.rewriteLetter(LetterDto.RewriteLetterRequest(letterId, style, content))
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateReply(letterId: Long): Result<LetterDto.GenerateReplyResponse?> {
        return try {
            val response = apiService.generateReply(LetterDto.GenerateReplyRequest(letterId))
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
