package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.network.SharedApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuestionnaireRepository @Inject constructor(
    private val apiService: SharedApiService,
) {
    suspend fun getActiveQuestionnaire(): Result<QuestionnaireDto.QuestionnaireResponse> {
        return try {
            val response = apiService.getActiveQuestionnaire()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getQuestions(questionnaireId: Long): Result<List<QuestionnaireDto.QuestionResponse>> {
        return try {
            val response = apiService.getQuestions(questionnaireId)
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveAnswers(
        questionnaireId: Long,
        answers: List<QuestionnaireDto.AnswerRequest>,
    ): Result<List<QuestionnaireDto.AnswerResponse>> {
        return try {
            val response = apiService.saveAnswers(questionnaireId, QuestionnaireDto.SubmitRequest(answers))
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitQuestionnaire(
        questionnaireId: Long,
        answers: List<QuestionnaireDto.AnswerRequest>,
    ): Result<QuestionnaireDto.SubmitResponse> {
        return try {
            val response = apiService.submitQuestionnaire(questionnaireId, QuestionnaireDto.SubmitRequest(answers))
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getProgress(): Result<QuestionnaireDto.ProgressResponse> {
        return try {
            val response = apiService.getQuestionnaireProgress()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveProgressIndex(questionnaireId: Long, currentIndex: Int): Result<Unit> {
        return try {
            val response = apiService.saveProgressIndex(
                questionnaireId,
                QuestionnaireDto.SaveProgressIndexRequest(currentIndex),
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

    suspend fun analyzeQuestionnaire(questionnaireId: Long): Result<QuestionnaireDto.AnalysisResponse> {
        return try {
            val response = apiService.analyzeQuestionnaire(questionnaireId)
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSubmissionHistory(): Result<List<QuestionnaireDto.SubmissionResponse>> {
        return try {
            val response = apiService.getSubmissionHistory()
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSubmissionDetail(submissionId: Long): Result<QuestionnaireDto.SubmissionResponse> {
        return try {
            val response = apiService.getSubmissionDetail(submissionId)
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSubmission(submissionId: Long): Result<Unit> {
        return try {
            val response = apiService.deleteSubmission(submissionId)
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
