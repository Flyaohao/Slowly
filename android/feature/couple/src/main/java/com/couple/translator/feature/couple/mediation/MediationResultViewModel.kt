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
     * §8.5-8 / B4.1-4：总结在服务端后台生成时的相位（生成中 / 自动重试 / 终态失败）。
     * 结果页必须显示「AI 正在生成」而不是空列表——此前一次取不到就空白，
     * 用户以为谈崩了；失败时更要说清是**生成失败**而不是「没有总结」。
     */
    val generation: MediationGenerationState = MediationGenerationState(MediationGenerationPhase.IDLE),
    /** 窗口用尽（还在处理、但我们不再等了）：提示语与「失败」区分开。 */
    val pollingBudgetExhausted: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
) {
    /** §8.5-6：只有已完成的调解才有「下一步」；回看历史时不再出现这些动作。 */
    val isCompleted: Boolean
        get() = status == "completed"

    val isGenerating: Boolean get() = generation.isGenerating

    val hasFailure: Boolean get() = generation.hasFailure

    /**
     * 没有任何总结内容、也不在任何"还没定论"的态里（真的空）。
     *
     * 三种情况都必须排除，否则会在同一屏上同时出现「还在处理」和
     * 「这次调解没有留下总结」——后者是结论，前者是过程，摆在一起自相矛盾：
     * - 生成中：结果还没来；
     * - 有失败：有明确失败卡片（含重试），不是"没有总结"；
     * - 轮询窗口用尽：我们不再等了，但服务端还在跑。
     */
    val isEmpty: Boolean
        get() = !isGenerating && !hasFailure && !pollingBudgetExhausted && !isLoading &&
            commonPoints.isEmpty() && diffPoints.isEmpty() && nextActions.isEmpty()
}

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

    private var pollJob: Job? = null
    private var waitStartedAt = 0L

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
        waitStartedAt = 0L
    }

    fun loadResult() = fetch()

    /**
     * §8.5-8 + 整改 B4.1-5：总结在后台生成，结果页要**有上限地退避**等它。
     *
     * 旧实现是 `3s × 30 ≈ 90s` 一到期就提示「比平时久」——而服务端此时
     * 可能还在正常跑（单次 LLM 超时 120s、最多 3 次尝试 + 指数退避）。
     * 现在窗口由 [MediationPolling] 统一给出，到时**不判失败**：
     * 只停轮询并说明「仍在处理中，可稍后回来查看」。§8.5-6：已完成的会话
     * 一次就拿到，不进入轮询。
     */
    fun startPollingUntilReady() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            fetch()
            if (!_uiState.value.isGenerating) return@launch
            waitStartedAt = System.currentTimeMillis()
            var round = 0
            while (true) {
                delay(MediationPolling.nextIntervalMs(round))
                round += 1
                val state = _uiState.value
                if (!state.isGenerating) return@launch
                if (state.generation.phase == MediationGenerationPhase.GENERATING &&
                    MediationPolling.budgetExhausted(System.currentTimeMillis() - waitStartedAt)
                ) {
                    _uiState.update { it.copy(pollingBudgetExhausted = true) }
                    return@launch
                }
                fetch()
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 整改 B4.1-4：总结生成失败后的手动重试（服务端只重排这一步，不丢内容）。 */
    fun retryGeneration() {
        val sessionId = _uiState.value.sessionId
        if (sessionId <= 0 || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.retryGeneration(sessionId).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, pollingBudgetExhausted = false) }
                    waitStartedAt = 0L
                    fetch()
                    startPollingUntilReady()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "重试失败") }
                },
            )
        }
    }

    private fun fetch() {
        val sessionId = _uiState.value.sessionId
        if (sessionId <= 0) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.getMediation(sessionId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        val generating = it.mediationStatus == "summarizing"
                        if (generating) {
                            if (waitStartedAt == 0L) waitStartedAt = System.currentTimeMillis()
                        }
                        _uiState.update { state ->
                            state.copy(
                                commonPoints = it.commonPoints,
                                diffPoints = it.diffPoints,
                                nextActions = it.nextActions,
                                status = it.mediationStatus,
                                generation = generationStateOf(
                                    status = it.mediationStatus,
                                    failure = it.failure,
                                    task = it.task,
                                    waitedMs = if (waitStartedAt == 0L) {
                                        0L
                                    } else {
                                        System.currentTimeMillis() - waitStartedAt
                                    },
                                ),
                                pollingBudgetExhausted = false,
                                isLoading = false,
                            )
                        }
                    } ?: _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { error ->
                    // 单次网络错误不判定整体失败（B4.1-4）：保留上一份内容，轮询会再来
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

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
