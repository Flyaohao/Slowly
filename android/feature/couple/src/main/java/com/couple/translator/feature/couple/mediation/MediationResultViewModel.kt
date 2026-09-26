package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.model.commonPoints
import com.couple.translator.feature.couple.data.model.diffPoints
import com.couple.translator.feature.couple.data.model.nextActions
import com.couple.translator.feature.couple.data.repository.MediationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val status: String = "completed",
    /**
     * §8.5-8：服务端还在后台生成总结（summarizing）。结果页此时必须显示
     * 「AI 正在生成」而不是空列表——此前一次取不到就空白，用户以为谈崩了。
     */
    val isGenerating: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
) {
    /** §8.5-6：只有已完成的调解才有「下一步」；回看历史时不再出现这些动作。 */
    val isCompleted: Boolean
        get() = status == "completed"

    /** 没有任何总结内容且不在生成中（生成失败/无内容）。 */
    val isEmpty: Boolean
        get() = !isGenerating && !isLoading &&
            commonPoints.isEmpty() && diffPoints.isEmpty() && nextActions.isEmpty()
}

sealed class MediationResultUiEvent {
    data class ShowError(val message: String) : MediationResultUiEvent()
    data class ActionCompleted(val action: String) : MediationResultUiEvent()
}

/** 结果页等后台总结的上限：30 次 × 3s ≈ 90s，之后交给用户手动重进。 */
private const val MAX_POLLS = 30
private const val POLL_INTERVAL_MS = 3_000L

@HiltViewModel
class MediationResultViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationResultUiState())
    val uiState: StateFlow<MediationResultUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationResultUiEvent>()
    val event: SharedFlow<MediationResultUiEvent> = _event.asSharedFlow()

    private var pollJob: Job? = null

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
    }

    fun loadResult() = fetch()

    /**
     * §8.5-8：总结在后台生成，结果页要等它。轮询上限 [MAX_POLLS] 次，
     * 到点仍未完成就提示「稍后重新进入查看」——状态在服务端，重进一定看得到，
     * 不必把用户永远卡在加载圈上。§8.5-6：已完成的会话一次就拿到，不轮询。
     */
    fun startPollingUntilReady() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            fetch()
            repeat(MAX_POLLS) {
                if (!_uiState.value.isGenerating) return@launch
                delay(POLL_INTERVAL_MS)
                fetch()
            }
            if (_uiState.value.isGenerating) {
                _uiState.update {
                    it.copy(isGenerating = false, error = "总结生成得比平时久，可以稍后重新进入查看")
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun fetch() {
        val sessionId = _uiState.value.sessionId
        if (sessionId <= 0) return
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
                                status = it.mediationStatus,
                                isGenerating = it.mediationStatus == "summarizing",
                                isLoading = false,
                            )
                        }
                    } ?: _uiState.update { it.copy(isLoading = false) }
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
