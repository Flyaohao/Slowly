package com.couple.translator.feature.couple.museum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.feature.couple.data.repository.MuseumRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MuseumItemDetailUiState(
    val item: MuseumDto.MuseumItemResponse? = null,
    val isLoading: Boolean = false,
    val error: String = "",
    val deleted: Boolean = false,
)

@HiltViewModel
class MuseumItemDetailViewModel @Inject constructor(
    private val repository: MuseumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MuseumItemDetailUiState())
    val uiState: StateFlow<MuseumItemDetailUiState> = _uiState.asStateFlow()

    fun loadItem(itemId: Long) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getItemDetail(itemId).fold(
                onSuccess = { item ->
                    _uiState.update { it.copy(item = item, isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun togglePin(itemId: Long) {
        viewModelScope.launch {
            repository.togglePin(itemId).fold(
                onSuccess = { updated ->
                    _uiState.update { it.copy(item = updated) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "操作失败") }
                },
            )
        }
    }

    fun deleteItem(itemId: Long) {
        viewModelScope.launch {
            repository.deleteItem(itemId).fold(
                onSuccess = {
                    _uiState.update { it.copy(deleted = true) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "删除失败") }
                },
            )
        }
    }
}
