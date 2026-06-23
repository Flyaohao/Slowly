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
    val isRefreshing: Boolean = false,
    val diaries: List<DiaryDto.DiaryResponse> = emptyList(),
    val filterType: String = "all",
    val error: String? = null,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val isDeleting: Boolean = false,
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

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                val response = apiService.getDiaries(filterType = _uiState.value.filterType)
                if (response.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            diaries = response.data?.items ?: emptyList(),
                        )
                    }
                } else {
                    _uiState.update { it.copy(isRefreshing = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
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
                            diaries = response.data?.items ?: emptyList(),
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
                val response = apiService.deleteDiary(id)
                if (response.isSuccess) {
                    loadDiaries(_uiState.value.filterType)
                } else {
                    _uiState.update { it.copy(error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "删除失败") }
            }
        }
    }

    fun toggleFavorite(id: Long) {
        viewModelScope.launch {
            try {
                val response = apiService.toggleDiaryFavorite(id)
                if (response.isSuccess) {
                    // 乐观更新：直接更新本地列表中的收藏状态
                    _uiState.update { state ->
                        val updatedDiaries = state.diaries.map { diary ->
                            if (diary.id == id) diary.copy(isFavorite = !diary.isFavorite) else diary
                        }
                        state.copy(diaries = updatedDiaries)
                    }
                } else {
                    _uiState.update { it.copy(error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "操作失败") }
            }
        }
    }

    fun toggleSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = !it.isSelectionMode,
                selectedIds = if (it.isSelectionMode) emptySet() else it.selectedIds,
            )
        }
    }

    fun toggleSelect(id: Long) {
        _uiState.update {
            val newSelected = if (id in it.selectedIds) {
                it.selectedIds - id
            } else {
                it.selectedIds + id
            }
            it.copy(selectedIds = newSelected)
        }
    }

    fun selectAll() {
        _uiState.update {
            it.copy(selectedIds = it.diaries.map { diary -> diary.id }.toSet())
        }
    }

    fun batchDelete() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                val response = apiService.batchDeleteDiaries(DiaryDto.BatchDeleteRequest(ids))
                if (response.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isSelectionMode = false,
                            selectedIds = emptySet(),
                            isDeleting = false,
                        )
                    }
                    loadDiaries(_uiState.value.filterType)
                } else {
                    _uiState.update { it.copy(isDeleting = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isDeleting = false, error = e.message) }
            }
        }
    }
}
