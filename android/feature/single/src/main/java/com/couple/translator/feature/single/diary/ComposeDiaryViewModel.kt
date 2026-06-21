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

data class ComposeDiaryUiState(
    val title: String = "",
    val content: String = "",
    val mood: String? = null,
    val weather: String? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null,
)

val MOOD_OPTIONS = listOf("开心", "平静", "难过", "焦虑", "愤怒")

@HiltViewModel
class ComposeDiaryViewModel @Inject constructor(
    private val apiService: SingleApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeDiaryUiState())
    val uiState: StateFlow<ComposeDiaryUiState> = _uiState.asStateFlow()

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateContent(content: String) {
        _uiState.update { it.copy(content = content) }
    }

    fun updateMood(mood: String?) {
        _uiState.update { it.copy(mood = mood) }
    }

    fun save() {
        val state = _uiState.value
        if (state.title.isBlank() || state.content.isBlank()) {
            _uiState.update { it.copy(error = "标题和内容不能为空") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val response = apiService.createDiary(
                    DiaryDto.CreateDiaryRequest(
                        title = state.title,
                        content = state.content,
                        mood = state.mood,
                        weather = state.weather,
                    )
                )
                if (response.isSuccess) {
                    _uiState.update { it.copy(isSaving = false, isSaved = true) }
                } else {
                    _uiState.update { it.copy(isSaving = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
