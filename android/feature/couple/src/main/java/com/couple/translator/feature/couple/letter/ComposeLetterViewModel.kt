package com.couple.translator.feature.couple.letter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.core.network.GenerationStreamEvent
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

data class ComposeLetterUiState(
    val letterId: Long? = null,
    val title: String = "",
    val content: String = "",
    val letterType: String = "normal",
    val isPrivate: Boolean = false,
    val isSaving: Boolean = false,
    val isSending: Boolean = false,
    val isRewriting: Boolean = false,
    val showAiAssist: Boolean = false,
    val error: String = "",
)

sealed class ComposeLetterUiEvent {
    data class ShowError(val message: String) : ComposeLetterUiEvent()
    object LetterSent : ComposeLetterUiEvent()
    data class Rewritten(val content: String) : ComposeLetterUiEvent()
}

@HiltViewModel
class ComposeLetterViewModel @Inject constructor(
    private val letterRepository: LetterRepository,
    // AI 改写流式期间挂前台服务保活，退后台不被系统掐断
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeLetterUiState())
    val uiState: StateFlow<ComposeLetterUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<ComposeLetterUiEvent>()
    val event: SharedFlow<ComposeLetterUiEvent> = _event.asSharedFlow()

    private var autoSaveJob: Job? = null

    /** AI 改写流式协程与取消上下文 */
    private var rewriteStreamJob: Job? = null
    private var rewriteGenerationId: Long = 0
    private var rewriteStopRequested = false

    fun loadDraft(letterId: Long) {
        viewModelScope.launch {
            letterRepository.getLetter(letterId).fold(
                onSuccess = { letter ->
                    letter?.let {
                        _uiState.update { state ->
                            state.copy(
                                letterId = it.id,
                                title = it.title ?: "",
                                content = it.content ?: "",
                                letterType = it.letterType,
                                isPrivate = it.isPrivate,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "加载草稿失败") }
                },
            )
        }
    }

    fun onTitleChange(title: String) {
        _uiState.update { it.copy(title = title) }
        scheduleAutoSave()
    }

    fun onContentChange(content: String) {
        _uiState.update { it.copy(content = content) }
        scheduleAutoSave()
    }

    fun onLetterTypeChange(type: String) {
        _uiState.update { it.copy(letterType = type) }
    }

    fun onPrivateChange(isPrivate: Boolean) {
        _uiState.update { it.copy(isPrivate = isPrivate) }
    }

    fun showAiAssist() {
        _uiState.update { it.copy(showAiAssist = true) }
    }

    fun dismissAiAssist() {
        _uiState.update { it.copy(showAiAssist = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun saveDraft() {
        val state = _uiState.value
        if (state.title.isBlank() && state.content.isBlank()) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val request = LetterDto.LetterRequest(
                title = state.title,
                content = state.content,
                letterType = state.letterType,
                status = "draft",
                isPrivate = state.isPrivate,
            )

            val result = if (state.letterId != null) {
                letterRepository.updateLetter(state.letterId, request)
            } else {
                letterRepository.createLetter(request)
            }

            result.fold(
                onSuccess = { letter ->
                    letter?.let {
                        _uiState.update { state ->
                            state.copy(letterId = it.id, isSaving = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSaving = false, error = error.message ?: "保存失败") }
                },
            )
        }
    }

    fun sendLetter() {
        val state = _uiState.value
        if (state.content.isBlank()) {
            _uiState.update { it.copy(error = "请输入信件内容") }
            return
        }

        _uiState.update { it.copy(isSending = true, error = "") }
        viewModelScope.launch {
            // Step 1: Save as DRAFT first (not sent)
            val request = LetterDto.LetterRequest(
                title = state.title.ifBlank { null },
                content = state.content,
                letterType = state.letterType,
                status = "draft",
                isPrivate = state.isPrivate,
            )

            val letterId = state.letterId
            val saveResult = if (letterId != null) {
                letterRepository.updateLetter(letterId, request)
            } else {
                letterRepository.createLetter(request)
            }

            saveResult.fold(
                onSuccess = { letter ->
                    if (letter != null) {
                        // Update local letterId
                        _uiState.update { it.copy(letterId = letter.id) }

                        // Step 2: Call send endpoint (backend transitions draft -> sent)
                        letterRepository.sendLetter(letter.id).fold(
                            onSuccess = {
                                _uiState.update { it.copy(isSending = false) }
                                _event.emit(ComposeLetterUiEvent.LetterSent)
                            },
                            onFailure = { error ->
                                _uiState.update {
                                    it.copy(isSending = false, error = error.message ?: "发送失败")
                                }
                            },
                        )
                    } else {
                        _uiState.update {
                            it.copy(isSending = false, error = "保存失败")
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSending = false, error = error.message ?: "保存失败")
                    }
                },
            )
        }
    }

    /**
     * 流式「AI 改写」（v2.2 起走 ai_generation 流式端点）。
     *
     * 改写后的正文逐字打进编辑器（打字机直接替换原文），生成期间再点
     * 「AI 辅助」等价于停止。改写请求按 letter_id 定位，服务端读库里
     * 的最新草稿，所以开始流式前先确保草稿已保存。
     */
    fun rewriteLetter(style: String) {
        val state = _uiState.value
        if (state.content.isBlank()) {
            _uiState.update { it.copy(error = "请先输入信件内容") }
            return
        }
        // 生成中再触发 = 停止生成（与信件详情页「停止生成」同一交互）
        if (state.isRewriting) {
            stopRewrite()
            return
        }

        val originalContent = state.content
        rewriteStopRequested = false
        rewriteGenerationId = 0
        var receivedAny = false
        var editorCleared = false

        _uiState.update { it.copy(isRewriting = true, showAiAssist = false, error = "") }
        AiStreamKeepAlive.start(appContext)

        rewriteStreamJob = viewModelScope.launch {
            // Ensure letter is saved first
            val letterId = state.letterId ?: run {
                val saveReq = LetterDto.LetterRequest(
                    title = state.title,
                    content = state.content,
                    letterType = state.letterType,
                    status = "draft",
                    isPrivate = state.isPrivate,
                )
                letterRepository.createLetter(saveReq).getOrNull()?.also {
                    _uiState.update { s -> s.copy(letterId = it.id) }
                }?.id
            }

            if (letterId == null) {
                _uiState.update { it.copy(isRewriting = false, error = "保存失败") }
                AiStreamKeepAlive.stop(appContext)
                return@launch
            }

            letterRepository.rewriteLetterStream(letterId, style).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> rewriteGenerationId = ev.generationId

                    // 改写场景思考过程不单独展示，用户看正文打字机就够了
                    is GenerationStreamEvent.Thinking -> Unit

                    is GenerationStreamEvent.Delta -> {
                        receivedAny = true
                        _uiState.update { s ->
                            // 第一帧 delta 到达时清空编辑器（原文已存在草稿里），
                            // 之后逐字追加，形成打字机替换效果
                            val base = if (editorCleared) s.content else ""
                            editorCleared = true
                            s.copy(content = base + ev.content)
                        }
                    }

                    is GenerationStreamEvent.Structuring -> Unit

                    is GenerationStreamEvent.Finished -> {
                        _uiState.update { s ->
                            s.copy(
                                isRewriting = false,
                                content = ev.content.ifBlank { s.content },
                            )
                        }
                        _event.emit(ComposeLetterUiEvent.Rewritten(_uiState.value.content))
                        AiStreamKeepAlive.stop(appContext)
                    }

                    is GenerationStreamEvent.Failure -> if (!rewriteStopRequested) {
                        _uiState.update { s ->
                            s.copy(
                                isRewriting = false,
                                // 一个字都没收到就还原原文，别让编辑器莫名清空
                                content = if (receivedAny) s.content else originalContent,
                                error = ev.message,
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时收敛状态
            if (!rewriteStopRequested && _uiState.value.isRewriting) {
                _uiState.update { s ->
                    s.copy(
                        isRewriting = false,
                        content = if (receivedAny) s.content else originalContent,
                    )
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /** 用户中止流式改写。半成品保留在编辑器里，服务端存 interrupted。 */
    fun stopRewrite() {
        if (!_uiState.value.isRewriting) return
        rewriteStopRequested = true
        _uiState.update { it.copy(isRewriting = false) }
        val id = rewriteGenerationId
        viewModelScope.launch {
            if (id != 0L) letterRepository.cancelGeneration(id)
        }
        rewriteStreamJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(30_000)
            saveDraft()
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoSaveJob?.cancel()
        // 页面销毁时流式协程会随之取消，保活服务没有存在的必要了
        AiStreamKeepAlive.stop(appContext)
    }
}
