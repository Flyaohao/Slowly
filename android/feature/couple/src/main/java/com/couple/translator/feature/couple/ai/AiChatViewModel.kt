package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.feature.couple.data.repository.AiRepository
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

data class AiChatUiState(
    val sessionId: Long? = null,
    val sceneKey: String = "private_advisor",
    val messages: List<AiDto.MessageResponse> = emptyList(),
    /** 流式回答的增量累积；非空时 UI 应把它渲染成"正在输入"的气泡 */
    val streamingContent: String = "",
    val isStreaming: Boolean = false,
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isLoadingMessages: Boolean = false,
    val showRewriteSheet: Boolean = false,
    val rewriteVersions: List<AiDto.RewriteVersion> = emptyList(),
    val rewriteOriginal: String = "",
    val error: String = "",
) {
    /** 输入框是否应禁用：请求中或流式输出中都禁用，避免同会话并发 */
    val isBusy: Boolean get() = isLoading || isStreaming
}

sealed class AiChatUiEvent {
    data class ShowError(val message: String) : AiChatUiEvent()
}

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<AiChatUiEvent>()
    val event: SharedFlow<AiChatUiEvent> = _event.asSharedFlow()

    fun setSceneKey(sceneKey: String) {
        _uiState.update { it.copy(sceneKey = sceneKey) }
    }

    fun loadSession(sessionId: Long) {
        _uiState.update { it.copy(sessionId = sessionId, isLoadingMessages = true) }
        viewModelScope.launch {
            aiRepository.getSessionMessages(sessionId).fold(
                onSuccess = { messages ->
                    _uiState.update {
                        it.copy(messages = messages, isLoadingMessages = false)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoadingMessages = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun dismissRewriteSheet() {
        _uiState.update { it.copy(showRewriteSheet = false, rewriteVersions = emptyList(), rewriteOriginal = "") }
    }

    fun rewriteExpression() {
        val state = _uiState.value
        val content = state.inputText.trim()
        if (content.isEmpty() || state.isBusy) return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            aiRepository.rewriteExpression(content).fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showRewriteSheet = true,
                            rewriteVersions = response.versions,
                            rewriteOriginal = response.original,
                            inputText = "",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "改写失败")
                    }
                },
            )
        }
    }

    fun applyRewrite(content: String) {
        _uiState.update { it.copy(inputText = content, showRewriteSheet = false, rewriteVersions = emptyList()) }
    }

    /**
     * 发送消息，走 SSE 流式。
     *
     * 与旧实现的区别：不再等整段回答生成完才显示。
     * 收到 `meta` 立即绑定会话、收到 `delta` 立即追加文本、收到 `done` 才固化成正式消息。
     * 中途失败时**保留已收到的部分内容**，避免用户看到的内容整段消失。
     */
    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty() || state.isBusy) return

        val userMessage = AiDto.MessageResponse(
            id = System.currentTimeMillis(),
            sessionId = state.sessionId,
            role = "user",
            content = text,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isStreaming = true,
                streamingContent = "",
                error = "",
            )
        }

        viewModelScope.launch {
            val request = AiDto.ChatRequest(
                sessionId = state.sessionId,
                sceneKey = state.sceneKey,
                message = text,
            )

            aiRepository.chatStream(request).collect { ev ->
                when (ev) {
                    is AiDto.ChatStreamEvent.Meta -> _uiState.update {
                        it.copy(sessionId = ev.sessionId)
                    }

                    is AiDto.ChatStreamEvent.Delta -> _uiState.update {
                        it.copy(streamingContent = it.streamingContent + ev.content)
                    }

                    is AiDto.ChatStreamEvent.Done -> finishStream(ev)

                    is AiDto.ChatStreamEvent.Failure -> {
                        keepPartialThenFail(ev.message)
                        _event.emit(AiChatUiEvent.ShowError(ev.message))
                    }
                }
            }

            // 兜底：服务端若没发 done 就断开，这里把已收到的内容固化。
            // 否则 isStreaming 会一直是 true，输入框永久禁用。
            if (_uiState.value.isStreaming) {
                keepPartialThenFail("回答被中断，请重试")
            }
        }
    }

    private fun finishStream(ev: AiDto.ChatStreamEvent.Done) {
        val state = _uiState.value
        val finalText = ev.content.ifBlank { state.streamingContent }
        val assistantMessage = AiDto.MessageResponse(
            id = if (ev.messageId != 0L) ev.messageId else System.currentTimeMillis(),
            sessionId = ev.sessionId.takeIf { it != 0L } ?: state.sessionId,
            role = "assistant",
            content = finalText,
            riskLevel = ev.riskLevel,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + assistantMessage,
                streamingContent = "",
                isStreaming = false,
            )
        }
    }

    private fun keepPartialThenFail(message: String) {
        val state = _uiState.value
        val partial = state.streamingContent.trim()
        val extra = if (partial.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AiDto.MessageResponse(
                    id = System.currentTimeMillis(),
                    sessionId = state.sessionId,
                    role = "assistant",
                    content = partial,
                )
            )
        }
        _uiState.update {
            it.copy(
                messages = it.messages + extra,
                streamingContent = "",
                isStreaming = false,
                error = message,
            )
        }
    }
}
