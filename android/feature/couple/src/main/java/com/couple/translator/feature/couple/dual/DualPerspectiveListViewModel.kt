package com.couple.translator.feature.couple.dual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.repository.DualPerspectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DualPerspectiveListUiState(
    val events: List<DualPerspectiveDto.DualEventResponse> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class DualPerspectiveListViewModel @Inject constructor(
    private val repository: DualPerspectiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DualPerspectiveListUiState())
    val uiState: StateFlow<DualPerspectiveListUiState> = _uiState.asStateFlow()

    init {
        loadEvents()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            repository.getEvents().fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(events = it.items, isRefreshing = false)
                        }
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

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadEvents() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getEvents().fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(events = it.items, isLoading = false)
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
}
