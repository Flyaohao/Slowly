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

data class DualPerspectiveDetailUiState(
    val event: DualPerspectiveDto.DualEventDetailResponse? = null,
    val isLoading: Boolean = false,
    val error: String = "",
    val revealed: Boolean = false,
)

@HiltViewModel
class DualPerspectiveDetailViewModel @Inject constructor(
    private val repository: DualPerspectiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DualPerspectiveDetailUiState())
    val uiState: StateFlow<DualPerspectiveDetailUiState> = _uiState.asStateFlow()

    fun loadEvent(eventId: Long) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getEventDetail(eventId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        _uiState.update { state ->
                            state.copy(
                                event = it,
                                isLoading = false,
                                revealed = it.status == "completed",
                            )
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

    fun revealRecords(eventId: Long) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.revealRecords(eventId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        _uiState.update { state ->
                            state.copy(event = it, isLoading = false, revealed = true)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "操作失败")
                    }
                },
            )
        }
    }
}
