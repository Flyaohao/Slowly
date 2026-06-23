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

data class QuestionnaireHistoryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val submissions: List<QuestionnaireDto.SubmissionResponse> = emptyList(),
    val error: String = "",
)

@HiltViewModel
class QuestionnaireHistoryViewModel @Inject constructor(
    private val questionnaireRepository: QuestionnaireRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuestionnaireHistoryUiState())
    val uiState: StateFlow<QuestionnaireHistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = "") }
            questionnaireRepository.getSubmissionHistory().fold(
                onSuccess = { submissions ->
                    _uiState.update {
                        it.copy(isRefreshing = false, submissions = submissions)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isRefreshing = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            questionnaireRepository.getSubmissionHistory().fold(
                onSuccess = { submissions ->
                    _uiState.update {
                        it.copy(isLoading = false, submissions = submissions)
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

    fun deleteSubmission(submissionId: Long) {
        viewModelScope.launch {
            questionnaireRepository.deleteSubmission(submissionId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(submissions = state.submissions.filter { it.id != submissionId })
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "删除失败") }
                },
            )
        }
    }
}
