package com.couple.translator.feature.couple.anniversary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.repository.AnniversaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddAnniversaryUiState(
    val title: String = "",
    val date: String = "",
    /**
     * 整改 §8.8：是否每年重复。默认 true——与存量「纪念日按年滚动」的语义一致，
     * 也让最常见的「在一起纪念日」不用改设置。
     */
    val repeatAnnually: Boolean = true,
    val description: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
    val created: Boolean = false,
)

@HiltViewModel
class AddAnniversaryViewModel @Inject constructor(
    private val repository: AnniversaryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddAnniversaryUiState())
    val uiState: StateFlow<AddAnniversaryUiState> = _uiState.asStateFlow()

    /** 关闭错误弹窗（B-05：此前 Screen 传空的 onDismiss，弹窗无法关闭） */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateDate(date: String) {
        _uiState.update { it.copy(date = date) }
    }

    fun updateRepeatAnnually(repeat: Boolean) {
        _uiState.update { it.copy(repeatAnnually = repeat) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    fun createAnniversary() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "请输入标题") }
            return
        }
        if (state.date.isBlank()) {
            _uiState.update { it.copy(error = "请输入日期") }
            return
        }
        if (!isValidDate(state.date)) {
            // 契约 §8.8：日期语义是本轮整改的重点，不能把「2026-13-45」这种
            // 交给服务端去拒——用户在这里就要知道格式错了。
            _uiState.update { it.copy(error = "日期格式应为 YYYY-MM-DD，例如 2024-11-20") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.createAnniversary(
                AnniversaryDto.CreateAnniversaryRequest(
                    title = state.title,
                    anniversaryDate = state.date,
                    repeatAnnually = state.repeatAnnually,
                    description = state.description.ifBlank { null },
                ),
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, created = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "创建失败")
                    }
                },
            )
        }
    }

    private fun isValidDate(text: String): Boolean = runCatching {
        java.time.LocalDate.parse(text.trim())
    }.isSuccess
}
