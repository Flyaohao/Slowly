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

data class AnniversaryListUiState(
    val anniversaries: List<AnniversaryDto.AnniversaryResponse> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class AnniversaryListViewModel @Inject constructor(
    private val repository: AnniversaryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnniversaryListUiState())
    val uiState: StateFlow<AnniversaryListUiState> = _uiState.asStateFlow()

    init {
        loadAnniversaries()
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadAnniversaries() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getAnniversaries().fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(anniversaries = it.items, isLoading = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun deleteAnniversary(id: Long) {
        viewModelScope.launch {
            repository.deleteAnniversary(id).fold(
                onSuccess = { loadAnniversaries() },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "删除失败") }
                },
            )
        }
    }
}
