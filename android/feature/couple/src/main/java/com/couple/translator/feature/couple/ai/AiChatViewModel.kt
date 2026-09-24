package com.couple.translator.feature.couple.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import com.couple.translator.feature.couple.data.repository.AnniversaryRepository
import com.couple.translator.feature.couple.data.repository.LetterRepository
import com.couple.translator.core.network.GenerationStreamEvent
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

/**
 * P0-8 引用 chip：同一时刻只允许一个，再选即替换。
 * [body] 是引用正文原文（可能很长），发送时按 §2.3 截断拼进 message。
 */
data class QuoteChip(
    val sourceLabel: String,
    val body: String,
    val originId: Long,
    val originRole: String? = null,
)

/** 「＋」二级引用选择器的三种来源 */
enum class QuotePickerType { MESSAGE, LETTER, ANNIVERSARY }

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
    /** P0-5：最近一次回答的判断依据（画像/记忆/理论）；新提问时清空 */
    val evidence: AiDto.EvidencePayload? = null,
    /** P0-8：当前引用 chip（发送/切场景后清空） */
    val quoteChip: QuoteChip? = null,
    /** P0-8：信件选择器数据（打开「引用一封信」时加载） */
    val quoteLetters: List<LetterDto.LetterResponse> = emptyList(),
    /** P0-8：纪念日选择器数据 */
    val quoteAnniversaries: List<AnniversaryDto.AnniversaryResponse> = emptyList(),
    val quotePickerLoading: Boolean = false,
    /** P0-8：加载失败必须可见，不许静默（项目红线） */
    val quotePickerError: String = "",
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
    // P0-8：引用一封信 / 附上纪念日 —— 两者均已存在、Hilt 可注入，禁止新建 Repository
    private val letterRepository: LetterRepository,
    private val anniversaryRepository: AnniversaryRepository,
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
        // P0-8：切场景清引用（§2.1 清空时机）
        _uiState.update { it.copy(sceneKey = sceneKey, quoteChip = null) }
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

    // ------------------------------------------------------------------ #
    // P0-8 引用：chip 状态 + 二级选择器数据加载（复用既有 Repository）
    // ------------------------------------------------------------------ //

    fun setQuoteChip(chip: QuoteChip) {
        _uiState.update { it.copy(quoteChip = chip) }
    }

    fun clearQuoteChip() {
        _uiState.update { it.copy(quoteChip = null) }
    }

    /** 打开对应类型的引用选择器前调用：消息走本地 state，信/纪念日拉网。 */
    fun loadQuotePickerData(type: QuotePickerType) {
        when (type) {
            QuotePickerType.MESSAGE -> _uiState.update {
                it.copy(quotePickerLoading = false, quotePickerError = "")
            }
            QuotePickerType.LETTER -> {
                _uiState.update { it.copy(quotePickerLoading = true, quotePickerError = "") }
                viewModelScope.launch {
                    letterRepository.getInbox().fold(
                        onSuccess = { resp ->
                            _uiState.update {
                                it.copy(
                                    quoteLetters = resp?.items.orEmpty(),
                                    quotePickerLoading = false,
                                )
                            }
                        },
                        onFailure = { e ->
                            // 红线：加载失败不许静默
                            _uiState.update {
                                it.copy(
                                    quotePickerLoading = false,
                                    quotePickerError = e.message ?: "加载信件失败",
                                )
                            }
                        },
                    )
                }
            }
            QuotePickerType.ANNIVERSARY -> {
                _uiState.update { it.copy(quotePickerLoading = true, quotePickerError = "") }
                viewModelScope.launch {
                    anniversaryRepository.getAnniversaries().fold(
                        onSuccess = { resp ->
                            _uiState.update {
                                it.copy(
                                    quoteAnniversaries = resp?.items.orEmpty(),
                                    quotePickerLoading = false,
                                )
                            }
                        },
                        onFailure = { e ->
                            _uiState.update {
                                it.copy(
                                    quotePickerLoading = false,
                                    quotePickerError = e.message ?: "加载纪念日失败",
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        // §2.3 长度红线：后端 ChatRequest.message max_length=2000，超了直接 422
        const val QUOTE_BODY_MAX = 600
        const val MESSAGE_SOFT_MAX = 1900
        const val MESSAGE_HARD_MAX = 2000

        /**
         * §2.2 拼接 + §2.3 截断。返回 (最终 message, 是否发生截断)。
         *
         * 顺序：引用正文先截 600 → 拼接 → 总长 >1900 则压引用正文预算
         * `1900 - header - 输入` → 仍 >2000 硬截到 2000。绝不发出 >2000 的 message。
         */
        fun assembleMessage(userInput: String, quote: QuoteChip?): Pair<String, Boolean> {
            if (quote == null) return userInput to false
            var body = quote.body
            var truncated = false
            if (body.length > QUOTE_BODY_MAX) {
                body = body.take(QUOTE_BODY_MAX) + "…"
                truncated = true
            }
            val prefix = "【引用·${quote.sourceLabel}】"
            val sep = "\n——\n"
            var msg = prefix + body + sep + userInput
            if (msg.length > MESSAGE_SOFT_MAX) {
                // 先把引用正文压到 1900 - 用户输入（再扣 header/sep）
                val bodyBudget = MESSAGE_SOFT_MAX - prefix.length - sep.length - userInput.length
                body = if (bodyBudget <= 0) {
                    ""
                } else {
                    val cut = quote.body.take(bodyBudget)
                    if (quote.body.length > bodyBudget) cut + "…" else cut
                }
                truncated = true
                msg = prefix + body + sep + userInput
            }
            if (msg.length > MESSAGE_HARD_MAX) {
                msg = msg.take(MESSAGE_HARD_MAX)
                truncated = true
            }
            return msg to truncated
        }

        /** chip 是否会触发截断（供 UI 显示「引用已自动精简」） */
        fun willTruncate(userInput: String, quote: QuoteChip?): Boolean {
            return assembleMessage(userInput, quote).second
        }
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
     * 发送消息，走 SSE 流式。
     *
     * 与旧实现的区别：不再等整段回答生成完才显示。
     * 收到 `meta` 立即绑定会话、收到 `delta` 立即追加文本、收到 `done` 才固化成正式消息。
     * 中途失败时**保留已收到的部分内容**，避免用户看到的内容整段消失。
     */
    fun sendMessage() {
        val state = _uiState.value
        val rawInput = state.inputText.trim()
        if (rawInput.isEmpty() || state.isBusy) return

        // P0-8 §2：有引用时按规范拼进 message（唯一能进模型的通道）
        val text = assembleMessage(rawInput, state.quoteChip).first

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
                // §2.1：发送成功（消息已入列表）后清空引用
                quoteChip = null,
                isStreaming = true,
                streamingContent = "",
                thinkingContent = "",
                thinkingFinished = false,
                thinkingSeconds = 0,
                evidence = null,
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

                    // P0-5：evidence 帧在 done 之前到达，只存不拼正文
                    is AiDto.ChatStreamEvent.Evidence -> _uiState.update {
                        it.copy(evidence = ev.payload)
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
