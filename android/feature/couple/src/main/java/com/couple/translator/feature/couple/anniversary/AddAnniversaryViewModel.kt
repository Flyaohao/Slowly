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
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.createAnniversary(
                AnniversaryDto.CreateAnniversaryRequest(
                    title = state.title,
                    anniversaryDate = state.date,
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
}
