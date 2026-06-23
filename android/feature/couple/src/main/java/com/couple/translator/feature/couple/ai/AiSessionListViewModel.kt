package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSessionListUiState(
    val sessions: List<AiDto.SessionResponse> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class AiSessionListViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiSessionListUiState())
    val uiState: StateFlow<AiSessionListUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = "") }
            aiRepository.getSessions().fold(
                onSuccess = { sessions ->
                    _uiState.update {
                        it.copy(sessions = sessions, isRefreshing = false)
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

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            aiRepository.getSessions().fold(
                onSuccess = { sessions ->
                    _uiState.update {
                        it.copy(sessions = sessions, isLoading = false)
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

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            aiRepository.deleteSession(sessionId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(sessions = state.sessions.filter { it.id != sessionId })
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = error.message ?: "删除失败")
                    }
                },
            )
        }
    }
}
