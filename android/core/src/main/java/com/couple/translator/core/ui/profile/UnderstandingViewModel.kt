package com.couple.translator.core.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「军师如何理解我们」页（契约 §3.2 画像三合一，零新后端）。
 * 组合现有端点：GET /profiles/me、/profiles/me/dimensions、/profiles/couple。
 * 单身/未做双人问卷时 coupleProfile 拉取失败 → null → 关系画像区块降级为提示文案。
 */
data class UnderstandingUiState(
    val isLoading: Boolean = true,
    val myProfile: ProfileDto.RelationshipProfileResponse? = null,
    val dimensions: List<ProfileDto.DimensionScoreResponse> = emptyList(),
    val coupleProfile: ProfileDto.CoupleProfileResponse? = null,
    val error: String = "",
)

@HiltViewModel
class UnderstandingViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnderstandingUiState())
    val uiState: StateFlow<UnderstandingUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val state = coroutineScope {
                val myProfileDeferred = async { profileRepository.getMyProfile().getOrNull() }
                val dimensionsDeferred = async { profileRepository.getMyDimensions().getOrNull() ?: emptyList() }
                val coupleDeferred = async { profileRepository.getCoupleProfile().getOrNull() }

                val myProfile = myProfileDeferred.await()
                val dimensions = dimensionsDeferred.await()
                val coupleProfile = coupleDeferred.await()

                UnderstandingUiState(
                    isLoading = false,
                    myProfile = myProfile,
                    dimensions = dimensions,
                    coupleProfile = coupleProfile,
                    // 我的画像与维度都拿不到才报错；关系画像缺失属正常（单身/未做双人问卷）
                    error = if (myProfile == null && dimensions.isEmpty()) {
                        "画像加载失败，稍后重试"
                    } else {
                        ""
                    },
                )
            }
            _uiState.update { state }
        }
    }
}
