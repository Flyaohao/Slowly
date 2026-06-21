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

data class DiaryListUiState(
    val isLoading: Boolean = true,
    val diaries: List<DiaryDto.DiaryResponse> = emptyList(),
    val filterType: String = "all",
    val error: String? = null,
)

@HiltViewModel
class DiaryListViewModel @Inject constructor(
    private val apiService: SingleApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiaryListUiState())
    val uiState: StateFlow<DiaryListUiState> = _uiState.asStateFlow()

    init {
        loadDiaries()
    }

    fun loadDiaries(filterType: String = "all") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, filterType = filterType) }
            try {
                val response = apiService.getDiaries(filterType = filterType)
                if (response.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            diaries = response.data ?: emptyList(),
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun deleteDiary(id: Long) {
        viewModelScope.launch {
            try {
                apiService.deleteDiary(id)
                loadDiaries(_uiState.value.filterType)
            } catch (_: Exception) {}
        }
    }

    fun toggleFavorite(id: Long) {
        viewModelScope.launch {
            try {
                apiService.toggleDiaryFavorite(id)
                loadDiaries(_uiState.value.filterType)
            } catch (_: Exception) {}
        }
    }
}
