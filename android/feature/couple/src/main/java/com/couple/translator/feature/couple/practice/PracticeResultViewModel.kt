package com.couple.translator.feature.couple.practice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import com.couple.translator.feature.couple.data.repository.PracticeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PracticeResultUiState(
    val record: PracticeDto.PracticeRecordDetailResponse? = null,
    val isLoading: Boolean = false,
    val error: String = "",
    // ---- AI 练习整理 ----
    val summaryText: String = "",
    /** 首次回读是否已完成；完成前不渲染生成入口，避免回读慢时重复点按 */
    val summaryLoaded: Boolean = false,
    val isSummaryGenerating: Boolean = false,
    val summaryStreamText: String = "",
    val isSummaryThinking: Boolean = false,
    val summaryThinkingText: String = "",
    val summaryThinkingSeconds: Int = 0,
) {

    /** 至少有一方作答才谈得上整理。 */
    val canSummarize: Boolean
        get() = !record?.mySubmission.isNullOrBlank() || !record?.partnerSubmission.isNullOrBlank()
}

@HiltViewModel
class PracticeResultViewModel @Inject constructor(
    private val repository: PracticeRepository,
    private val aiRepository: AiRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeResultUiState())
    val uiState: StateFlow<PracticeResultUiState> = _uiState.asStateFlow()

    private var summaryJob: Job? = null
    private var summaryStopRequested = false
    private var summaryStartedAt = 0L
    private var currentRecordId: Long = 0

    fun loadResult(recordId: Long) {
        currentRecordId = recordId
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getRecordDetail(recordId).fold(
                onSuccess = { record ->
                    _uiState.update { it.copy(record = record, isLoading = false) }
                    loadSavedSummary(recordId)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    // ==================== AI 练习整理 ====================

    /** 进页面先回读这条练习记录上次保存的整理。 */
    private fun loadSavedSummary(recordId: Long) {
        viewModelScope.launch {
            val payload = aiRepository.getSavedPracticeSummary(recordId).getOrNull()
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
                summaryText = it.summaryStreamText.trim(),
                summaryStreamText = "",
            )
        }
    }

    /** 生成（或重新生成）练习整理。 */
    fun startSummary() {
        val recordId = currentRecordId
        if (recordId <= 0L) return

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
            aiRepository.practiceSummaryStream(recordId).collect { ev ->
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
