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
    val isLoading: Boolean = false,
    val isEditMode: Boolean = false,
    val error: String? = null,
)

val MOOD_OPTIONS = listOf("开心", "平静", "难过", "焦虑", "愤怒")
val WEATHER_OPTIONS = listOf("晴天", "多云", "阴天", "雨天", "雪天", "大风")

@HiltViewModel
class ComposeDiaryViewModel @Inject constructor(
    private val apiService: SingleApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeDiaryUiState())
    val uiState: StateFlow<ComposeDiaryUiState> = _uiState.asStateFlow()

    private var editingDiaryId: Long? = null

    /**
     * 加载已有日记进入编辑模式
     */
    fun loadForEdit(diaryId: Long) {
        if (editingDiaryId != null) return // 避免重复加载
        editingDiaryId = diaryId
        _uiState.update { it.copy(isLoading = true, isEditMode = true) }
        viewModelScope.launch {
            try {
                val response = apiService.getDiary(diaryId)
                val diary = response.data
                if (response.isSuccess && diary != null) {
                    _uiState.update {
                        it.copy(
                            title = diary.title,
                            content = diary.content,
                            mood = diary.mood,
                            weather = diary.weather,
                            isLoading = false,
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

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateContent(content: String) {
        _uiState.update { it.copy(content = content) }
    }

    fun updateMood(mood: String?) {
        _uiState.update { it.copy(mood = mood) }
    }

    fun updateWeather(weather: String?) {
        _uiState.update { it.copy(weather = weather) }
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
                val response = if (state.isEditMode && editingDiaryId != null) {
                    // 编辑模式：调用 PUT 更新
                    apiService.updateDiary(
                        editingDiaryId!!,
                        DiaryDto.UpdateDiaryRequest(
                            title = state.title,
                            content = state.content,
                            mood = state.mood,
                            weather = state.weather,
                        )
                    )
                } else {
                    // 新建模式
                    apiService.createDiary(
                        DiaryDto.CreateDiaryRequest(
                            title = state.title,
                            content = state.content,
                            mood = state.mood,
                            weather = state.weather,
                        )
                    )
                }
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
