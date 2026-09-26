package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.network.SharedApiService
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

data class MediationInviteUiState(
    val sessionId: Long? = null,
    val status: String = "inviting",
    /**
     * 身份真源（契约 §2.3-2）：优先服务端 `my_role`，缺失时按 `session.user_id == 我` 推导。
     * null = 详情还没加载（或加载失败），渲染回退到导航参数 isInviter——只作展示兜底。
     */
    val isInviter: Boolean? = null,
    val isLoading: Boolean = false,
    /** 是否仍在轮询等待（§8.5-1：状态变化靠推送 + 可靠轮询双保险）。 */
    val isPolling: Boolean = false,
    val error: String = "",
)

sealed class MediationInviteUiEvent {
    data class ShowError(val message: String) : MediationInviteUiEvent()
    data object Accepted : MediationInviteUiEvent()
    data object Rejected : MediationInviteUiEvent()
    /**
     * §8.5-1：轮询/推送发现服务端状态推进了（对方接受 / 已进改写 / 已完成…）。
     * 落点由 [MediationFlow.whileWaiting] 统一裁决，页面只负责执行——这样
     * 「对方在我等待期间一路写完并双方确认」也能一次跳到位，不会把用户丢回输入页。
     */
    data class MoveTo(val step: MediationStep) : MediationInviteUiEvent()
}

@HiltViewModel
class MediationInviteViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
    private val sharedApiService: SharedApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationInviteUiState())
    val uiState: StateFlow<MediationInviteUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MediationInviteUiEvent>()
    val event: SharedFlow<MediationInviteUiEvent> = _event.asSharedFlow()

    private var pollJob: Job? = null

    fun setSessionId(id: Long) {
        _uiState.update { it.copy(sessionId = id) }
    }

    /**
     * 契约 §2.3-2：`GET /ai/mediation/{id}` 是唯一状态真源。
     * 加载失败静默降级（isInviter 保持 null，页面按导航参数渲染），
     * 不打断接受/拒绝——那两个动作本身有服务端校验兜底。
     */
    fun loadSession(id: Long) {
        _uiState.update { it.copy(sessionId = id, isLoading = true) }
        viewModelScope.launch {
            val myUserId = runCatching { sharedApiService.getCurrentUser().data?.userId }.getOrNull()
            mediationRepository.getMediation(id).fold(
                onSuccess = { detail ->
                    if (detail != null) {
                        val role = detail.myRole ?: deriveRole(detail.userId, myUserId)
                        _uiState.update {
                            it.copy(
                                status = detail.mediationStatus,
                                isInviter = role?.let { r -> r == "inviter" } ?: it.isInviter,
                                isLoading = false,
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoading = false) }
                },
            )
        }
    }

    /**
     * §8.5-1：发起方等待页必须能**获知对方接受**——此前只 load 一次，
     * 对方接受了页面还停在「等待对方接受邀请...」，用户只能自己退出去重进。
     *
     * 这里做成「WS 状态帧（[onStatusFrame]）+ 可靠轮询」双保险：WS 断线时
     * 轮询仍然把状态拉回来，不把可达性押在长连接上。轮询在离开 inviting
     * 或页面销毁时自动停。
     */
    fun startWaiting(sessionId: Long) {
        if (pollJob?.isActive == true) return
        _uiState.update { it.copy(sessionId = sessionId, isPolling = true) }
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val detail = mediationRepository.getMediation(sessionId).getOrNull() ?: continue
                _uiState.update { it.copy(status = detail.mediationStatus) }
                val step = MediationFlow.whileWaiting(detail.mediationStatus)
                if (step != MediationStep.STAY) {
                    _uiState.update { it.copy(isPolling = false) }
                    _event.emit(MediationInviteUiEvent.MoveTo(step))
                    return@launch
                }
            }
        }
    }

    /** §2.3-4 推送侧：WS 状态帧到达时立即推进，不必等下一次轮询。 */
    fun onStatusFrame(status: String) {
        _uiState.update { it.copy(status = status) }
        val step = MediationFlow.whileWaiting(status)
        if (step != MediationStep.STAY) {
            viewModelScope.launch { _event.emit(MediationInviteUiEvent.MoveTo(step)) }
        }
    }

    /** 离开页面时停掉轮询（页面销毁/返回）。 */
    fun stopWaiting() {
        pollJob?.cancel()
        pollJob = null
        _uiState.update { it.copy(isPolling = false) }
    }

    /** 服务端未给 my_role 时的客户端推导：会话创建者是我 → inviter。 */
    private fun deriveRole(sessionUserId: Long?, myUserId: Long?): String? {
        if (sessionUserId == null || myUserId == null) return null
        return if (sessionUserId == myUserId) "inviter" else "partner"
    }

    /**
     * 发起调解（契约 §2.3-1，API 优先）。
     * 现由 MediationExplanationScreen 在「开始调解」时先调 start 拿真实 session_id 再导航进来；
     * 本方法保留作备用入口（隐藏 ≠ 删除），无调用点。
     */
    fun startMediation() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            mediationRepository.startMediation().fold(
                onSuccess = { session ->
                    if (session != null) {
                        _uiState.update { state ->
                            state.copy(
                                sessionId = session.sessionId,
                                status = session.mediationStatus,
                                isInviter = session.myRole?.let { it == "inviter" } ?: true,
                                isLoading = false,
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = "发起失败") }
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
                    stopWaiting()
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
                    stopWaiting()
                    _uiState.update { it.copy(status = "rejected", isLoading = false) }
                    _event.emit(MediationInviteUiEvent.Rejected)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "拒绝失败") }
                },
            )
        }
    }

    override fun onCleared() {
        stopWaiting()
        super.onCleared()
    }

    private companion object {
        /** 轮询间隔：够快让用户感觉是「实时」，又不至于把关系页/邀请页打成高频请求。 */
        const val POLL_INTERVAL_MS = 4_000L
    }
}
