package com.couple.translator.feature.couple.avatar

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class AvatarUiState(
    val faceShape: Int = 0,
    val eyeStyle: Int = 0,
    val mouthStyle: Int = 0,
    val blushStyle: Int = 0,
    val toneStyle: String = "温柔",
    val aiName: String = "翻译官",
)

private val tones = listOf("温柔", "冷静", "直接", "可爱", "成熟")

@HiltViewModel
class AvatarCustomizeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(AvatarUiState())
    val uiState: StateFlow<AvatarUiState> = _uiState.asStateFlow()

    fun updateFaceShape(index: Int) {
        _uiState.update { it.copy(faceShape = index) }
    }

    fun updateEyeStyle(index: Int) {
        _uiState.update { it.copy(eyeStyle = index) }
    }

    fun updateMouthStyle(index: Int) {
        _uiState.update { it.copy(mouthStyle = index) }
    }

    fun updateBlushStyle(index: Int) {
        _uiState.update { it.copy(blushStyle = index) }
    }

    fun cycleTone() {
        _uiState.update { state ->
            val currentIndex = tones.indexOf(state.toneStyle)
            val nextIndex = (currentIndex + 1) % tones.size
            state.copy(toneStyle = tones[nextIndex])
        }
    }
}
