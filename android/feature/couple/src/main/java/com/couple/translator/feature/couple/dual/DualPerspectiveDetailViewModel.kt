package com.couple.translator.feature.couple.dual

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import com.couple.translator.feature.couple.data.repository.DualPerspectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DualPerspectiveDetailUiState(
    val event: DualPerspectiveDto.DualEventDetailResponse? = null,
    val isLoading: Boolean = false,
    val error: String = "",
    val revealed: Boolean = false,
    // ---- AI 双视角对照总结 ----
    /** 回读到的已保存总结（按事件维度保存，换事件互不覆盖） */
    val summaryText: String = "",
    /** 首次回读是否已完成；完成前不渲染生成入口，避免回读慢时重复点按 */
    val summaryLoaded: Boolean = false,
    val isSummaryGenerating: Boolean = false,
    val summaryStreamText: String = "",
    val isSummaryThinking: Boolean = false,
    val summaryThinkingText: String = "",
    val summaryThinkingSeconds: Int = 0,
) {

    /** 双方都写下视角后才谈得上「对照总结」。 */
    val canSummarize: Boolean
        get() = (event?.records?.size ?: 0) >= 2
}

@HiltViewModel
class DualPerspectiveDetailViewModel @Inject constructor(
    private val repository: DualPerspectiveRepository,
    private val aiRepository: AiRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DualPerspectiveDetailUiState())
    val uiState: StateFlow<DualPerspectiveDetailUiState> = _uiState.asStateFlow()

    private var summaryJob: Job? = null
    private var summaryStopRequested = false
    private var summaryStartedAt = 0L
    private var currentEventId: Long = 0

    fun loadEvent(eventId: Long) {
        currentEventId = eventId
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getEventDetail(eventId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        _uiState.update { state ->
                            state.copy(
                                event = it,
                                isLoading = false,
                                revealed = it.status == "completed",
                            )
                        }
                        loadSavedSummary(eventId)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun revealRecords(eventId: Long) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.revealRecords(eventId).fold(
                onSuccess = { detail ->
                    detail?.let {
                        _uiState.update { state ->
                            state.copy(event = it, isLoading = false, revealed = true)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "操作失败")
                    }
                },
            )
        }
    }

    // ==================== AI 双视角对照总结 ====================

    /** 进页面先回读本事件上次保存的总结。 */
    private fun loadSavedSummary(eventId: Long) {
        viewModelScope.launch {
            val payload = aiRepository.getSavedDualSummary(eventId).getOrNull()
            _uiState.update {
                it.copy(
                    summaryText = payload?.content?.trim().orEmpty(),
                    summaryLoaded = true,
                )
            }
        }
    }

    /** 用户点「停止生成」：收敛本地状态，服务端靠连接断开自己止血。 */
    fun stopSummary() {
        if (!_uiState.value.isSummaryGenerating) return
        summaryStopRequested = true
        summaryJob?.cancel()
        summaryJob = null
        AiStreamKeepAlive.stop(appContext)
        _uiState.update {
            it.copy(
                isSummaryGenerating = false,
                isSummaryThinking = false,
                // 已流出来的内容保留展示，不算白生成
                summaryText = it.summaryStreamText.trim(),
                summaryStreamText = "",
            )
        }
    }

    /** 生成（或重新生成）双视角对照总结。 */
    fun startSummary() {
        val eventId = currentEventId
        if (eventId <= 0L) return

        summaryJob?.cancel()
        summaryStopRequested = false
        summaryStartedAt = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                isSummaryGenerating = true,
                isSummaryThinking = true,
                summaryStreamText = "",
                summaryThinkingText = "",
                summaryThinkingSeconds = 0,
                error = "",
            )
        }

        AiStreamKeepAlive.start(appContext)

        summaryJob = viewModelScope.launch {
            aiRepository.dualSummaryStream(eventId).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> Unit

                    is GenerationStreamEvent.Thinking -> _uiState.update {
                        it.copy(summaryThinkingText = it.summaryThinkingText + ev.content)
                    }

                    is GenerationStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            summaryStreamText = it.summaryStreamText + ev.content,
                            isSummaryThinking = false,
                            summaryThinkingSeconds = it.summaryThinkingSeconds
                                .takeIf { s -> s > 0 } ?: elapsedSeconds(),
                        )
                    }

                    is GenerationStreamEvent.Structuring -> Unit // 纯 Markdown，无结构化阶段

                    is GenerationStreamEvent.Finished -> {
                        _uiState.update { state ->
                            state.copy(
                                isSummaryGenerating = false,
                                isSummaryThinking = false,
                                summaryStreamText = "",
                                summaryText = ev.content.trim().ifBlank { state.summaryText },
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }

                    is GenerationStreamEvent.Failure -> if (!summaryStopRequested) {
                        _uiState.update {
                            it.copy(
                                isSummaryGenerating = false,
                                isSummaryThinking = false,
                                error = ev.message,
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时状态必须收敛
            if (!summaryStopRequested && _uiState.value.isSummaryGenerating) {
                _uiState.update {
                    it.copy(
                        isSummaryGenerating = false,
                        isSummaryThinking = false,
                        summaryText = it.summaryStreamText.trim().ifBlank { it.summaryText },
                        summaryStreamText = "",
                    )
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    private fun elapsedSeconds(): Int {
        if (summaryStartedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - summaryStartedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
    }

    override fun onCleared() {
        super.onCleared()
        summaryJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }
}
