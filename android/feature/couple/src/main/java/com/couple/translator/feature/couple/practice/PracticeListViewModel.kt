package com.couple.translator.feature.couple.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.feature.couple.data.repository.PracticeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PracticeListUiState(
    val practices: List<PracticeDto.PracticeResponse> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class PracticeListViewModel @Inject constructor(
    private val repository: PracticeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeListUiState())
    val uiState: StateFlow<PracticeListUiState> = _uiState.asStateFlow()

    init {
        loadPractices()
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadPractices() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getPractices().fold(
                onSuccess = { practices ->
                    practices?.let {
                        _uiState.update { state ->
                            state.copy(practices = it, isLoading = false)
                        }
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

    fun startPractice(practiceId: Long, onSuccess: (Long) -> Unit) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.startPractice(practiceId).fold(
                onSuccess = { record ->
                    record?.let {
                        _uiState.update { state -> state.copy(isLoading = false) }
                        onSuccess(it.id)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "发起失败")
                    }
                },
            )
        }
    }
}
