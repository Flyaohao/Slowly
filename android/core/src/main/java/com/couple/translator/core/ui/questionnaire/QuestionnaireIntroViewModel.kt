package com.couple.translator.core.ui.questionnaire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.data.repository.QuestionnaireRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuestionnaireIntroUiState(
    val questionnaire: QuestionnaireDto.QuestionnaireResponse? = null,
    val isLoading: Boolean = true,
    val isSubmitted: Boolean = false,
    val answeredCount: Int = 0,
    val totalQuestions: Int = 0,
    val error: String = "",
)

sealed class QuestionnaireIntroUiEvent {
    data class NavigateToQuestionnaire(val questionnaireId: Long) : QuestionnaireIntroUiEvent()
    data class ShowError(val message: String) : QuestionnaireIntroUiEvent()
}

@HiltViewModel
class QuestionnaireIntroViewModel @Inject constructor(
    private val questionnaireRepository: QuestionnaireRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuestionnaireIntroUiState())
    val uiState: StateFlow<QuestionnaireIntroUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<QuestionnaireIntroUiEvent>()
    val event: SharedFlow<QuestionnaireIntroUiEvent> = _event.asSharedFlow()

    init {
        loadQuestionnaire()
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadQuestionnaire() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            questionnaireRepository.getActiveQuestionnaire().fold(
                onSuccess = { questionnaire ->
                    _uiState.update {
                        it.copy(questionnaire = questionnaire, isLoading = false)
                    }
                    // Check progress and submission status
                    checkProgress()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    private suspend fun checkProgress() {
        questionnaireRepository.getProgress().fold(
            onSuccess = { progress ->
                _uiState.update {
                    it.copy(
                        isSubmitted = progress.isSubmitted,
                        answeredCount = progress.answeredCount,
                        totalQuestions = progress.totalQuestions,
                    )
                }
            },
            onFailure = { /* No progress yet, that's fine */ },
        )
    }

    fun onStartQuestionnaire() {
        val questionnaireId = _uiState.value.questionnaire?.id ?: return
        viewModelScope.launch {
            _event.emit(QuestionnaireIntroUiEvent.NavigateToQuestionnaire(questionnaireId))
        }
    }
}
