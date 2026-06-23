package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MemoryDto
import com.couple.translator.feature.couple.data.repository.MemoryRepository
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

data class MemoryUiState(
    val memories: List<MemoryDto.MemoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

sealed class MemoryUiEvent {
    data class ShowError(val message: String) : MemoryUiEvent()
    data object DeleteSuccess : MemoryUiEvent()
}

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MemoryUiEvent>()
    val event: SharedFlow<MemoryUiEvent> = _event.asSharedFlow()

    init {
        loadMemories()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            memoryRepository.getMemories().fold(
                onSuccess = { memories ->
                    _uiState.update { it.copy(memories = memories, isRefreshing = false) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isRefreshing = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    fun loadMemories() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            memoryRepository.getMemories().fold(
                onSuccess = { memories ->
                    _uiState.update { it.copy(memories = memories, isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memoryId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(memories = state.memories.filter { it.id != memoryId })
                    }
                    _event.emit(MemoryUiEvent.DeleteSuccess)
                },
                onFailure = { error ->
                    _event.emit(MemoryUiEvent.ShowError(error.message ?: "删除失败"))
                },
            )
        }
    }

    fun updateVisibility(memoryId: Long, visibility: String) {
        viewModelScope.launch {
            memoryRepository.updateVisibility(memoryId, visibility).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            memories = state.memories.map {
                                if (it.id == memoryId) it.copy(visibility = visibility) else it
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _event.emit(MemoryUiEvent.ShowError(error.message ?: "更新失败"))
                },
            )
        }
    }
}
