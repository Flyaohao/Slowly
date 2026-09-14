package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.model.myRewrite
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

data class MediationConfirmUiState(
    val sessionId: Long = 0,
    val myRewrite: MediationDto.MediationRewrite? = null,
    val supplement: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
)

sealed class MediationConfirmUiEvent {
    data class ShowError(val message: String) : MediationConfirmUiEvent()
    data object Confirmed : MediationConfirmUiEvent()
}

@HiltViewModel
class MediationConfirmViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationConfirmUiState())
    val uiState: StateFlow<MediationConfirmUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationConfirmUiEvent>()
    val event: SharedFlow<MediationConfirmUiEvent> = _event.asSharedFlow()

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
    }

    fun loadRewrite() {
        val sessionId = _uiState.value.sessionId
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.getMediation(sessionId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        _uiState.update { state ->
                            state.copy(myRewrite = it.myRewrite, isLoading = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    fun onSupplementChange(text: String) {
        _uiState.update { it.copy(supplement = text) }
    }

    fun confirm(confirmed: Boolean) {
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val request = MediationDto.MediationConfirmRequest(
                confirmed = confirmed,
                supplement = state.supplement.ifBlank { null },
            )
            mediationRepository.confirmRewrite(state.sessionId, request).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    _event.emit(MediationConfirmUiEvent.Confirmed)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "确认失败") }
                },
            )
        }
    }
}
