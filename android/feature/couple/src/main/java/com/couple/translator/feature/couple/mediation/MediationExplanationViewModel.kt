package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class MediationExplanationUiState(
    val isStarting: Boolean = false,
    val error: String = "",
)

sealed class MediationExplanationUiEvent {
    /** 契约 §2.3-1：start 成功，携带真实 session_id——导航必须带它进邀请页，废除 sessionId=0 */
    data class Started(val sessionId: Long) : MediationExplanationUiEvent()
}

/**
 * 双人调解说明页的 VM：「开始调解」= 先 `POST /ai/mediation/start` 拿真实
 * session_id（契约 §2.3-1，API 优先），成功后由页面导航 `mediation_invite?sessionId={id}`。
 * 失败展示明确错误——不再出现点了没反应或 sessionId=0 的 50001。
 */
@HiltViewModel
class MediationExplanationViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationExplanationUiState())
    val uiState: StateFlow<MediationExplanationUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationExplanationUiEvent>()
    val event: SharedFlow<MediationExplanationUiEvent> = _event.asSharedFlow()

    fun startMediation() {
        if (_uiState.value.isStarting) return
        _uiState.update { it.copy(isStarting = true, error = "") }
        viewModelScope.launch {
            mediationRepository.startMediation().fold(
                onSuccess = { session ->
                    val sessionId = session?.sessionId
                    if (sessionId != null && sessionId > 0L) {
                        _uiState.update { it.copy(isStarting = false) }
                        _event.emit(MediationExplanationUiEvent.Started(sessionId))
                    } else {
                        _uiState.update { it.copy(isStarting = false, error = "发起失败：服务端未返回会话") }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isStarting = false, error = error.message ?: "发起调解失败") }
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }
}
