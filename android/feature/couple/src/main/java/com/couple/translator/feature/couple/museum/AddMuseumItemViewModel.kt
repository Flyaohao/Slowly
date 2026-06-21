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

data class AddMuseumItemUiState(
    val title: String = "",
    val story: String = "",
    val itemType: String = "word",
    val isLoading: Boolean = false,
    val error: String = "",
    val created: Boolean = false,
)

@HiltViewModel
class AddMuseumItemViewModel @Inject constructor(
    private val repository: MuseumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddMuseumItemUiState())
    val uiState: StateFlow<AddMuseumItemUiState> = _uiState.asStateFlow()

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateStory(story: String) {
        _uiState.update { it.copy(story = story) }
    }

    fun updateItemType(type: String) {
        _uiState.update { it.copy(itemType = type) }
    }

    fun createItem() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "请输入标题") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.createItem(
                MuseumDto.CreateMuseumItemRequest(
                    itemType = state.itemType,
                    title = state.title,
                    story = state.story.ifBlank { null },
                ),
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, created = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "创建失败")
                    }
                },
            )
        }
    }
}
