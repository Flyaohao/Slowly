package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.model.commonPoints
import com.couple.translator.feature.couple.data.model.diffPoints
import com.couple.translator.feature.couple.data.model.nextActions
import com.couple.translator.feature.couple.data.repository.MediationRepository
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

data class MediationResultUiState(
    val sessionId: Long = 0,
    val commonPoints: List<String> = emptyList(),
    val diffPoints: List<String> = emptyList(),
    val nextActions: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
)

sealed class MediationResultUiEvent {
    data class ShowError(val message: String) : MediationResultUiEvent()
    data class ActionCompleted(val action: String) : MediationResultUiEvent()
}

@HiltViewModel
class MediationResultViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationResultUiState())
    val uiState: StateFlow<MediationResultUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationResultUiEvent>()
    val event: SharedFlow<MediationResultUiEvent> = _event.asSharedFlow()

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
    }

    fun loadResult() {
        val sessionId = _uiState.value.sessionId
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.getMediation(sessionId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        _uiState.update { state ->
                            state.copy(
                                commonPoints = it.commonPoints,
                                diffPoints = it.diffPoints,
                                nextActions = it.nextActions,
                                isLoading = false,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    fun chooseNextAction(action: String) {
        val sessionId = _uiState.value.sessionId
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.nextAction(sessionId, MediationDto.MediationNextRequest(action)).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    _event.emit(MediationResultUiEvent.ActionCompleted(action))
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "操作失败") }
                },
            )
        }
    }
}
