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
    /**
     * 推理模型思考过程的增量累积。
     *
     * 为什么要单独留一个字段：推理模型（qwen3.7-flash）的正文首帧实测要 18s+，
     * 而思考首帧约 0.5s。如果只累积流式正文，用户在这 18 秒里看到的是一片空白。
     * 把思考过程也收下来，UI 就能立刻渲染「正在深度思考」的可展开面板。
     * 它**不参与正文拼接**，只用于过程展示。
     */
    val thinkingContent: String = "",
    /** 思考是否已结束（正文已开始或流已终止）。UI 据此把面板从展开改为折叠 */
    val thinkingFinished: Boolean = false,
    /** 从发问到出正文之间的等待秒数，折叠后作为副标题展示（如「已深度思考 18 秒」） */
    val thinkingSeconds: Int = 0,
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

    /** 是否正在思考（收到了思考增量但正文还没来） */
    val isThinking: Boolean get() = isStreaming && streamingContent.isEmpty()
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

    /** 本次流的起始时刻，用于算出「已深度思考 N 秒」 */
    private var streamStartedAt = 0L

    init {
        loadScenes()
    }

    /**
     * 拉取后端场景清单并写进 [AiSceneCatalog]。
     *
     * 失败不提示用户：目录里已有一份与后端种子一致的内置清单，
     * 拉取只是把它换成服务端的权威版本。为这点差异弹错误框不划算。
     */
    private fun loadScenes() {
        viewModelScope.launch {
            aiRepository.getScenes().onSuccess { AiSceneCatalog.refresh(it) }
        }
    }

    /** 关闭错误弹窗（B-05：此前 Screen 传空的 onDismiss，弹窗无法关闭） */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }


    private val _event = MutableSharedFlow<AiChatUiEvent>()
    val event: SharedFlow<AiChatUiEvent> = _event.asSharedFlow()

    fun setSceneKey(sceneKey: String) {
        _uiState.update { it.copy(sceneKey = sceneKey) }
    }

    /**
     * 切换聊天场景。
     *
     * 只接受目录里的场景（[AiSceneCatalog]），而不是任意字符串——
     * 这正是此前 `ModeDrawerSheet` 能把 `reply` / `apologize` 这类
     * 后端不存在的 key 塞进请求的原因。
     */
    fun selectScene(scene: AiScene) {
        setSceneKey(scene.key)
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
                thinkingContent = "",
                thinkingFinished = false,
                thinkingSeconds = 0,
                error = "",
            )
        }
        streamStartedAt = System.currentTimeMillis()

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

                    // 思考增量：只喂给「深度思考」面板，不拼进正文
                    is AiDto.ChatStreamEvent.Thinking -> _uiState.update {
                        it.copy(thinkingContent = it.thinkingContent + ev.content)
                    }

                    is AiDto.ChatStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            streamingContent = it.streamingContent + ev.content,
                            // 正文一开始，思考就结束了：面板转为折叠态并标注耗时
                            thinkingFinished = true,
                            thinkingSeconds = it.thinkingSeconds
                                .takeIf { s -> s > 0 } ?: elapsedSeconds(),
                        )
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

    /** 从发问算起的等待秒数，至少 1 秒（避免显示「已深度思考 0 秒」） */
    private fun elapsedSeconds(): Int {
        if (streamStartedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - streamStartedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
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
