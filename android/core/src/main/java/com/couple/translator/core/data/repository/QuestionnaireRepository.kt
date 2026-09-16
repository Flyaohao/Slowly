package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.network.GenerationStreamDecoder
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.network.SharedApiService
import com.couple.translator.core.network.generationStreamFlow
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuestionnaireRepository @Inject constructor(
    private val apiService: SharedApiService,
    private val moshi: Moshi,
) {

    /** 量表分析走通用的 ai_generation SSE 协议（与信件解读/改写同一套解码） */
    private val generationDecoder = GenerationStreamDecoder(moshi)

    private val analysisAdapter by lazy {
        moshi.adapter(QuestionnaireDto.AnalysisResponse::class.java)
    }

    private val mapAdapter by lazy { moshi.adapter(Map::class.java) }

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

    /**
     * 量表分析的**流式**版本：正文打字机可见，结构化字段在 done 帧一次性带回。
     *
     * 与 [analyzeQuestionnaire] 的关系跟信件解读一样——旧同步端点保留兼容，
     * 新页面一律走流式；服务端两条路复用同一份 Prompt 与同一个输出模型，
     * 结果也都会回写 submission，所以两条路生成出来的报告是一致的。
     */
    fun analyzeQuestionnaireStream(questionnaireId: Long): Flow<GenerationStreamEvent> =
        generationStreamFlow(generationDecoder) {
            apiService.analyzeQuestionnaireStream(questionnaireId)
        }

    /**
     * 把 done 帧里的 `structured_output` 转成页面要的分析结果。
     *
     * 转不出来（服务端换了字段、或结构化片段被截断）就返回 null，
     * 由调用方决定是重试还是只展示正文，不要在这里抛异常打断收尾流程。
     */
    fun parseAnalysis(structured: Map<String, Any?>?): QuestionnaireDto.AnalysisResponse? {
        if (structured.isNullOrEmpty()) return null
        return try {
            analysisAdapter.fromJson(mapAdapter.toJson(structured))
        } catch (_: Exception) {
            null
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
