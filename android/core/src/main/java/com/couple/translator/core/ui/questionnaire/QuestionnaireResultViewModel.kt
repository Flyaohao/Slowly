package com.couple.translator.core.ui.questionnaire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.data.repository.QuestionnaireRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuestionnaireResultUiState(
    val isLoading: Boolean = true,
    val isAnalyzing: Boolean = false,
    val analysis: QuestionnaireDto.AnalysisResponse? = null,
    val error: String = "",
    val coupleProfileReady: Boolean = false,
)

@HiltViewModel
class QuestionnaireResultViewModel @Inject constructor(
    private val questionnaireRepository: QuestionnaireRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuestionnaireResultUiState())
    val uiState: StateFlow<QuestionnaireResultUiState> = _uiState.asStateFlow()

    private var questionnaireId: Long = 0

    fun setCoupleProfileReady(ready: Boolean) {
        _uiState.update { it.copy(coupleProfileReady = ready) }
    }

    fun loadAnalysis(qId: Long) {
        questionnaireId = qId
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, error = "") }

            questionnaireRepository.analyzeQuestionnaire(qId).fold(
                onSuccess = { analysis ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAnalyzing = false,
                            analysis = analysis,
                            error = "",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAnalyzing = false,
                            error = error.message ?: "分析生成失败",
                        )
                    }
                },
            )
        }
    }

    fun retry() {
        loadAnalysis(questionnaireId)
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadFromSubmission(subId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            questionnaireRepository.getSubmissionDetail(subId).fold(
                onSuccess = { submission ->
                    val analysis = if (!submission.analysisText.isNullOrBlank()) {
                        QuestionnaireDto.AnalysisResponse(
                            analysis = submission.analysisText,
                            profileType = submission.profileType ?: "",
                            profileLabel = profileTypeLabels[submission.profileType] ?: submission.profileType ?: "",
                            confidence = 0f,
                            dimensionScores = submission.dimensionScores ?: emptyMap(),
                        )
                    } else {
                        // No analysis text, trigger a new analysis
                        questionnaireRepository.analyzeQuestionnaire(submission.questionnaireId).getOrNull()
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAnalyzing = false,
                            analysis = analysis,
                            coupleProfileReady = submission.coupleProfileReady,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    companion object {
        private val profileTypeLabels = mapOf(
            "secure" to "安全型依恋",
            "anxious" to "焦虑依恋型",
            "dismissive" to "疏离回避型",
            "fearful" to "恐惧回避型",
        )
    }
}
