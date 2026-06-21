package com.couple.translator.feature.single.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.single.data.model.DiaryDto
import com.couple.translator.feature.single.network.SingleApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiaryDetailUiState(
    val isLoading: Boolean = true,
    val diary: DiaryDto.DiaryResponse? = null,
    val isDeleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DiaryDetailViewModel @Inject constructor(
    private val apiService: SingleApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiaryDetailUiState())
    val uiState: StateFlow<DiaryDetailUiState> = _uiState.asStateFlow()

    fun loadDiary(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.getDiary(id)
                if (response.isSuccess && response.data != null) {
                    _uiState.update { it.copy(isLoading = false, diary = response.data) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun toggleFavorite() {
        val diary = _uiState.value.diary ?: return
        viewModelScope.launch {
            try {
                val response = apiService.toggleDiaryFavorite(diary.id)
                if (response.isSuccess && response.data != null) {
                    _uiState.update { it.copy(diary = response.data) }
                }
            } catch (_: Exception) {}
        }
    }

    fun deleteDiary() {
        val diary = _uiState.value.diary ?: return
        viewModelScope.launch {
            try {
                val response = apiService.deleteDiary(diary.id)
                if (response.isSuccess) {
                    _uiState.update { it.copy(isDeleted = true) }
                }
            } catch (_: Exception) {}
        }
    }
}
