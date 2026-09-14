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

data class MuseumUiState(
    val items: List<MuseumDto.MuseumItemResponse> = emptyList(),
    val selectedType: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class MuseumViewModel @Inject constructor(
    private val repository: MuseumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MuseumUiState())
    val uiState: StateFlow<MuseumUiState> = _uiState.asStateFlow()

    init {
        loadItems()
    }

    fun refresh() {
        val type = _uiState.value.selectedType
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            repository.getItems(type).fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(items = it.items, isRefreshing = false)
                        }
                    } ?: run {
                        _uiState.update { it.copy(isRefreshing = false) }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isRefreshing = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadItems(type: String? = _uiState.value.selectedType) {
        _uiState.update { it.copy(isLoading = true, error = "", selectedType = type) }
        viewModelScope.launch {
            repository.getItems(type).fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(items = it.items, isLoading = false)
                        }
                    } ?: run {
                        _uiState.update { it.copy(isLoading = false) }
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

    fun selectType(type: String?) {
        loadItems(type)
    }
}
