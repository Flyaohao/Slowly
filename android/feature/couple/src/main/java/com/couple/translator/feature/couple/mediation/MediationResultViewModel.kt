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
    /**
     * 整改 B4.3 P0-2：这次调解是**被安全终止**的（高风险语境下 AI 主动叫停）。
     * 界面据此只显示安全提示，不给任何推进动作，也不显示「没有留下总结」。
     */
    val safetyBlocked: Boolean = false,
    /** 安全提示正文（服务端下发的安全资源文案）；null = 还没有 */
    val safetyMessage: String? = null,
    val isLoading: Boolean = false,
    val error: String = "",
) {
    /**
     * §8.5-6：只有**正常谈完**的调解才有「下一步」；回看历史时不再出现这些动作。
     *
     * P0-2：安全终止**不算** completed——给它配上「继续沟通 / 结束调解」
     * 既会被服务端拒绝（continue 对 safety_blocked 返回 50003），
     * 也在语义上错误（「结束」一场已经被终止的调解）。
     */
    val isCompleted: Boolean
        get() = status == MediationFlow.COMPLETED && MediationFlow.showsNextStepActions(status)

    val isGenerating: Boolean get() = generation.isGenerating

    val hasFailure: Boolean get() = generation.hasFailure

    /**
     * 没有任何总结内容、也不在任何"还没定论"的态里（真的空）。
     *
     * 四种情况都必须排除，否则会在同一屏上同时出现「还在处理」和
     * 「这次调解没有留下总结」——后者是结论，前者是过程，摆在一起自相矛盾：
     * - 生成中：结果还没来；
     * - 有失败：有明确失败卡片（含重试），不是"没有总结"；
     * - 轮询窗口用尽：我们不再等了，但服务端还在跑；
     * - **安全终止**（P0-2）：这一场本来就不该有总结，说「没有留下总结」
     *   等于把一次主动的安全干预讲成一次技术故障。
     */
    val isEmpty: Boolean
        get() = !safetyBlocked && !isGenerating && !hasFailure && !pollingBudgetExhausted &&
            !isLoading && commonPoints.isEmpty() && diffPoints.isEmpty() && nextActions.isEmpty()

    /** 是否可以给「重试生成」：安全终止不是失败，重试只会再撞一次同样的判定。 */
    val canRetryGeneration: Boolean
        get() = MediationFlow.allowsRetry(status)
}

sealed class MediationResultUiEvent {
    data class ShowError(val message: String) : MediationResultUiEvent()
    data class ActionCompleted(val action: String) : MediationResultUiEvent()
}

/**
 * 从消息里取安全提示正文（P0-2）。
 *
 * 后端把安全文案同时放在 `structured_output.safety_response` 与 `content`
 * （见 `mediation_service._commit_safety_block`）。取法必须**先认结构化字段**，
 * 因为同一场会话里前面还可能有别的 assistant 消息（改写稿），只按「最后一条
 * assistant」取，一旦顺序不如预期就会把改写稿当成安全提示展示给用户。
 *
 * 取不到就返回 null：界面据此只渲染标题与固定引导语，**绝不**用一句本地
 * 兜底文案假装「这就是服务端的提示」。
 */
internal fun safetyTextOf(messages: List<MediationDto.MediationMessageItem>): String? {
    val assistants = messages.filter { it.role == "assistant" }
    // 1) 结构化契约字段优先——这是服务端明确标注「这段是安全资源」的唯一标记。
    assistants.lastOrNull { !it.structuredOutput?.safetyResponse.isNullOrBlank() }
        ?.let { return it.structuredOutput?.safetyResponse }
    // 2) 契约字段缺失时的降级：只接受**不是调解产物**的那条 assistant 消息。
    //    改写稿 / 总结稿都带 rewrite 或 common_points，用这个特征把它们排除，
    //    避免把一份正常的调解产物渲染成「安全提示」。
    return assistants.lastOrNull { msg ->
        val s = msg.structuredOutput
        msg.content.isNotBlank() &&
            s?.rewriteA.isNullOrBlank() && s?.rewriteB.isNullOrBlank() &&
            s?.commonPoints.isNullOrEmpty() && s?.nextActions.isNullOrEmpty()
    }?.content
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
            // P0-2：安全终止是终态，服务端不会再有产出——不进入轮询。
            if (!MediationFlow.shouldKeepPolling(_uiState.value.status)) return@launch
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
        // P0-2：安全终止不是失败——重试只会再撞一次同样的高风险判定，
        // 把它渲染成「可重试」是在鼓励用户绕开安全阻断。
        if (sessionId <= 0 || _uiState.value.isLoading) return
        if (!_uiState.value.canRetryGeneration) return
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
                        val blocked = it.mediationStatus == MediationFlow.SAFETY_BLOCKED
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
                                // P0-2：安全提示正文从消息里取（服务端把它落在
                                // assistant 消息的 structured_output.safety_response，
                                // 并在 content 里给了同一段文案）。
                                safetyBlocked = blocked,
                                safetyMessage = if (blocked) safetyTextOf(it.messages) else null,
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
