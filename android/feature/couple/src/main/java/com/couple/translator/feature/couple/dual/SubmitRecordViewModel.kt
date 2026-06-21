package com.couple.translator.feature.couple.dual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.repository.DualPerspectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubmitRecordUiState(
    val content: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
    val submitted: Boolean = false,
)

@HiltViewModel
class SubmitRecordViewModel @Inject constructor(
    private val repository: DualPerspectiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubmitRecordUiState())
    val uiState: StateFlow<SubmitRecordUiState> = _uiState.asStateFlow()

    fun updateContent(content: String) {
        _uiState.update { it.copy(content = content) }
    }

    fun submitRecord(eventId: Long) {
        if (_uiState.value.content.isBlank()) {
            _uiState.update { it.copy(error = "请输入你的视角") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.submitRecord(
                eventId,
                DualPerspectiveDto.SubmitRecordRequest(
                    content = _uiState.value.content,
                ),
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, submitted = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "提交失败")
                    }
                },
            )
        }
    }
}
