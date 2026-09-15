package com.couple.translator.feature.couple.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.repository.AiRepository
import com.couple.translator.feature.couple.data.repository.GenerationStreamEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
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
    /** 表达改写流式生成中（v2.2 起走 ai_generation 流式端点） */
    val isRewriteStreaming: Boolean = false,
    /** 表达改写的流式正文（5 个风格逐段打字机） */
    val rewriteStreamContent: String = "",
    /** 表达改写模型的思考过程，喂「深度思考」面板 */
    val rewriteThinking: String = "",
    /** 最近一次 Agent 回答携带的工具调用轨迹（查画像 / 检索理论），渲染在对应气泡上方 */
    val agentToolCalls: List<AiDto.AgentToolCall> = emptyList(),
    val agentSteps: Int = 0,
    val error: String = "",
) {
    /** 输入框是否应禁用：请求中或流式输出中都禁用，避免同会话并发（含表达改写流式） */
    val isBusy: Boolean get() = isLoading || isStreaming || isRewriteStreaming

    /** 是否正在思考（收到了思考增量但正文还没来） */
    val isThinking: Boolean get() = isStreaming && streamingContent.isEmpty()
}

sealed class AiChatUiEvent {
    data class ShowError(val message: String) : AiChatUiEvent()
}

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    // 流式回答期间挂前台服务保活，退后台不被系统掐断
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    /** 本次流的起始时刻，用于算出「已深度思考 N 秒」 */
    private var streamStartedAt = 0L

    /** 表达改写流式协程与取消上下文 */
    private var rewriteStreamJob: Job? = null
    private var rewriteGenerationId: Long = 0
    private var rewriteStopRequested = false

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
        stopRewrite()
        _uiState.update {
            it.copy(
                showRewriteSheet = false,
                rewriteVersions = emptyList(),
                rewriteOriginal = "",
                rewriteStreamContent = "",
                rewriteThinking = "",
                isRewriteStreaming = false,
            )
        }
    }

    fun rewriteExpression() {
        val state = _uiState.value
        val content = state.inputText.trim()
        if (content.isEmpty() || state.isBusy) return

        rewriteStopRequested = false
        rewriteGenerationId = 0

        // 弹层立刻打开：正文流式期间就能逐字看到，不再空等十几秒
        _uiState.update {
            it.copy(
                isRewriteStreaming = true,
                rewriteStreamContent = "",
                rewriteThinking = "",
                showRewriteSheet = true,
                rewriteVersions = emptyList(),
                rewriteOriginal = content,
                inputText = "",
                error = "",
            )
        }
        AiStreamKeepAlive.start(appContext)

        rewriteStreamJob = viewModelScope.launch {
            aiRepository.rewriteExpressionStream(content).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> rewriteGenerationId = ev.generationId

                    is GenerationStreamEvent.Thinking -> _uiState.update {
                        it.copy(rewriteThinking = it.rewriteThinking + ev.content)
                    }

                    is GenerationStreamEvent.Delta -> _uiState.update {
                        it.copy(rewriteStreamContent = it.rewriteStreamContent + ev.content)
                    }

                    is GenerationStreamEvent.Structuring -> Unit

                    is GenerationStreamEvent.Finished -> {
                        _uiState.update {
                            it.copy(
                                isRewriteStreaming = false,
                                rewriteVersions = parseRewriteVersions(ev.structured),
                                rewriteStreamContent = ev.content.ifBlank { it.rewriteStreamContent },
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }

                    is GenerationStreamEvent.Failure -> if (!rewriteStopRequested) {
                        _uiState.update { it.copy(isRewriteStreaming = false, error = ev.message) }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时收敛状态
            if (!rewriteStopRequested && _uiState.value.isRewriteStreaming) {
                _uiState.update { it.copy(isRewriteStreaming = false) }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /** 用户点「停止生成」。半成品保留在弹层里，服务端也存了 interrupted。 */
    fun stopRewrite() {
        if (!_uiState.value.isRewriteStreaming) return
        rewriteStopRequested = true
        _uiState.update { it.copy(isRewriteStreaming = false) }
        val id = rewriteGenerationId
        viewModelScope.launch {
            if (id != 0L) aiRepository.cancelGeneration(id)
        }
        rewriteStreamJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }

    /** `Finished.structured` 的 `rewrites` → 弹层版本卡片 */
    private fun parseRewriteVersions(structured: Map<String, Any?>?): List<AiDto.RewriteVersion> {
        val list = (structured?.get("rewrites") as? List<*>) ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val style = m["style"] as? String ?: return@mapNotNull null
            val text = m["content"] as? String ?: return@mapNotNull null
            AiDto.RewriteVersion(style, text)
        }
    }

    fun applyRewrite(content: String) {
        _uiState.update {
            it.copy(
                inputText = content,
                showRewriteSheet = false,
                rewriteVersions = emptyList(),
                rewriteStreamContent = "",
                rewriteThinking = "",
                isRewriteStreaming = false,
            )
        }
    }

    /**
     * Agent 问答：与 [sendMessage] 的区别是走 `POST /ai/agent`，
     * 模型自主决定是否调用工具（查关系画像 / 检索依恋理论）后作答。
     * 工具调用轨迹存进 [AiChatUiState.agentToolCalls]，UI 在对应气泡上方展示。
     * Agent 路径没有会话管理，消息只保留在内存里。
     */
    fun askAgent() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty() || state.isBusy) return

        val history = state.messages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(10)
            .map { AiDto.AgentHistoryItem(role = it.role, content = it.content.take(4000)) }

        val userMessage = AiDto.MessageResponse(
            id = System.currentTimeMillis(),
            role = "user",
            content = text,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isLoading = true,
                agentToolCalls = emptyList(),
                agentSteps = 0,
                error = "",
            )
        }

        viewModelScope.launch {
            aiRepository.agentChat(text, history).fold(
                onSuccess = { result ->
                    val aiMessage = AiDto.MessageResponse(
                        id = System.currentTimeMillis(),
                        role = "assistant",
                        content = result.answer,
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            messages = it.messages + aiMessage,
                            agentToolCalls = result.toolCalls,
                            agentSteps = result.steps,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "Agent 服务异常，请稍后重试")
                    }
                },
            )
        }
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
        AiStreamKeepAlive.stop(appContext)
    }

    override fun onCleared() {
        super.onCleared()
        // 页面销毁时流式协程会随之取消，保活服务没有存在的必要了
        AiStreamKeepAlive.stop(appContext)
    }
}
