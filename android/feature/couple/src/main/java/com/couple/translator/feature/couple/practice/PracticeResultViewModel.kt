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

data class PracticeResultUiState(
    val record: PracticeDto.PracticeRecordDetailResponse? = null,
    val isLoading: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class PracticeResultViewModel @Inject constructor(
    private val repository: PracticeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeResultUiState())
    val uiState: StateFlow<PracticeResultUiState> = _uiState.asStateFlow()

    fun loadResult(recordId: Long) {
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
}
