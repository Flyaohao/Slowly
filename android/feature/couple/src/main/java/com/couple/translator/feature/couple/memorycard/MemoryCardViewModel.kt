package com.couple.translator.feature.couple.memorycard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import com.couple.translator.feature.couple.data.repository.MuseumRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryCardUiState(
    val targetType: String = "anniversary",
    val targetId: Long = 0,
    /** 从来源页带过来的条目标题，用于顶栏与存入纪念馆时的默认标题 */
    val itemTitle: String = "",
    val error: String = "",
    // ---- 回忆卡片 ----
    val cardText: String = "",
    /** 首次回读是否已完成；完成前不渲染生成入口，避免回读慢时重复点按 */
    val cardLoaded: Boolean = false,
    val isGenerating: Boolean = false,
    val streamText: String = "",
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val thinkingSeconds: Int = 0,
    // ---- 存入纪念馆 ----
    val isSavingToMuseum: Boolean = false,
    val savedToMuseum: Boolean = false,
    val saveError: String = "",
)

@HiltViewModel
class MemoryCardViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val museumRepository: MuseumRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryCardUiState())
    val uiState: StateFlow<MemoryCardUiState> = _uiState.asStateFlow()

    private var cardJob: Job? = null
    private var stopRequested = false
    private var startedAt = 0L

    fun initPage(targetType: String, targetId: Long, itemTitle: String) {
        if (_uiState.value.targetId == targetId && _uiState.value.cardLoaded) return
        _uiState.update {
            it.copy(targetType = targetType, targetId = targetId, itemTitle = itemTitle)
        }
        loadSavedCard(targetType, targetId)
    }

    /** 进页面先回读这条条目上次的回忆卡片。 */
    private fun loadSavedCard(targetType: String, targetId: Long) {
        viewModelScope.launch {
            val payload = aiRepository.getSavedMemoryCard(targetType, targetId).getOrNull()
            _uiState.update {
                it.copy(
                    cardText = payload?.content?.trim().orEmpty(),
                    cardLoaded = true,
                )
            }
        }
    }

    /** 用户点「停止生成」：收敛本地状态，服务端靠连接断开自己止血。 */
    fun stopGeneration() {
        if (!_uiState.value.isGenerating) return
        stopRequested = true
        cardJob?.cancel()
        cardJob = null
        AiStreamKeepAlive.stop(appContext)
        _uiState.update {
            it.copy(
                isGenerating = false,
                isThinking = false,
                cardText = it.streamText.trim(),
                streamText = "",
            )
        }
    }

    /** 生成（或重新生成）回忆卡片。 */
    fun startGeneration() {
        val state = _uiState.value
        if (state.targetId <= 0L) return

        cardJob?.cancel()
        stopRequested = false
        startedAt = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                isGenerating = true,
                isThinking = true,
                streamText = "",
                thinkingText = "",
                thinkingSeconds = 0,
                saveError = "",
            )
        }

        AiStreamKeepAlive.start(appContext)

        cardJob = viewModelScope.launch {
            aiRepository.memoryCardStream(state.targetType, state.targetId).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> Unit

                    is GenerationStreamEvent.Thinking -> _uiState.update {
                        it.copy(thinkingText = it.thinkingText + ev.content)
                    }

                    is GenerationStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            streamText = it.streamText + ev.content,
                            isThinking = false,
                            thinkingSeconds = it.thinkingSeconds
                                .takeIf { s -> s > 0 } ?: elapsedSeconds(),
                        )
                    }

                    is GenerationStreamEvent.Structuring -> Unit // 纯 Markdown，无结构化阶段

                    is GenerationStreamEvent.Finished -> {
                        _uiState.update { s ->
                            s.copy(
                                isGenerating = false,
                                isThinking = false,
                                streamText = "",
                                cardText = ev.content.trim().ifBlank { s.cardText },
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }

                    is GenerationStreamEvent.Failure -> if (!stopRequested) {
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                isThinking = false,
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
                    it.copy(
                        isGenerating = false,
                        isThinking = false,
                        cardText = it.streamText.trim().ifBlank { it.cardText },
                        streamText = "",
                    )
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /** 把当前卡片存入纪念馆（item_type=memory_card，带 source 关联）。 */
    /**
     * 清掉生成错误弹窗（只关弹窗，不触发任何请求）。
     *
     * `MemoryCardScreen` 的错误弹窗「关闭」按钮绑到这里。不能绑
     * `startGeneration()`：生成一次是真调 LLM、要烧额度，用户想关弹窗却误触
     * 重跑，既花钱又可能把已有内容冲掉。想重生成请点页面上显式的按钮。
     *
     * 注意 `startGeneration()` 只清 `saveError` 不清 `error`，所以这个方法
     * 是必需的，否则弹窗会一直挂着。
     */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun saveToMuseum() {
        val state = _uiState.value
        if (state.cardText.isBlank() || state.isSavingToMuseum || state.savedToMuseum) return

        _uiState.update { it.copy(isSavingToMuseum = true, saveError = "") }
        viewModelScope.launch {
            museumRepository.createItem(
                MuseumDto.CreateMuseumItemRequest(
                    itemType = "memory_card",
                    title = state.itemTitle.ifBlank { "回忆卡片" },
                    story = state.cardText,
                    sourceId = state.targetId,
                    sourceType = state.targetType,
                )
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSavingToMuseum = false, savedToMuseum = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSavingToMuseum = false,
                            saveError = error.message ?: "存入失败，请稍后再试",
                        )
                    }
                },
            )
        }
    }

    private fun elapsedSeconds(): Int {
        if (startedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
    }

    override fun onCleared() {
        super.onCleared()
        cardJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }
}
