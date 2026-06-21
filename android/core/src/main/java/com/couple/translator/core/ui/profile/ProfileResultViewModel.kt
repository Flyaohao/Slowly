package com.couple.translator.core.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileResultUiState(
    val profile: ProfileDto.RelationshipProfileResponse? = null,
    val dimensions: List<ProfileDto.DimensionScoreResponse> = emptyList(),
    val isLoading: Boolean = true,
    val error: String = "",
) {
    val profileTypeName: String
        get() = when (profile?.profileType) {
            "secure" -> "安全型依恋"
            "anxious" -> "焦虑依恋型"
            "dismissive" -> "疏离回避型"
            "fearful" -> "恐惧回避型"
            else -> "未知"
        }

    val profileTypeDescription: String
        get() = when (profile?.profileType) {
            "secure" -> "你在关系中感到安全和自在，能够平衡独立与亲密。"
            "anxious" -> "你渴望亲密，有时会担心伴侣不够在乎你。"
            "dismissive" -> "你重视独立自主，有时会回避过深的情感交流。"
            "fearful" -> "你既渴望亲密又害怕受伤，在关系中常感到矛盾。"
            else -> ""
        }
}

@HiltViewModel
class ProfileResultViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileResultUiState())
    val uiState: StateFlow<ProfileResultUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }

            val profileResult = profileRepository.getMyProfile()
            val dimensionsResult = profileRepository.getMyDimensions()

            profileResult.fold(
                onSuccess = { profile ->
                    val dimensions = dimensionsResult.getOrNull() ?: emptyList()
                    _uiState.update {
                        it.copy(
                            profile = profile,
                            dimensions = dimensions,
                            isLoading = false,
                        )
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
}
