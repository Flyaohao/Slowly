package com.couple.translator.feature.couple.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.feature.couple.data.repository.PracticeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PracticeDetailUiState(
    val record: PracticeDto.PracticeRecordDetailResponse? = null,
    val currentStep: Int = 0,
    val content: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
    val submitted: Boolean = false,
)

@HiltViewModel
class PracticeDetailViewModel @Inject constructor(
    private val repository: PracticeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeDetailUiState())
    val uiState: StateFlow<PracticeDetailUiState> = _uiState.asStateFlow()

    /** 关闭错误弹窗（B-05：此前 Screen 传空的 onDismiss，弹窗无法关闭） */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadRecord(recordId: Long) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getRecordDetail(recordId).fold(
                onSuccess = { record ->
                    _uiState.update { it.copy(record = record, isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun updateContent(content: String) {
        _uiState.update { it.copy(content = content) }
    }

    fun nextStep() {
        _uiState.update { it.copy(currentStep = it.currentStep + 1, content = "") }
    }

    fun submitPractice(recordId: Long) {
        if (_uiState.value.content.isBlank()) {
            _uiState.update { it.copy(error = "请输入内容") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.submitPractice(
                recordId,
                PracticeDto.SubmitPracticeRequest(content = _uiState.value.content),
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
