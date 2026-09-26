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
    val isLoading: Boolean = false,
    val error: String = "",
) {
    val isGenerating: Boolean
        get() = status == "rewriting" || status == "summarizing"
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
                    detail?.let {
                        _uiState.update { state ->
                            state.copy(
                                status = it.mediationStatus,
                                partnerSubmitted = it.partnerSubmitted == true,
                                // 服务端按作者给出的「我提交过没有」是唯一依据（§8.5-7）
                                submitted = it.mySubmitted == true || state.submitted,
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

    /** §8.5-2：提交后停在等待态，靠轮询等服务端推进（对方写完 → 改写 → 确认页）。 */
    fun startWaiting() {
        if (pollJob?.isActive == true) return
        val sessionId = _uiState.value.sessionId
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val detail = mediationRepository.getMediation(sessionId).getOrNull() ?: continue
                _uiState.update {
                    it.copy(
                        status = detail.mediationStatus,
                        partnerSubmitted = detail.partnerSubmitted == true,
                    )
                }
                val step = MediationFlow.afterSubmit(detail.mediationStatus)
                if (step != MediationStep.WAITING_PARTNER) {
                    _event.emit(MediationInputUiEvent.MoveTo(step))
                    return@launch
                }
            }
        }
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

    private companion object {
        const val POLL_INTERVAL_MS = 4_000L
    }
}
