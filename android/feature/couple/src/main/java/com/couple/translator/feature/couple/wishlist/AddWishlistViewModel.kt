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

data class AddWishlistUiState(
    val title: String = "",
    val description: String = "",
    val isLoading: Boolean = false,
    /** true = 编辑已有愿望（从列表点进来），false = 新建。 */
    val isEditing: Boolean = false,
    val error: String = "",
    val created: Boolean = false,
)

@HiltViewModel
class AddWishlistViewModel @Inject constructor(
    private val repository: WishlistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddWishlistUiState())
    val uiState: StateFlow<AddWishlistUiState> = _uiState.asStateFlow()

    /** 关闭错误弹窗（B-05：此前 Screen 传空的 onDismiss，弹窗无法关闭） */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    private var wishlistId: Long? = null

    /**
     * [id] 非空 = 编辑已有愿望。
     *
     * 没有「单条愿望详情」接口，所以从列表里回读——愿望量级很小，
     * 不值得为它单开一个端点。
     */
    fun start(id: Long?) {
        wishlistId = id
        if (id == null) return
        _uiState.update { it.copy(isLoading = true, isEditing = true, error = "") }
        viewModelScope.launch {
            repository.getWishlists().fold(
                onSuccess = { data ->
                    val item = data?.items?.firstOrNull { it.id == id }
                    if (item == null) {
                        _uiState.update { it.copy(isLoading = false, error = "愿望不存在") }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                title = item.title,
                                description = item.description.orEmpty(),
                            )
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

    /** 新建或更新——由 [start] 是否带 id 决定。 */
    fun save() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "请输入愿望") }
            return
        }
        val id = wishlistId
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val result = if (id == null) {
                repository.createWishlist(
                    WishlistDto.CreateWishlistRequest(
                        title = state.title,
                        description = state.description.ifBlank { null },
                    ),
                )
            } else {
                repository.updateWishlist(
                    id,
                    WishlistDto.UpdateWishlistRequest(
                        title = state.title,
                        description = state.description.ifBlank { null },
                    ),
                )
            }
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, created = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "保存失败")
                    }
                },
            )
        }
    }
}
