package com.couple.translator.feature.couple.avatar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.AvatarDto
import com.couple.translator.feature.couple.data.repository.AvatarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AvatarUiState(
    val faceShape: Int = 0,
    val eyeStyle: Int = 0,
    val mouthStyle: Int = 0,
    val blushStyle: Int = 0,
    val toneIndex: Int = 0,
    val aiName: String = "翻译官",
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String = "",
)

internal val toneValues = listOf("gentle", "calm", "direct", "cute", "mature")
internal val toneLabels = listOf("温柔", "冷静", "直接", "可爱", "成熟")

/** 后端 voice_style 值 → UI 文案下标；未知值回退到 0（温柔）。 */
private fun toneIndexOf(value: String?): Int = toneValues.indexOf(value).coerceAtLeast(0)

@HiltViewModel
class AvatarCustomizeViewModel @Inject constructor(
    private val repository: AvatarRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AvatarUiState())
    val uiState: StateFlow<AvatarUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            repository.getMyAvatar().fold(
                onSuccess = { avatar ->
                    if (avatar != null) {
                        val face = avatar.faceConfig.orEmpty()
                        _uiState.update {
                            it.copy(
                                faceShape = (face["face_shape"] ?: 0).coerceIn(0, 2),
                                eyeStyle = (face["eye_style"] ?: 0).coerceIn(0, 2),
                                mouthStyle = (face["mouth_style"] ?: 0).coerceIn(0, 2),
                                blushStyle = (face["blush_style"] ?: 0).coerceIn(0, 2),
                                toneIndex = toneIndexOf(avatar.voiceStyle),
                                aiName = avatar.name,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "加载失败") }
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun clearSaved() {
        _uiState.update { it.copy(saved = false) }
    }

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
            val next = (state.toneIndex + 1) % toneValues.size
            state.copy(toneIndex = next)
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(aiName = name) }
    }

    /** 保存：捏脸 → face_config，语气 → voice_style，名字 → name。 */
    fun save() {
        val state = _uiState.value
        _uiState.update { it.copy(isSaving = true, error = "") }
        viewModelScope.launch {
            repository.updateAvatar(
                AvatarDto.AvatarUpdateRequest(
                    name = state.aiName.ifBlank { "翻译官" },
                    faceConfig = mapOf(
                        "face_shape" to state.faceShape,
                        "eye_style" to state.eyeStyle,
                        "mouth_style" to state.mouthStyle,
                        "blush_style" to state.blushStyle,
                    ),
                ),
            ).fold(
                onSuccess = {
                    repository.setVoiceStyle(toneValues[state.toneIndex]).fold(
                        onSuccess = {
                            _uiState.update { it.copy(isSaving = false, saved = true) }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(isSaving = false, error = error.message ?: "保存失败")
                            }
                        },
                    )
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSaving = false, error = error.message ?: "保存失败")
                    }
                },
            )
        }
    }
}
