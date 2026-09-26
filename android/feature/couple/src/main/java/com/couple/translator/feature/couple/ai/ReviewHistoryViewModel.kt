package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewHistoryUiState(
    val isLoading: Boolean = true,
    val history: List<AiDto.ReviewHistoryItem> = emptyList(),
    val error: String = "",
)

/**
 * 复盘历史列表（§8.7）。
 *
 * 与 [ReviewViewModel] 分开的原因：它只有一个职责——把留档列出来。
 * 复盘页自己也要历史（用来回读最近一条），但那是它内部的回读路径，
 * 不该把「列表页状态」和「生成状态」混在同一个 UiState 里。
 */
@HiltViewModel
class ReviewHistoryViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewHistoryUiState())
    val uiState: StateFlow<ReviewHistoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            aiRepository.reviewHistory(page = 1, pageSize = 50).fold(
                onSuccess = { resp ->
                    _uiState.update {
                        it.copy(isLoading = false, history = resp?.items.orEmpty())
                    }
                },
                onFailure = { e ->
                    // 红线：加载失败不许静默——否则「读不到」会渲染成「没有复盘」
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            history = emptyList(),
                            error = e.message ?: "历史加载失败，稍后再试",
                        )
                    }
                },
            )
        }
    }
}
