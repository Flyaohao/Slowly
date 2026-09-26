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

/**
 * 确认改写页状态（整改 §8.5-5 / B4.1-4）。
 *
 * 关键语义：**确认是按人记的**。`myConfirmed` 表示「我点过确认」，`partnerConfirmed`
 * 表示「对方点过确认」——两者是会话行上的两个独立列，不是「谁先点谁就推流程」。
 * 双方都确认才进总结；只有我确认时页面必须停在「等对方确认」的等待态。
 *
 * 整改 B4.1-4：生成状态不再是一个布尔 `isGenerating`，而是 [MediationGenerationState]
 * ——「正在生成」「失败还在重试」「失败已终态」是三种不同的东西，页面必须分开处理。
 */
data class MediationConfirmUiState(
    val sessionId: Long = 0,
    /** 服务端按身份解析好的**我这一侧**改写（§8.5-4，本地不再读 rewrite_a/b）。 */
    val myRewrite: MediationDto.MediationRewrite? = null,
    /** §8.5-6：已完成的调解回看时，对方的改写与自己的并列展示。 */
    val partnerRewrite: MediationDto.MediationRewrite? = null,
    val status: String = "confirming",
    val myConfirmed: Boolean = false,
    val partnerConfirmed: Boolean = false,
    val supplement: String = "",
    /** 生成相位（含失败/重试/已完成），页面据此渲染等待态或失败态。 */
    val generation: MediationGenerationState = MediationGenerationState(MediationGenerationPhase.IDLE),
    /** 窗口用尽（还在处理、但我们不再等了）：提示语与「失败」区分开。 */
    val pollingBudgetExhausted: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
) {
    /** 双方都确认 → 该进结果页了。 */
    val bothConfirmed: Boolean
        get() = myConfirmed && partnerConfirmed

    /** 我确认了但对方没有 → 等待态（§8.5-5 的硬门）。 */
    val waitingPartner: Boolean
        get() = myConfirmed && !partnerConfirmed

    val isGenerating: Boolean get() = generation.isGenerating

    val hasFailure: Boolean get() = generation.hasFailure
}

sealed class MediationConfirmUiEvent {
    data class ShowError(val message: String) : MediationConfirmUiEvent()
    /** 只有「双方都确认」才会发出；单方确认留在本页等对方。 */
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

    /** 本轮等待的起点（用于「已等 N 秒」与轮询预算）。重新加载时重置。 */
    private var waitStartedAt = 0L
    private var pollJob: Job? = null

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
        waitStartedAt = 0L
    }

    /**
     * 拉一次会话状态（§8.5-7：断线/返回本页都按**服务端状态**恢复步骤）。
     *
     * 后台生成中（rewriting / summarizing）时不报错也不空白：置 [isGenerating]，
     * 由页面轮询等到 confirming/completed —— 这是 §8.5-8 异步化的消费端。
     */
    fun loadRewrite() = fetch(silent = false)

    /**
     * 轮询版取数：不翻 isLoading（否则每次轮询页面都闪一下加载圈）。
     */
    private fun fetch(silent: Boolean) {
        val sessionId = _uiState.value.sessionId
        if (sessionId <= 0) return
        _uiState.update { it.copy(isLoading = !silent, error = "") }
        viewModelScope.launch {
            mediationRepository.getMediation(sessionId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        applyDetail(it)
                        maybeFinish(it)
                    } ?: _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { error ->
                    // 单次网络错误**不判定整体失败**（B4.1-4）：页面留着上一份状态，
                    // 轮询还会再来一次；只有真的从服务端读回失败态才叫失败。
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    /**
     * 把服务端快照落进 UI 状态。
     *
     * 两个刻意的选择：
     * 1. **`mediation_status` 与失败信息原样落库，不做本地合并**——状态真源在服务端，
     *    本地"保留上一份更乐观的状态"会让失败被旧的 generating 覆盖回来；
     * 2. 等待计时只在**处于生成中**时累计：生成结束（成功或失败）就归零，
     *    下次重新生成再重新计时——否则「已等 6 分钟」会跨轮次叠加。
     *
     * 改写文本是唯一允许沿用旧值的字段：服务端的 `my_rewrite` 由「最后一条含改写的
     * 助手消息」推导，只有在这一稿从未生成过时才为 null；那时保留旧值只是避免
     * 页面闪成空白，不会把"新稿"说成"旧稿"。
     */
    private fun applyDetail(detail: MediationDto.MediationDetailResponse) {
        val now = System.currentTimeMillis()
        val generatingStatus = detail.mediationStatus == "rewriting" ||
            detail.mediationStatus == "summarizing"
        if (generatingStatus) {
            if (waitStartedAt == 0L) waitStartedAt = now
        } else {
            waitStartedAt = 0L
        }
        val waited = if (waitStartedAt == 0L) 0L else now - waitStartedAt
        val generation = generationStateOf(
            status = detail.mediationStatus,
            failure = detail.failure,
            task = detail.task,
            waitedMs = waited,
        )
        _uiState.update { state ->
            state.copy(
                myRewrite = detail.myRewrite ?: state.myRewrite,
                partnerRewrite = detail.partnerRewrite ?: state.partnerRewrite,
                status = detail.mediationStatus,
                myConfirmed = detail.myConfirmed == true,
                partnerConfirmed = detail.partnerConfirmed == true,
                generation = generation,
                pollingBudgetExhausted = false,
                isLoading = false,
            )
        }
    }

    /**
     * 服务端状态说「可以进结果页了」就发事件（§8.5-5 / §8.5-8）。
     *
     * 走 [MediationFlow.afterConfirm] 统一裁决：双方确认齐、或总结已生成完成。
     * 断线重进时对方已经确认过、甚至总结都生成好了，这一条也能把用户直接接上——
     * 否则他会停在一张「等对方确认」的页面上，而对方其实早就点过了。
     */
    private fun maybeFinish(detail: MediationDto.MediationDetailResponse) {
        val step = MediationFlow.afterConfirm(
            mediationStatus = detail.mediationStatus,
            myConfirmed = detail.myConfirmed == true,
            partnerConfirmed = detail.partnerConfirmed == true,
        )
        if (step == MediationStep.RESULT) {
            viewModelScope.launch { _event.emit(MediationConfirmUiEvent.Confirmed) }
        }
    }

    fun onSupplementChange(text: String) {
        _uiState.update { it.copy(supplement = text) }
    }

    /**
     * 确认 / 需要修改。
     *
     * - `confirmed = true`：服务端记下**我这一侧**的确认。响应里
     *   `partner_confirmed` 仍为 false 时**不发 [MediationConfirmUiEvent.Confirmed]**，
     *   页面停在等待态（此前一人点确认就跳结果页，对方从没确认过）。
     * - `confirmed = false`：只重生成我自己那一侧，对方那侧原样保留。
     */
    fun confirm(confirmed: Boolean) {
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val request = MediationDto.MediationConfirmRequest(
                confirmed = confirmed,
                supplement = state.supplement.ifBlank { null },
            )
            mediationRepository.confirmRewrite(state.sessionId, request).fold(
                onSuccess = { session ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            status = session?.mediationStatus ?: it.status,
                            myConfirmed = session?.myConfirmed == true,
                            partnerConfirmed = session?.partnerConfirmed == true,
                            supplement = "",
                        )
                    }
                    // 重新生成（需要修改 / 补生成）会把状态推回 rewriting，
                    // 这里重新起一轮等待计时与轮询——否则用户点完「需要修改」
                    // 就再也不会有东西去拉新稿了。
                    if (session?.mediationStatus == "rewriting") {
                        waitStartedAt = 0L
                        fetch(silent = true)
                        pollUntilReady()
                    }
                    // §8.5-5：只有「双方都确认」才算走完确认这一步。单方确认
                    // （partner_confirmed 仍为 false）留在本页显示等待态。
                    if (MediationFlow.afterConfirm(
                            mediationStatus = session?.mediationStatus ?: state.status,
                            myConfirmed = session?.myConfirmed == true,
                            partnerConfirmed = session?.partnerConfirmed == true,
                        ) == MediationStep.RESULT
                    ) {
                        _event.emit(MediationConfirmUiEvent.Confirmed)
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "确认失败") }
                },
            )
        }
    }

    /**
     * 整改 B4.1-4：失败后的手动重试（`rewrite_failed` / `summary_failed`）。
     *
     * 只重排失败的那一步，不新建会话、不丢已有输入；成功后状态回到
     * rewriting / summarizing，本页继续等（轮询重新起）。
     */
    fun retryGeneration() {
        val sessionId = _uiState.value.sessionId
        if (sessionId <= 0 || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.retryGeneration(sessionId).fold(
                onSuccess = { session ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            status = session?.mediationStatus ?: it.status,
                            pollingBudgetExhausted = false,
                        )
                    }
                    waitStartedAt = 0L
                    fetch(silent = true)
                    pollUntilReady()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "重试失败") }
                },
            )
        }
    }

    /** 离开页面时停掉轮询（页面销毁/返回）。 */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * §8.5-8 + 整改 B4.1-5：服务端在后台生成时就地轮询，**有上限的退避**。
     *
     * 与旧实现的区别（`3s × 30 ≈ 90s`，注释还写着"够覆盖一次 120s 的调用"）：
     *
     * - 间隔退避到 [MediationPolling.MAX_INTERVAL_MS]，窗口拉长到
     *   [MediationPolling.TOTAL_BUDGET_MS]，覆盖「120s 超时 × 3 次尝试 + 指数退避」；
     * - 到达窗口上限**不判失败**（那是把还在正常跑的服务端说成坏了），
     *   只停轮询并提示「仍在处理中，可稍后回来查看」——状态在服务端，
     *   页面重建/重进一定拿得到；
     * - 单次请求失败不结束轮询，下一次继续（网络抖动不该终止等待）；
     * - 一旦服务端给出失败态（[hasFailure]），立刻停止轮询：再等也不会变。
     */
    fun pollUntilReady() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            if (waitStartedAt == 0L) waitStartedAt = System.currentTimeMillis()
            var round = 0
            while (true) {
                delay(MediationPolling.nextIntervalMs(round))
                round += 1

                val state = _uiState.value
                if (!state.isGenerating) return@launch  // 生成结束（成功/失败/别人推进）
                if (state.generation.phase == MediationGenerationPhase.GENERATING &&
                    MediationPolling.budgetExhausted(System.currentTimeMillis() - waitStartedAt)
                ) {
                    _uiState.update { it.copy(pollingBudgetExhausted = true) }
                    return@launch
                }
                fetch(silent = true)
            }
        }
    }

    /** §2.3-4 推送侧：状态帧到达时立即刷新，不必等下一次轮询。 */
    fun onStatusFrame(status: String) {
        _uiState.update { it.copy(status = status) }
        fetch(silent = true)
        if (_uiState.value.isGenerating) pollUntilReady()
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
