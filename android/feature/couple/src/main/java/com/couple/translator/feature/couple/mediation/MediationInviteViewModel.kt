package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.SavedStateHandle
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

data class MediationInviteUiState(
    val sessionId: Long? = null,
    val status: String = "inviting",
    val isLoading: Boolean = false,
    val error: String = "",
)

sealed class MediationInviteUiEvent {
    data class ShowError(val message: String) : MediationInviteUiEvent()
    data object Accepted : MediationInviteUiEvent()
    data object Rejected : MediationInviteUiEvent()
}

@HiltViewModel
class MediationInviteViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationInviteUiState())
    val uiState: StateFlow<MediationInviteUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationInviteUiEvent>()
    val event: SharedFlow<MediationInviteUiEvent> = _event.asSharedFlow()

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
    }

    fun startMediation(partnerUserId: Long) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.startMediation(MediationDto.MediationStartRequest(partnerUserId)).fold(
                onSuccess = { session ->
                    session?.let {
                        _uiState.update { state ->
                            state.copy(sessionId = it.sessionId, status = it.mediationStatus, isLoading = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "发起失败") }
                },
            )
        }
    }

    fun acceptInvite() {
        val sessionId = _uiState.value.sessionId ?: return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.acceptMediation(sessionId).fold(
                onSuccess = {
                    _uiState.update { it.copy(status = "accepted", isLoading = false) }
                    _event.emit(MediationInviteUiEvent.Accepted)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "接受失败") }
                },
            )
        }
    }

    fun rejectInvite() {
        val sessionId = _uiState.value.sessionId ?: return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.rejectMediation(sessionId).fold(
                onSuccess = {
                    _uiState.update { it.copy(status = "rejected", isLoading = false) }
                    _event.emit(MediationInviteUiEvent.Rejected)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "拒绝失败") }
                },
            )
        }
    }
}
