package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MediationDto
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

data class MediationInputUiState(
    val sessionId: Long = 0,
    val feeling: String = "",
    val trigger: String = "",
    val wishUnderstood: String = "",
    val wishNext: String = "",
    /**
     * 服务端状态（§8.5-2 / §8.5-7）：第一方提交后服务端仍是 `inputting`——
     * 我是「先写的一方」，页面必须进**等待态**（等对方写完、等改写生成），
     * 而不是跳双方确认页（那里此刻是空的）。
     */
    val status: String = "inputting",
    val partnerSubmitted: Boolean = false,
    /** 我已提交、停在等待态（页面显示等待文案）。 */
    val submitted: Boolean = false,
    /**
     * 整改 B4.1-4：服务端给出失败态（`rewrite_failed`）。
     *
     * 这一页等的是两件事：对方写完 + 改写生成。生成失败时必须停止等待并
     * 让用户看见——此前失败态落进 `afterSubmit` 的 else，页面继续显示
     * 「等对方写完」，用户等的是一个永远不会来的改写。
     *
     * 注意本页**不渲染**失败卡片：改写的归宿是确认页（那一页才有完整失败态
     * 与「重试」按钮）。[MediationFlow.afterSubmit] 会把 `rewrite_failed`
     * 判成 [MediationStep.FAILED_REWRITE]，由导航把人送过去。
     */
    val generation: MediationGenerationState = MediationGenerationState(MediationGenerationPhase.IDLE),
    /**
     * 整改 B4.1-5：等「人」的窗口用尽后**暂停**轮询（不是失败）。
     * 对方可能在上班/睡觉；暂停后页面给「继续等待」，点一下重开一轮窗口。
     */
    val waitingPaused: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
) {
    val isGenerating: Boolean
        get() = status in MediationFlow.GENERATING_STATUSES

    /** 有失败（含自动重试中）：页面必须显式渲染，不许当成"还在等"。 */
    val hasFailure: Boolean get() = generation.hasFailure
}

sealed class MediationInputUiEvent {
    data class ShowError(val message: String) : MediationInputUiEvent()
    data object InputSubmitted : MediationInputUiEvent()
    /** 等待态里状态推进了 → 由 [MediationFlow] 裁决落点。 */
    data class MoveTo(val step: MediationStep) : MediationInputUiEvent()
}

@HiltViewModel
class MediationInputViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationInputUiState())
    val uiState: StateFlow<MediationInputUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationInputUiEvent>()
    val event: SharedFlow<MediationInputUiEvent> = _event.asSharedFlow()

    private var pollJob: Job? = null

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
    }

    /**
     * §8.5-7：按服务端状态恢复步骤。断线重进时可能已经提交过、已经进改写、
     * 甚至已经双方确认完——页面据此直接落到正确的一步，不重放历史动作。
     */
    fun loadSession() {
        val sessionId = _uiState.value.sessionId
        if (sessionId <= 0) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.getMediation(sessionId).fold(
                onSuccess = { detail ->
                    detail?.let { applyDetail(it) }
                        ?: _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    /**
     * 服务端快照 → UI 状态（加载与轮询共用一份，避免两条路径分叉）。
     *
     * `submitted` 用「服务端说的」或「本地已提交过」的或：服务端的
     * `my_submitted` 是唯一依据（§8.5-7），本地那次只用来兜住响应还没回来的
     * 一瞬——否则刚点完提交会闪回一张空表单，用户能再提交一遍。
     */
    private fun applyDetail(detail: MediationDto.MediationDetailResponse) {
        _uiState.update { state ->
            state.copy(
                status = detail.mediationStatus,
                partnerSubmitted = detail.partnerSubmitted == true,
                submitted = detail.mySubmitted == true || state.submitted,
                generation = generationStateOf(
                    status = detail.mediationStatus,
                    failure = detail.failure,
                    task = detail.task,
                ),
                isLoading = false,
            )
        }
    }

    /**
     * §8.5-2：提交后停在等待态，靠轮询等服务端推进（对方写完 → 改写 → 确认页）。
     *
     * 整改 B4.1-5：这一页等的主要是**人**（对方写完自己的部分），所以用
     * [MediationPolling.HUMAN_WAIT_TOTAL_BUDGET_MS] 这个更宽的窗口；窗口用尽
     * 只**暂停**（[MediationInputUiState.waitingPaused]），由用户点「继续等待」
     * 再开一轮——对方在上班/睡觉不等于这场调解坏了。
     *
     * 单次请求失败**不终止等待**（网络抖动而已），下一轮接着来；
     * 只有服务端真的给出新状态才走 [MediationFlow] 裁决的落点。
     * 失败态（`rewrite_failed`）在 [MediationFlow.afterSubmit] 里直接映射成
     * 非等待落点，于是循环会立刻退出并交还给用户，不会一直转圈。
     */
    fun startWaiting() {
        if (pollJob?.isActive == true) return
        val sessionId = _uiState.value.sessionId
        if (sessionId <= 0) return
        _uiState.update { it.copy(waitingPaused = false) }
        pollJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (true) {
                delay(MediationPolling.HUMAN_WAIT_INTERVAL_MS)
                val detail = mediationRepository.getMediation(sessionId).getOrNull() ?: continue
                applyDetail(detail)
                val step = MediationFlow.afterSubmit(detail.mediationStatus)
                if (step != MediationStep.WAITING_PARTNER) {
                    _event.emit(MediationInputUiEvent.MoveTo(step))
                    return@launch
                }
                if (MediationPolling.humanWaitExhausted(System.currentTimeMillis() - startedAt)) {
                    _uiState.update { it.copy(waitingPaused = true) }
                    return@launch
                }
            }
        }
    }

    /** 暂停后点「继续等待」：开新的一轮窗口（状态在服务端，这里只是重新开始轮询）。 */
    fun resumeWaiting() {
        _uiState.update { it.copy(waitingPaused = false) }
        startWaiting()
    }

    /** §2.3-4 推送侧：WS 状态帧到达时立即更新（轮询是兜底，不把可达性押在长连接上）。 */
    fun onStatusFrame(status: String) {
        if (!_uiState.value.submitted) return
        _uiState.update { it.copy(status = status) }
    }

    fun stopWaiting() {
        pollJob?.cancel()
        pollJob = null
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
                content = buildString {
                    appendLine("我的感受：${state.feeling}")
                    appendLine("触发的事件：${state.trigger}")
                    appendLine("希望被理解的是：${state.wishUnderstood}")
                    appendLine("希望接下来：${state.wishNext}")
                }.trim(),
            )
            mediationRepository.submitInput(state.sessionId, request).fold(
                onSuccess = { session ->
                    // §8.5-2：提交**不等于**可以去确认页了。服务端返回的
                    // mediation_status 才是落点依据：第一方提交后仍是 inputting
                    // （等对方写），此时页面进等待态；双方都写完才会是
                    // rewriting/confirming。此前这里无条件跳确认页，
                    // 第一方会看到一张没有任何改写内容的空确认页。
                    val status = session?.mediationStatus ?: state.status
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            status = status,
                            submitted = true,
                            partnerSubmitted = session?.partnerSubmitted == true,
                        )
                    }
                    _event.emit(MediationInputUiEvent.InputSubmitted)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "提交失败") }
                },
            )
        }
    }

    override fun onCleared() {
        stopWaiting()
        super.onCleared()
    }
}
