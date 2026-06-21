package com.couple.translator.feature.couple.dual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.repository.DualPerspectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateDualEventUiState(
    val title: String = "",
    val eventTime: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
    val created: Boolean = false,
    val createdEventId: Long = 0,
)

@HiltViewModel
class CreateDualEventViewModel @Inject constructor(
    private val repository: DualPerspectiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateDualEventUiState())
    val uiState: StateFlow<CreateDualEventUiState> = _uiState.asStateFlow()

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateEventTime(time: String) {
        _uiState.update { it.copy(eventTime = time) }
    }

    fun createEvent() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "请输入事件标题") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.createEvent(
                DualPerspectiveDto.CreateEventRequest(
                    title = state.title,
                    eventTime = state.eventTime.ifBlank { null },
                ),
            ).fold(
                onSuccess = { event ->
                    event?.let {
                        _uiState.update { state ->
                            state.copy(isLoading = false, created = true, createdEventId = it.id)
                        }
                    }
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
