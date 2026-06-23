package com.couple.translator.feature.couple.wishlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.WishlistDto
import com.couple.translator.feature.couple.data.repository.WishlistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WishlistUiState(
    val items: List<WishlistDto.WishlistResponse> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class WishlistViewModel @Inject constructor(
    private val repository: WishlistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WishlistUiState())
    val uiState: StateFlow<WishlistUiState> = _uiState.asStateFlow()

    init {
        loadWishlists()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            repository.getWishlists().fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(items = it.items, isRefreshing = false)
                        }
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

    fun loadWishlists() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getWishlists().fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(items = it.items, isLoading = false)
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

    fun completeWishlist(id: Long) {
        viewModelScope.launch {
            repository.completeWishlist(id).fold(
                onSuccess = { loadWishlists() },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "操作失败") }
                },
            )
        }
    }

    fun deleteWishlist(id: Long) {
        viewModelScope.launch {
            repository.deleteWishlist(id).fold(
                onSuccess = { loadWishlists() },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "删除失败") }
                },
            )
        }
    }
}
