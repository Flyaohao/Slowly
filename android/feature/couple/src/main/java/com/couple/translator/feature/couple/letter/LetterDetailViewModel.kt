package com.couple.translator.feature.couple.letter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.repository.LetterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

data class LetterDetailUiState(
    val letter: LetterDto.LetterResponse? = null,
    /** 结构化解读结果。流式期间为 null，`done` 帧到达后才填上。 */
    val understanding: LetterDto.LetterUnderstanding? = null,
    /** 流式正文，逐字增长。流结束后会被服务端下发的终稿覆盖（去掉了尾部空行）。 */
    val streamContent: String = "",
    /** 推理模型的思考过程累积，只喂「深度思考」面板。 */
    val thinkingContent: String = "",
    val isLoading: Boolean = false,
    /** 是否正在生成（思考中、正文中、整理结构化结果中都算）。 */
    val isStreaming: Boolean = false,
    /** 正在思考：收到了思考增量，正文还没开始。 */
    val isThinking: Boolean = false,
    /** 正文已说完，正在整理结构化结果。 */
    val isStructuring: Boolean = false,
    /** 从发问到出正文之间的等待秒数。 */
    val thinkingSeconds: Int = 0,
    /** 是否展示解读面板。 */
    val showUnderstanding: Boolean = false,
    /** 最近一次生成的状态：done / interrupted。中断的半成品同样会保留展示。 */
    val understandingStatus: String = "",
    val error: String = "",

    // ---- AI 回信建议 ----
    // 这套接口一直存在（POST /ai/generate-reply），此前全仓没有任何 UI 调用它。
    val isReplyLoading: Boolean = false,
    val reply: ReplySuggestion? = null,
    val replyError: String = "",
    val showReply: Boolean = false,
) {
    /** 解读结果是否已经拿到了结构化字段（决定渲染要点卡片还是纯正文）。 */
    val hasStructured: Boolean get() = understanding != null
}

/** AI 回信建议：几个可以直接用的版本 + 一句「别这么说」。 */
data class ReplySuggestion(
    val summary: String = "",
    val variants: List<ReplyVariantUi> = emptyList(),
    val doNotSay: String = "",
    val riskLevel: String = "normal",
)

data class ReplyVariantUi(val style: String, val content: String)

sealed class LetterDetailUiEvent {
    data class ShowError(val message: String) : LetterDetailUiEvent()
    object LetterDeleted : LetterDetailUiEvent()
}

@HiltViewModel
class LetterDetailViewModel @Inject constructor(
    private val letterRepository: LetterRepository,
    // 保活服务要用 applicationContext 启动，不能拿 Activity 的 context
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LetterDetailUiState())
    val uiState: StateFlow<LetterDetailUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<LetterDetailUiEvent>()
    val event: SharedFlow<LetterDetailUiEvent> = _event.asSharedFlow()

    /** 当前这次生成的协程。持有它才能让用户主动叫停。 */
    private var streamJob: Job? = null
    /** 服务端分配的生成 id，取消时要回传。 */
    private var generationId: Long = 0
    private var streamStartedAt: Long = 0

    /**
     * 用户是否按了「停止生成」。
     *
     * 用途是抑制后续事件：停止后流还可能吐出一两个残留帧，若照单全收，
     * 刚清空的正文会突然又冒出来一截。
     */
    private var stopRequested = false

    fun loadLetter(id: Long) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            letterRepository.getLetter(id).fold(
                onSuccess = { letter ->
                    _uiState.update { it.copy(letter = letter, isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
        loadSavedUnderstanding(id)
    }

    /**
     * 回读上次的解读。
     *
     * 这是「退出再进来还能看到」的落点：结果早就存在服务端的 `ai_generation` 里，
     * 页面进来先读一次即可，不必重新扣一次模型调用。
     *
     * 拿不到就静默结束——「这封信还没解读过」是正常状态，不该弹报错。
     *
     * [delayMs] 是给「连接中断后回读」用的：服务端要等下一次往流里写失败，
     * 才知道对端已经走了，半成品落库有 1~2 秒延迟。中断瞬间立刻回读，
     * 多半只能拿到刚建好的空记录。
     */
    private fun loadSavedUnderstanding(id: Long, delayMs: Long = 0) {
        viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            letterRepository.getSavedUnderstanding(id).onSuccess { payload ->
                if (payload == null) return@onSuccess
                // 只有正文和结构化字段都没有时才认为「没东西可显示」，
                // 那种情况通常是上次生成直接失败了，不如留白让用户手点。
                val hasSomething = payload.content.isNotBlank() || payload.structuredOutput != null
                if (!hasSomething) return@onSuccess
                _uiState.update {
                    it.copy(
                        understanding = payload.structuredOutput,
                        streamContent = payload.content,
                        thinkingContent = payload.thinking,
                        understandingStatus = payload.status,
                        showUnderstanding = true,
                    )
                }
            }
        }
    }

    fun toggleFavorite() {
        val letter = _uiState.value.letter ?: return
        viewModelScope.launch {
            letterRepository.toggleFavorite(letter.id).fold(
                onSuccess = { updated ->
                    _uiState.update { it.copy(letter = updated) }
                },
                onFailure = { error ->
                    _event.emit(LetterDetailUiEvent.ShowError(error.message ?: "操作失败"))
                },
            )
        }
    }

    /**
     * 拉取 / 重新拉取 AI 理解。
     *
     * 流式生成期间再点一次等价于「停止生成」——按钮在 UI 上也是这么变化的，
     * 免得用户找不到中止的地方。
     */
    fun understandLetter() {
        if (_uiState.value.isStreaming) {
            stopUnderstanding()
            return
        }

        val letter = _uiState.value.letter ?: return

        stopRequested = false
        generationId = 0
        streamStartedAt = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                isStreaming = true,
                isThinking = true,
                isStructuring = false,
                streamContent = "",
                thinkingContent = "",
                understanding = null,
                thinkingSeconds = 0,
                understandingStatus = "",
                showUnderstanding = true,
                error = "",
            )
        }

        // 生成期间挂前台服务保活：退后台后进程不被冻结、网络不受限，
        // 否则 socket 一断服务端就把这轮生成按 interrupted 收尾了
        AiStreamKeepAlive.start(appContext)

        streamJob = viewModelScope.launch {
            letterRepository.understandLetterStream(letter.id).collect { ev ->
                when (ev) {
                    is LetterDto.LetterStreamEvent.Started -> generationId = ev.generationId

                    // 思考增量只喂「深度思考」面板，不拼进正文
                    is LetterDto.LetterStreamEvent.Thinking -> _uiState.update {
                        it.copy(thinkingContent = it.thinkingContent + ev.content)
                    }

                    is LetterDto.LetterStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            streamContent = it.streamContent + ev.content,
                            // 正文一开始，思考就结束了：面板转为折叠并标注耗时
                            isThinking = false,
                            isStructuring = false,
                            thinkingSeconds = it.thinkingSeconds
                                .takeIf { s -> s > 0 } ?: elapsedSeconds(),
                        )
                    }

                    is LetterDto.LetterStreamEvent.Structuring -> _uiState.update {
                        it.copy(isStructuring = true, isThinking = false)
                    }

                    is LetterDto.LetterStreamEvent.Finished -> finishStream(ev)

                    is LetterDto.LetterStreamEvent.Failure -> if (!stopRequested) {
                        _uiState.update {
                            it.copy(isStreaming = false, isThinking = false, isStructuring = false)
                        }
                        // 连接断了不等于结果没了：这一轮生成在服务端多半已经落库
                        // （跑完存 done、被掐断存 interrupted），而且它是在流式开始
                        // 时就建好记录、逐段更新的。回读一次把内容捞回来，免得界面
                        // 只剩一句报错——用户干等了二十秒，不该什么都看不到。
                        // 隔 1.8s 再读，等服务端那侧先发现对端已断并把半成品存下来。
                        _uiState.value.letter?.let { loadSavedUnderstanding(it.id, delayMs = 1800) }
                        _event.emit(LetterDetailUiEvent.ShowError(ev.message))
                    }
                }
            }

            // 兜底：服务端如果没发 done 就断开，这里把状态收敛掉，
            // 否则「生成中」会一直挂着，按钮永远停在「停止生成」。
            if (!stopRequested && _uiState.value.isStreaming) {
                _uiState.update {
                    it.copy(isStreaming = false, isThinking = false, isStructuring = false)
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /**
     * 用户点「停止生成」。
     *
     * 顺序很讲究：**先改本地状态，再通知服务端**。本地立即响应，用户按下去
     * 就见效；服务端那边要断开连接、通知模型停手，存在一两秒延迟，
     * 等它回来才更新界面的话，按钮就像没反应。
     *
     * 注意「停止」不等于「丢弃」：已经生成的那半段照常留在界面上，
     * 服务端也会以 `interrupted` 状态把它存下来，下次进来还能看到。
     */
    fun stopUnderstanding() {
        if (!_uiState.value.isStreaming) return
        stopRequested = true
        AiStreamKeepAlive.stop(appContext)

        _uiState.update {
            it.copy(
                isStreaming = false,
                isThinking = false,
                isStructuring = false,
                understandingStatus = "interrupted",
                showUnderstanding = it.streamContent.isNotBlank() || it.understanding != null,
            )
        }

        val id = generationId
        viewModelScope.launch {
            if (id != 0L) {
                letterRepository.cancelGeneration(id)
            }
            // 后端停手后连接会收尾，这里再主动取消一次，让 body 尽早关闭
            streamJob?.cancel()
        }
    }

    fun dismissUnderstanding() {
        _uiState.update { it.copy(showUnderstanding = false) }
    }

    /**
     * AI 建议怎么回。
     *
     * 与「AI 帮我理解」是两件事：那个读懂**来信**，这个产出**拟回复**。
     * 所以状态也完全独立（isReply* 而非复用 isStreaming），同时进行时不会互相覆盖。
     *
     * 走同步接口而不是流式：回信建议的产物是「几个可选版本」的列表，
     * 用户要的是挑一条来用，不是看着它一个字一个字长出来。
     */
    fun generateReply() {
        val letter = _uiState.value.letter ?: return
        if (_uiState.value.isReplyLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isReplyLoading = true, replyError = "", showReply = true)
            }
            letterRepository.generateReply(letter.id).fold(
                onSuccess = { response ->
                    val result = response?.reply
                    _uiState.update {
                        it.copy(
                            isReplyLoading = false,
                            reply = result?.let { r ->
                                ReplySuggestion(
                                    summary = r.summary.orEmpty(),
                                    variants = r.replies.map { v ->
                                        ReplyVariantUi(style = v.style, content = v.content)
                                    },
                                    doNotSay = r.doNotSay.orEmpty(),
                                    riskLevel = r.riskLevel.orEmpty(),
                                )
                            },
                            // 有回信但结构化字段缺失时给一句可执行的提示，
                            // 而不是留一片空白让用户以为按钮坏了
                            replyError = if (result == null) "这次没能给出建议，可以再试一次" else "",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isReplyLoading = false, replyError = e.message ?: "生成失败")
                    }
                },
            )
        }
    }

    fun dismissReply() {
        _uiState.update { it.copy(showReply = false) }
    }

    fun deleteLetter() {
        val letter = _uiState.value.letter ?: return
        viewModelScope.launch {
            letterRepository.deleteLetter(letter.id).fold(
                onSuccess = {
                    _event.emit(LetterDetailUiEvent.LetterDeleted)
                },
                onFailure = { error ->
                    _event.emit(LetterDetailUiEvent.ShowError(error.message ?: "删除失败"))
                },
            )
        }
    }

    private fun finishStream(ev: LetterDto.LetterStreamEvent.Finished) {
        _uiState.update {
            it.copy(
                understanding = ev.structured,
                // 用服务端终稿覆盖流式拼接：它去掉了正文与分隔符之间的空行，
                // 也保证界面显示的和库里存的是同一份。
                streamContent = ev.content.ifBlank { it.streamContent },
                thinkingContent = ev.thinking.ifBlank { it.thinkingContent },
                understandingStatus = ev.status,
                isStreaming = false,
                isThinking = false,
                isStructuring = false,
                showUnderstanding = true,
            )
        }
        AiStreamKeepAlive.stop(appContext)
    }

    /** 从发问算起的等待秒数，至少 1 秒（避免显示「已深度思考 0 秒」）。 */
    private fun elapsedSeconds(): Int {
        if (streamStartedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - streamStartedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
    }

    override fun onCleared() {
        super.onCleared()
        // 页面销毁时流式协程会随之取消，保活服务没有存在的必要了
        AiStreamKeepAlive.stop(appContext)
    }
}
