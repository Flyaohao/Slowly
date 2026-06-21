package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MediationDto
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

data class MediationInputUiState(
    val sessionId: Long = 0,
    val feeling: String = "",
    val trigger: String = "",
    val wishUnderstood: String = "",
    val wishNext: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
)

sealed class MediationInputUiEvent {
    data class ShowError(val message: String) : MediationInputUiEvent()
    data object InputSubmitted : MediationInputUiEvent()
}

@HiltViewModel
class MediationInputViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationInputUiState())
    val uiState: StateFlow<MediationInputUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationInputUiEvent>()
    val event: SharedFlow<MediationInputUiEvent> = _event.asSharedFlow()

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
    }

    fun onFeelingChange(text: String) {
        _uiState.update { it.copy(feeling = text) }
    }

    fun onTriggerChange(text: String) {
        _uiState.update { it.copy(trigger = text) }
    }

    fun onWishUnderstoodChange(text: String) {
        _uiState.update { it.copy(wishUnderstood = text) }
    }

    fun onWishNextChange(text: String) {
        _uiState.update { it.copy(wishNext = text) }
    }

    fun submitInput() {
        val state = _uiState.value
        if (state.feeling.isBlank() || state.trigger.isBlank()) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val request = MediationDto.MediationInputRequest(
                feeling = state.feeling,
                trigger = state.trigger,
                wishUnderstood = state.wishUnderstood,
                wishNext = state.wishNext,
            )
            mediationRepository.submitInput(state.sessionId, request).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    _event.emit(MediationInputUiEvent.InputSubmitted)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "提交失败") }
                },
            )
        }
    }
}
