package com.couple.translator.feature.single

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.single.data.model.DiaryDto
import com.couple.translator.core.network.SharedApiService
import com.couple.translator.feature.single.network.SingleApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SingleHomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val recentDiaries: List<DiaryDto.DiaryResponse> = emptyList(),
    val hasProfile: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SingleHomeViewModel @Inject constructor(
    private val sharedApiService: SharedApiService,
    private val singleApiService: SingleApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SingleHomeUiState())
    val uiState: StateFlow<SingleHomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val homeResponse = sharedApiService.getHomeData()
                if (homeResponse.isSuccess && homeResponse.data != null) {
                    val data = homeResponse.data!!
                    // 单身模式用顶层字段，情侣模式用 relation 内字段
                    val nickname = data.userNickname ?: data.relation?.userNickname
                    val avatarUrl = data.userAvatarUrl ?: data.relation?.userAvatarUrl
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            nickname = nickname,
                            avatarUrl = avatarUrl,
                            hasProfile = data.hasProfile,
                        )
                    }
                    // 加载最近日记
                    loadDiaries()
                } else {
                    // 单身模式首页可能返回不同结构，尝试加载日记
                    loadDiaries()
                }
            } catch (e: Exception) {
                loadDiaries()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                val homeResponse = sharedApiService.getHomeData()
                if (homeResponse.isSuccess && homeResponse.data != null) {
                    val data = homeResponse.data!!
                    _uiState.update {
                        it.copy(
                            nickname = data.relation?.userNickname,
                            avatarUrl = data.relation?.userAvatarUrl,
                            hasProfile = true,
                        )
                    }
                }
                loadDiariesForRefresh()
            } catch (e: Exception) {
                loadDiariesForRefresh()
            }
        }
    }

    private suspend fun loadDiariesForRefresh() {
        try {
            val diaryResponse = singleApiService.getDiaries(limit = 3)
            if (diaryResponse.isSuccess) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        recentDiaries = diaryResponse.data?.items ?: emptyList(),
                    )
                }
            } else {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isRefreshing = false, error = e.message) }
        }
    }

    private suspend fun loadDiaries() {
        try {
            val diaryResponse = singleApiService.getDiaries(limit = 3)
            if (diaryResponse.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        recentDiaries = diaryResponse.data?.items ?: emptyList(),
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }
}
