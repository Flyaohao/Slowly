package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MemoryDto
import com.couple.translator.feature.couple.data.repository.MemoryRepository
import com.couple.translator.core.data.repository.UiNoticeStore
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

data class MemoryUiState(
    val memories: List<MemoryDto.MemoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
    // ---- P-C3 §4.3：筛选（null = 全部，改动即重新拉取，筛选在服务端做）----
    /** 来源：letter/diary/dual/anniversary/questionnaire/chat_summary */
    val sourceFilter: String? = null,
    /** 时间窗天数：null = 全部，7 / 30 = 近 N 天 */
    val daysFilter: Int? = null,
    /** 重要度：null = 全部，2 = 只看标星 */
    val importanceFilter: Int? = null,
    // ---- 顶部提示条「不再显示」（跨启动记住；默认 true=先不显示，等 store 确认未关再亮，
    //      避免「用户已关但每次进来还闪一下」）----
    val bannerDismissed: Boolean = true,
)

sealed class MemoryUiEvent {
    data class ShowError(val message: String) : MemoryUiEvent()
    data object DeleteSuccess : MemoryUiEvent()
}

/** 记忆页顶部提示条的稳定 id（UiNoticeStore 持久化用） */
private const val MEMORY_BANNER_ID = "memory_privacy_notice"

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val uiNoticeStore: UiNoticeStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MemoryUiEvent>()
    val event: SharedFlow<MemoryUiEvent> = _event.asSharedFlow()

    init {
        loadMemories()
        // 顶部提示条关闭状态（跨启动记住）
        viewModelScope.launch {
            uiNoticeStore.dismissed(MEMORY_BANNER_ID).collect { dismissed ->
                _uiState.update { it.copy(bannerDismissed = dismissed) }
            }
        }
    }

    /** 用户点提示条 ×：立即收起并持久化，之后不再出现 */
    fun dismissBanner() {
        _uiState.update { it.copy(bannerDismissed = true) }
        viewModelScope.launch {
            uiNoticeStore.setDismissed(MEMORY_BANNER_ID, true)
        }
    }

    fun refresh() = fetch(isRefresh = true)

    fun loadMemories() = fetch(isRefresh = false)

    /** P-C3 §4.3：筛选在服务端做（since 用 occurred_at 回退 created_at 的口径），改筛选即重拉。 */
    private fun fetch(isRefresh: Boolean) {
        _uiState.update {
            if (isRefresh) it.copy(isRefreshing = true, error = "")
            else it.copy(isLoading = true, error = "")
        }
        val state = _uiState.value
        val since = state.daysFilter?.let { days ->
            java.time.LocalDateTime.now().minusDays(days.toLong()).toString()
        }
        viewModelScope.launch {
            memoryRepository.getMemories(
                source = state.sourceFilter,
                importance = state.importanceFilter,
                since = since,
            ).fold(
                onSuccess = { memories ->
                    _uiState.update {
                        it.copy(
                            memories = memories,
                            isLoading = false,
                            isRefreshing = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = error.message ?: "加载失败",
                        )
                    }
                },
            )
        }
    }

    fun setSourceFilter(source: String?) {
        _uiState.update { it.copy(sourceFilter = source) }
        loadMemories()
    }

    fun setDaysFilter(days: Int?) {
        _uiState.update { it.copy(daysFilter = days) }
        loadMemories()
    }

    fun setImportanceFilter(importance: Int?) {
        _uiState.update { it.copy(importanceFilter = importance) }
        loadMemories()
    }

    /** P-C3 §4.2：★/☆ 切换——写库成功后才改本地（失败不产生假状态）。 */
    fun toggleImportance(memoryId: Long) {
        val current = _uiState.value.memories.firstOrNull { it.id == memoryId } ?: return
        val next = if (current.importance == 2) 0 else 2
        viewModelScope.launch {
            memoryRepository.updateImportance(memoryId, next).fold(
                onSuccess = {
                    _uiState.update { state ->
                        val updated = state.memories.map {
                            if (it.id == memoryId) it.copy(importance = next) else it
                        }
                        state.copy(
                            // 正在「只看标星」时取消标星 → 该行已不符合筛选，移出列表
                            memories = if (state.importanceFilter == 2 && next == 0) {
                                updated.filter { it.id != memoryId }
                            } else {
                                updated
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _event.emit(MemoryUiEvent.ShowError(error.message ?: "标星失败"))
                },
            )
        }
    }

    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memoryId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(memories = state.memories.filter { it.id != memoryId })
                    }
                    _event.emit(MemoryUiEvent.DeleteSuccess)
                },
                onFailure = { error ->
                    _event.emit(MemoryUiEvent.ShowError(error.message ?: "删除失败"))
                },
            )
        }
    }

    fun updateVisibility(memoryId: Long, visibility: String) {
        viewModelScope.launch {
            memoryRepository.updateVisibility(memoryId, visibility).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            memories = state.memories.map {
                                if (it.id == memoryId) it.copy(visibility = visibility) else it
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _event.emit(MemoryUiEvent.ShowError(error.message ?: "更新失败"))
                },
            )
        }
    }
}
