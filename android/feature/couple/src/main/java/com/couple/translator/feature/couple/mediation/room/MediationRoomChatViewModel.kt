package com.couple.translator.feature.couple.mediation.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MediationRoomDto
import com.couple.translator.feature.couple.data.model.MediationRoomDto.RoomState
import com.couple.translator.feature.couple.data.model.MediationRoomDto.RoomMessage
import com.couple.translator.feature.couple.data.repository.MediationRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 轮询间隔（D-REALTIME MVP：对方端 2s 短轮询；双端 SSE 归 P1） */
private const val POLL_INTERVAL_MS = 2_000L

data class RoomChatUiState(
    val roomId: Long = 0,
    val loading: Boolean = true,
    val messages: List<RoomMessage> = emptyList(),
    val state: RoomState? = null,
    val roomName: String = "",
    val styleLabel: String = "",
    /** 本端正在流式接收军师发言 */
    val advisorThinking: String = "",
    val advisorStreamingContent: String = "",
    val advisorStreaming: Boolean = false,
    /** 对端触发军师生成（pending 占位），本端只显示「军师正在输入」 */
    val advisorInputPending: Boolean = false,
    val toast: String = "",
    val error: String = "",
)

@HiltViewModel
class MediationRoomChatViewModel @Inject constructor(
    private val repository: MediationRoomRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomChatUiState())
    val uiState: StateFlow<RoomChatUiState> = _uiState

    private var pollJob: Job? = null
    private var streamJob: Job? = null

    fun bind(roomId: Long) {
        if (_uiState.value.roomId == roomId && pollJob != null) return
        _uiState.update { it.copy(roomId = roomId) }
        viewModelScope.launch {
            repository.getRoom(roomId).onSuccess { room ->
                _uiState.update {
                    it.copy(roomName = room?.name ?: "", styleLabel = room?.styleLabel ?: "")
                }
            }
        }
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                pollNow(silent = true)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 立即拉一次新消息（动作后 / SSE done 后调用，不等下个周期）。 */
    fun pollNow(silent: Boolean = false) {
        val roomId = _uiState.value.roomId
        if (roomId == 0L) return
        viewModelScope.launch {
            val afterId = _uiState.value.messages.lastOrNull()?.id ?: 0L
            repository.getMessages(roomId, afterId).fold(
                onSuccess = { data ->
                    if (data == null) return@launch
                    _uiState.update { cur ->
                        val merged = if (data.messages.isEmpty()) cur.messages
                        else cur.messages + data.messages
                        cur.copy(
                            loading = false,
                            messages = merged,
                            state = data.state ?: cur.state,
                            advisorInputPending = data.state?.advisorGenerating == true &&
                                !cur.advisorStreaming,
                        )
                    }
                },
                onFailure = { e ->
                    if (!silent) {
                        _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
                    } else {
                        _uiState.update { it.copy(loading = false) }
                    }
                },
            )
        }
    }

    fun consumeToast() = _uiState.update { it.copy(toast = "") }
    fun clearError() = _uiState.update { it.copy(error = "") }

    /** 发消息；mention=true 同时召唤军师（@军师）。 */
    fun sendMessage(content: String, mention: Boolean) {
        val roomId = _uiState.value.roomId
        val text = content.trim()
        if (roomId == 0L || text.isEmpty()) return
        viewModelScope.launch {
            repository.postMessage(roomId, MediationRoomDto.PostMessageRequest(text, mention))
                .fold(
                    onSuccess = { resp ->
                        _uiState.update { it.copy(advisorThinking = "", advisorStreamingContent = "") }
                        if (resp?.advisorBusy == true && mention) {
                            _uiState.update { it.copy(toast = "军师正在思考，请等这轮说完再召唤") }
                        }
                        if (resp?.advisorClaimed == true && resp.token != null) {
                            startAdvisorStream(resp.token)
                        }
                        pollNow(silent = true)
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(error = e.message ?: "发送失败") }
                    },
                )
        }
    }

    /** 军师发言 SSE（D-CHANNEL：API 进程流式）。 */
    private fun startAdvisorStream(token: String) {
        streamJob?.cancel()
        _uiState.update { it.copy(advisorStreaming = true, advisorThinking = "", advisorStreamingContent = "") }
        streamJob = viewModelScope.launch {
            val roomId = _uiState.value.roomId
            repository.advisorStream(roomId, token).collect { ev ->
                when (ev) {
                    is MediationRoomDto.AdvisorStreamEvent.Meta -> Unit
                    is MediationRoomDto.AdvisorStreamEvent.Thinking ->
                        _uiState.update { it.copy(advisorThinking = it.advisorThinking + ev.content) }
                    is MediationRoomDto.AdvisorStreamEvent.Delta ->
                        _uiState.update { it.copy(advisorStreamingContent = it.advisorStreamingContent + ev.content) }
                    is MediationRoomDto.AdvisorStreamEvent.Done -> {
                        _uiState.update { it.copy(advisorStreaming = false) }
                        pollNow(silent = true)
                    }
                    is MediationRoomDto.AdvisorStreamEvent.Failure -> {
                        _uiState.update {
                            it.copy(advisorStreaming = false, toast = ev.message)
                        }
                        pollNow(silent = true)
                    }
                }
            }
            _uiState.update { it.copy(advisorStreaming = false) }
        }
    }

    /** 同意 → 双方都同意后军师靠边（§五.3）。 */
    fun agree() {
        val roomId = _uiState.value.roomId
        if (roomId == 0L) return
        viewModelScope.launch {
            repository.agree(roomId).fold(
                onSuccess = { pollNow(silent = true) },
                onFailure = { e -> _uiState.update { it.copy(toast = e.message ?: "操作失败") } },
            )
        }
    }

    /** 补充 → 本轮同意作废 + 军师再发言（§五.2）。本端凭 token 直开流。 */
    fun supplement(content: String?) {
        val roomId = _uiState.value.roomId
        if (roomId == 0L) return
        viewModelScope.launch {
            repository.supplement(roomId, MediationRoomDto.SupplementRequest(content?.trim()?.ifEmpty { null }))
                .fold(
                    onSuccess = { resp ->
                        if (resp?.advisorClaimed == true && resp.token != null) {
                            startAdvisorStream(resp.token)
                        }
                        pollNow(silent = true)
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(toast = e.message ?: "军师正在思考，请稍候") }
                    },
                )
        }
    }

    /** 结束调解投票（可反悔，D-CLOSING）。 */
    fun voteEnd(vote: Boolean) {
        val roomId = _uiState.value.roomId
        if (roomId == 0L) return
        viewModelScope.launch {
            repository.endVote(roomId, if (vote) "vote" else "cancel").fold(
                onSuccess = { pollNow(silent = true) },
                onFailure = { e -> _uiState.update { it.copy(toast = e.message ?: "操作失败") } },
            )
        }
    }

    /** 调解书确认（D-RESULT：双方确认后结算）。 */
    fun confirmSettlement() {
        val roomId = _uiState.value.roomId
        if (roomId == 0L) return
        viewModelScope.launch {
            repository.confirmSettlement(roomId).fold(
                onSuccess = { pollNow(silent = true) },
                onFailure = { e -> _uiState.update { it.copy(toast = e.message ?: "操作失败") } },
            )
        }
    }

    /** 结算失败后手动重试（settle_error 可见时）。 */
    fun retrySettlement() {
        val roomId = _uiState.value.roomId
        if (roomId == 0L) return
        viewModelScope.launch {
            repository.retrySettlement(roomId).fold(
                onSuccess = { _uiState.update { it.copy(toast = "已重新排队，请稍候") } },
                onFailure = { e -> _uiState.update { it.copy(toast = e.message ?: "重试失败") } },
            )
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        streamJob?.cancel()
        super.onCleared()
    }
}
