package com.couple.translator.feature.couple.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewUiState(
    /** 用户输入的事件经过 */
    val description: String = "",
    val context: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
    // ---- 结构化复盘结果（来自 ai_generation，退出重进可回读）----
    val review: AiDto.ReviewResult? = null,
    val savedContent: String = "",
    // ---- 流式生成中的中间态 ----
    val isGenerating: Boolean = false,
    val streamText: String = "",
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val thinkingSeconds: Int = 0,
    val isStructuring: Boolean = false,
) {
    /** 已经有可展示的复盘内容（回读到的或在生成的） */
    val hasReview: Boolean
        get() = review != null || savedContent.isNotBlank() || isGenerating
}

/**
 * 关系复盘 ViewModel。
 *
 * 对应功能设计 六.9：输入一次争吵 / 冷战 / 和好的经过，AI 输出触发点、
 * 双方真实需求、误解发生处、升级冲突的话语、降温话术、下次可提前使用的表达。
 *
 * 三条与其它流式页面一致的硬约束：
 * 1. 解码一律复用 core 的 [GenerationStreamEvent] 基建，不自己解析 SSE；
 * 2. 生成期间挂 [AiStreamKeepAlive] 前台服务，退后台进程不被冻结；
 * 3. 所有 isGenerating → false 的路径（停止 / 失败 / done / 兜底 / onCleared）
 *    都必须 stop 保活，漏一条会让前台服务常驻。
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState(isLoading = true))
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var stopRequested = false
    private var streamStartedAt = 0L
    private var generationId: Long = 0

    init {
        loadSaved()
    }

    /**
     * 进页面先回读上次复盘结果。
     *
     * ai_generation 是覆盖式的（同一用户 + 同一 kind 只留最新一条），
     * 所以这里读到的就是"上次那次"。没有内容是正常情况，不是错误。
     */
    private fun loadSaved() {
        viewModelScope.launch {
            val result = aiRepository.getSavedReview()
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess { payload ->
                if (payload == null) return@onSuccess
                val parsed = aiRepository.parseReview(payload.structuredOutput)
                _uiState.update {
                    it.copy(
                        review = parsed,
                        savedContent = payload.content,
                    )
                }
            }
        }
    }

    fun onDescriptionChange(value: String) {
        _uiState.update { it.copy(description = value) }
    }

    fun onContextChange(value: String) {
        _uiState.update { it.copy(context = value) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    /** 用户点「停止生成」：收敛本地状态，服务端靠连接断开自己止血。 */
    fun stopReview() {
        if (!_uiState.value.isGenerating) return
        stopRequested = true
        streamJob?.cancel()
        streamJob = null
        AiStreamKeepAlive.stop(appContext)
        _uiState.update { it.copy(isGenerating = false, isThinking = false, isStructuring = false) }
    }

    /** 重新复盘：清掉旧结果再生成。 */
    fun startReview() {
        val description = _uiState.value.description.trim()
        if (description.isBlank()) {
            _uiState.update { it.copy(error = "先说说发生了什么吧") }
            return
        }
        streamJob?.cancel()
        stopRequested = false
        streamStartedAt = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                isGenerating = true,
                isThinking = true,
                isStructuring = false,
                streamText = "",
                thinkingText = "",
                thinkingSeconds = 0,
                review = null,
                savedContent = "",
                error = "",
            )
        }

        AiStreamKeepAlive.start(appContext)

        streamJob = viewModelScope.launch {
            aiRepository.reviewStream(description, _uiState.value.context.trim().ifBlank { null })
                .collect { ev ->
                    when (ev) {
                        is GenerationStreamEvent.Started -> generationId = ev.generationId

                        is GenerationStreamEvent.Thinking -> _uiState.update {
                            it.copy(thinkingText = it.thinkingText + ev.content)
                        }

                        is GenerationStreamEvent.Delta -> _uiState.update {
                            it.copy(
                                streamText = it.streamText + ev.content,
                                isThinking = false,
                                isStructuring = false,
                                thinkingSeconds = it.thinkingSeconds
                                    .takeIf { s -> s > 0 } ?: elapsedSeconds(),
                            )
                        }

                        is GenerationStreamEvent.Structuring -> _uiState.update {
                            it.copy(isStructuring = true, isThinking = false)
                        }

                        is GenerationStreamEvent.Finished -> finishStream(ev)

                        is GenerationStreamEvent.Failure -> if (!stopRequested) {
                            _uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    isThinking = false,
                                    isStructuring = false,
                                    error = ev.message,
                                )
                            }
                            AiStreamKeepAlive.stop(appContext)
                        }
                    }
                }

            // 兜底：服务端没发 done 就断开时状态必须收敛
            if (!stopRequested && _uiState.value.isGenerating) {
                _uiState.update {
                    it.copy(isGenerating = false, isThinking = false, isStructuring = false)
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /**
     * done 帧收尾：结构化字段齐全就填卡片，转不出来则退回纯正文
     * （模型偶尔不吐 JSON，此时正文还在，比一片空白强）。
     */
    private fun finishStream(ev: GenerationStreamEvent.Finished) {
        val parsed = aiRepository.parseReview(ev.structured)
        _uiState.update { state ->
            state.copy(
                isGenerating = false,
                isThinking = false,
                isStructuring = false,
                streamText = "",
                review = parsed,
                savedContent = if (parsed == null) ev.content else "",
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
        streamJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }
}
