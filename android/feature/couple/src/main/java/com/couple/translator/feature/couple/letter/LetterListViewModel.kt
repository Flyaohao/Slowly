package com.couple.translator.feature.couple.letter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.repository.LetterRepository
import com.couple.translator.core.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LetterListUiState(
    val letters: List<LetterDto.LetterResponse> = emptyList(),
    val selectedTab: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val isDeleting: Boolean = false,
    val error: String = "",
    /**
     * 当前登录用户 id（2026-09-29 新增）。
     * 「全部」tab 后端返回的是 sender OR receiver 的混排列表，前端必须靠它
     * 区分「我写的」与「TA 写给我的」——否则自己发的信会被误当成来信。
     * null = 还没取到（此时列表不显示方向标识，降级而非猜错）。
     */
    val currentUserId: Long? = null,
)

@HiltViewModel
class LetterListViewModel @Inject constructor(
    private val letterRepository: LetterRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LetterListUiState())
    val uiState: StateFlow<LetterListUiState> = _uiState.asStateFlow()

    init {
        // 拉一次当前用户 id（与首页同法）；失败保持 null，UI 降级为不显示方向标识
        viewModelScope.launch {
            val me = runCatching { userRepository.getCurrentUser().getOrNull()?.userId }.getOrNull()
            if (me != null) {
                _uiState.update { it.copy(currentUserId = me) }
            }
        }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun refresh() {
        // 与界面可见 tab 同源：未来/私密已冻结移除，索引对不上的刷新会取错数据
        val tabName = visibleCoupleTabs().getOrElse(uiState.value.selectedTab) { "全部" }
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            val result = when (tabName) {
                "全部" -> letterRepository.getLetters()
                "收到" -> letterRepository.getInbox()
                "发出" -> letterRepository.getLetters(direction = "sent")
                "草稿" -> letterRepository.getDrafts()
                "冷静" -> letterRepository.getLetters(type = "calm")
                "未说出口" -> letterRepository.getLetters(type = "unsaid")
                "收藏" -> letterRepository.getLetters().map { resp ->
                    resp?.copy(items = resp.items.filter { it.isFavorite })
                }
                "本周" -> letterRepository.getLetters().map { resp ->
                    resp?.copy(items = resp.items.filter { isThisWeek(it.createdAt) })
                }
                "本月" -> letterRepository.getLetters().map { resp ->
                    resp?.copy(items = resp.items.filter { isThisMonth(it.createdAt) })
                }
                else -> letterRepository.getLetters()
            }
            result.fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(letters = it.items, isRefreshing = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isRefreshing = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun loadLetters(tabName: String = "全部") {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val result = when (tabName) {
                // Couple mode tabs（未来/私密已随可见 tab 一并冻结移除）
                "全部" -> letterRepository.getLetters()
                "收到" -> letterRepository.getInbox()
                "发出" -> letterRepository.getLetters(direction = "sent")
                "草稿" -> letterRepository.getDrafts()
                "冷静" -> letterRepository.getLetters(type = "calm")
                "未说出口" -> letterRepository.getLetters(type = "unsaid")
                // Diary mode tabs (client-side filtering)
                "收藏" -> letterRepository.getLetters().map { resp ->
                    resp?.copy(items = resp.items.filter { it.isFavorite })
                }
                "本周" -> letterRepository.getLetters().map { resp ->
                    resp?.copy(items = resp.items.filter { isThisWeek(it.createdAt) })
                }
                "本月" -> letterRepository.getLetters().map { resp ->
                    resp?.copy(items = resp.items.filter { isThisMonth(it.createdAt) })
                }
                else -> letterRepository.getLetters()
            }
            result.fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(letters = it.items, isLoading = false, isSelectionMode = false, selectedIds = emptySet())
                        }
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

    // === Selection mode ===

    fun toggleSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = !it.isSelectionMode,
                selectedIds = emptySet(),
            )
        }
    }

    fun toggleSelect(letterId: Long) {
        _uiState.update { state ->
            val newSelected = if (state.selectedIds.contains(letterId)) {
                state.selectedIds - letterId
            } else {
                state.selectedIds + letterId
            }
            state.copy(selectedIds = newSelected)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedIds = state.letters.map { it.id }.toSet())
        }
    }

    fun deselectAll() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun batchDelete() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return

        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            letterRepository.batchDeleteLetters(ids).fold(
                onSuccess = { count ->
                    _uiState.update { state ->
                        state.copy(
                            letters = state.letters.filter { it.id !in state.selectedIds },
                            selectedIds = emptySet(),
                            isSelectionMode = false,
                            isDeleting = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isDeleting = false, error = error.message ?: "删除失败")
                    }
                },
            )
        }
    }

    private fun isThisWeek(isoString: String?): Boolean {
        if (isoString == null) return false
        return try {
            val date = parseToLocalDate(isoString)
            val today = LocalDate.now()
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekEnd = weekStart.plusDays(6)
            !date.isBefore(weekStart) && !date.isAfter(weekEnd)
        } catch (_: Exception) {
            false
        }
    }

    private fun isThisMonth(isoString: String?): Boolean {
        if (isoString == null) return false
        return try {
            val date = parseToLocalDate(isoString)
            val today = LocalDate.now()
            date.year == today.year && date.month == today.month
        } catch (_: Exception) {
            false
        }
    }

    private fun parseToLocalDate(isoString: String): LocalDate {
        return try {
            OffsetDateTime.parse(isoString).toLocalDate()
        } catch (_: Exception) {
            LocalDateTime.parse(isoString).toLocalDate()
        }
    }
}
