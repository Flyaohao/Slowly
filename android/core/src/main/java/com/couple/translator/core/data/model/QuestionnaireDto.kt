package com.couple.translator.core.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object QuestionnaireDto {

    @JsonClass(generateAdapter = true)
    data class QuestionnaireResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "title") val title: String,
        @Json(name = "description") val description: String? = null,
        @Json(name = "version") val version: Int = 1,
        @Json(name = "status") val status: String = "active",
    )

    @JsonClass(generateAdapter = true)
    data class QuestionResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "questionnaire_id") val questionnaireId: Long,
        @Json(name = "question_text") val questionText: String,
        @Json(name = "question_type") val questionType: String,
        @Json(name = "dimension_key") val dimensionKey: String? = null,
        @Json(name = "is_required") val isRequired: Boolean = true,
        @Json(name = "weight") val weight: Float = 1f,
        @Json(name = "sort_order") val sortOrder: Int = 0,
        @Json(name = "options") val options: List<OptionResponse>? = null,
    )

    @JsonClass(generateAdapter = true)
    data class OptionResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "option_text") val optionText: String,
        @Json(name = "score_value") val scoreValue: Float = 0f,
        @Json(name = "sort_order") val sortOrder: Int = 0,
    )

    @JsonClass(generateAdapter = true)
    data class AnswerRequest(
        @Json(name = "question_id") val questionId: Long,
        @Json(name = "answer_value") val answerValue: Any,
    )

    @JsonClass(generateAdapter = true)
    data class AnswerResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "question_id") val questionId: Long,
        @Json(name = "answer_value") val answerValue: Any? = null,
    )

    @JsonClass(generateAdapter = true)
    data class SubmitRequest(
        @Json(name = "answers") val answers: List<AnswerRequest>,
    )

    @JsonClass(generateAdapter = true)
    data class SubmitResponse(
        @Json(name = "questionnaire_id") val questionnaireId: Long,
        @Json(name = "profile_generated") val profileGenerated: Boolean = false,
        @Json(name = "couple_profile_ready") val coupleProfileReady: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class ProgressResponse(
        @Json(name = "questionnaire_id") val questionnaireId: Long,
        @Json(name = "total_questions") val totalQuestions: Int = 0,
        @Json(name = "answered_count") val answeredCount: Int = 0,
        @Json(name = "current_question_index") val currentQuestionIndex: Int = 0,
        @Json(name = "is_submitted") val isSubmitted: Boolean = false,
        @Json(name = "answers") val answers: List<AnswerResponse> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class SaveProgressIndexRequest(
        @Json(name = "current_index") val currentIndex: Int,
    )

    @JsonClass(generateAdapter = true)
    data class DimensionAnalysis(
        @Json(name = "key") val key: String,
        @Json(name = "label") val label: String,
        @Json(name = "score") val score: Float = 0f,
        @Json(name = "level") val level: String = "",
        @Json(name = "analysis") val analysis: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class AnalysisResponse(
        @Json(name = "analysis") val analysis: String = "",
        @Json(name = "profile_type") val profileType: String,
        @Json(name = "profile_label") val profileLabel: String,
        @Json(name = "confidence") val confidence: Float,
        @Json(name = "dimension_scores") val dimensionScores: Map<String, Float> = emptyMap(),
        @Json(name = "profile_analysis") val profileAnalysis: String = "",
        @Json(name = "dimension_analyses") val dimensionAnalyses: List<DimensionAnalysis> = emptyList(),
        @Json(name = "strengths") val strengths: String = "",
        @Json(name = "growth_tips") val growthTips: List<String> = emptyList(),
        @Json(name = "communication_guide") val communicationGuide: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class SubmissionResponse(
        @Json(name = "id") val id: Long,
        @Json(name = "questionnaire_id") val questionnaireId: Long,
        @Json(name = "questionnaire_title") val questionnaireTitle: String,
        @Json(name = "total_questions") val totalQuestions: Int,
        @Json(name = "answered_count") val answeredCount: Int,
        @Json(name = "profile_type") val profileType: String? = null,
        @Json(name = "profile_summary") val profileSummary: String? = null,
        @Json(name = "dimension_scores") val dimensionScores: Map<String, Float>? = null,
        @Json(name = "analysis_text") val analysisText: String? = null,
        @Json(name = "couple_profile_ready") val coupleProfileReady: Boolean = false,
        @Json(name = "created_at") val createdAt: String? = null,
        // 结构化分析字段
        @Json(name = "profile_analysis") val profileAnalysis: String? = null,
        @Json(name = "dimension_analyses") val dimensionAnalyses: List<DimensionAnalysis>? = null,
        @Json(name = "strengths") val strengths: String? = null,
        @Json(name = "growth_tips") val growthTips: List<String>? = null,
        @Json(name = "communication_guide") val communicationGuide: String? = null,
    )
}
