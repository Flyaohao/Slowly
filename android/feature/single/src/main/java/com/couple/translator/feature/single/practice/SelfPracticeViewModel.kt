package com.couple.translator.feature.single.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.single.data.model.SelfPracticeDto
import com.couple.translator.feature.single.network.SingleApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SelfPracticeUiState(
    val isLoading: Boolean = true,
    val practices: List<SelfPracticeDto.SelfPracticeResponse> = emptyList(),
    val currentPractice: SelfPracticeDto.SelfPracticeResponse? = null,
    val currentRecord: SelfPracticeDto.SelfPracticeRecordResponse? = null,
    val content: String = "",
    val reflection: String = "",
    val isSubmitting: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SelfPracticeViewModel @Inject constructor(
    private val apiService: SingleApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SelfPracticeUiState())
    val uiState: StateFlow<SelfPracticeUiState> = _uiState.asStateFlow()

    init {
        loadPractices()
    }

    fun loadPractices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.getSelfPractices()
                if (response.isSuccess) {
                    _uiState.update {
                        it.copy(isLoading = false, practices = response.data ?: emptyList())
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun startPractice(practiceId: Long) {
        viewModelScope.launch {
            try {
                val response = apiService.startSelfPractice(practiceId)
                if (response.isSuccess && response.data != null) {
                    val practice = _uiState.value.practices.find { it.id == practiceId }
                    _uiState.update {
                        it.copy(
                            currentPractice = practice,
                            currentRecord = response.data,
                            content = "",
                            reflection = "",
                            isCompleted = false,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateContent(content: String) {
        _uiState.update { it.copy(content = content) }
    }

    fun updateReflection(reflection: String) {
        _uiState.update { it.copy(reflection = reflection) }
    }

    fun submitPractice() {
        val record = _uiState.value.currentRecord ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            try {
                val response = apiService.submitSelfPractice(
                    record.id,
                    SelfPracticeDto.SubmitSelfPracticeRequest(
                        content = _uiState.value.content,
                        reflection = _uiState.value.reflection,
                    )
                )
                if (response.isSuccess) {
                    _uiState.update { it.copy(isSubmitting = false, isCompleted = true) }
                } else {
                    _uiState.update { it.copy(isSubmitting = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    fun resetToPracticeList() {
        _uiState.update {
            it.copy(
                currentPractice = null,
                currentRecord = null,
                content = "",
                reflection = "",
                isCompleted = false,
            )
        }
    }
}
