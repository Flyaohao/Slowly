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
 * 确认改写页状态（整改 §8.5-5）。
 *
 * 关键语义：**确认是按人记的**。`myConfirmed` 表示「我点过确认」，`partnerConfirmed`
 * 表示「对方点过确认」——两者是会话行上的两个独立列，不是「谁先点谁就推流程」。
 * 双方都确认才进总结；只有我确认时页面必须停在「等对方确认」的等待态。
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
    /** 生成中（服务端在后台跑改写/总结）：页面要显示「AI 正在准备」而不是空白。 */
    val isGenerating: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
) {
    /** 双方都确认 → 该进结果页了。 */
    val bothConfirmed: Boolean
        get() = myConfirmed && partnerConfirmed

    /** 我确认了但对方没有 → 等待态（§8.5-5 的硬门）。 */
    val waitingPartner: Boolean
        get() = myConfirmed && !partnerConfirmed
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

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
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
                        _uiState.update { state ->
                            state.copy(
                                myRewrite = it.myRewrite ?: state.myRewrite,
                                partnerRewrite = it.partnerRewrite ?: state.partnerRewrite,
                                status = it.mediationStatus,
                                myConfirmed = it.myConfirmed == true,
                                partnerConfirmed = it.partnerConfirmed == true,
                                isGenerating = it.mediationStatus in GENERATING_STATUSES,
                                isLoading = false,
                            )
                        }
                        maybeFinish(it)
                    } ?: _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
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

    /** 离开页面时停掉轮询（页面销毁/返回）。 */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * §8.5-8：服务端在后台生成时，就地轮询到生成结束。
     *
     * 生成失败/超时不会死循环：最多轮 [MAX_POLLS] 次，超了就提示「稍后重进查看」——
     * 状态在服务端，用户退出重进一定能看到结果，不必把页面卡住。
     */
    fun pollUntilReady() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            repeat(MAX_POLLS) {
                delay(POLL_INTERVAL_MS)
                fetch(silent = true)
                if (!_uiState.value.isGenerating) return@launch
            }
            _uiState.update {
                it.copy(isGenerating = false, error = "生成时间比平时久，可以稍后重新进入查看")
            }
        }
    }

    /** §2.3-4 推送侧：状态帧到达时立即刷新，不必等下一次轮询。 */
    fun onStatusFrame(status: String) {
        _uiState.update { it.copy(status = status, isGenerating = status in GENERATING_STATUSES) }
        fetch(silent = true)
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private var pollJob: Job? = null

    private companion object {
        /** §8.5-8：这两个状态代表服务端正在后台生成，客户端只等待、不报错。 */
        val GENERATING_STATUSES = setOf("rewriting", "summarizing")

        const val POLL_INTERVAL_MS = 3_000L

        /** 约 90s：够长到覆盖一次 120s 超时的 LLM 调用，又不至于把用户永久卡在轮询里。 */
        const val MAX_POLLS = 30
    }
}
