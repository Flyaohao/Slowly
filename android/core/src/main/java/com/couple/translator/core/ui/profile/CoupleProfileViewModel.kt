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

data class CoupleProfileUiState(
    val coupleProfile: ProfileDto.CoupleProfileResponse? = null,
    val isLoading: Boolean = true,
    val error: String = "",
) {
    val conflictPatternName: String
        get() = when (coupleProfile?.conflictPattern) {
            "pursue_withdraw" -> "追问-退缩循环"
            "anxious_escalation" -> "情绪升级循环"
            "mutual_withdrawal" -> "双向冷处理循环"
            "explain_misunderstand" -> "解释-不被理解循环"
            "suppress_explode" -> "压抑-爆发循环"
            else -> "暂无冲突模式"
        }

    val conflictPatternDescription: String
        get() = when (coupleProfile?.conflictPattern) {
            "pursue_withdraw" -> "一方倾向于追问和表达不满，另一方倾向于回避和沉默，形成拉锯。"
            "anxious_escalation" -> "双方都容易情绪激动，争吵容易升级。"
            "mutual_withdrawal" -> "双方都倾向于回避冲突，问题得不到解决。"
            "explain_misunderstand" -> "一方习惯解释，另一方觉得不被理解。"
            "suppress_explode" -> "一方长期压抑，积累到临界点后爆发。"
            else -> ""
        }
}

@HiltViewModel
class CoupleProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoupleProfileUiState())
    val uiState: StateFlow<CoupleProfileUiState> = _uiState.asStateFlow()

    init {
        loadCoupleProfile()
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadCoupleProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            profileRepository.getCoupleProfile().fold(
                onSuccess = { coupleProfile ->
                    _uiState.update {
                        it.copy(coupleProfile = coupleProfile, isLoading = false)
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
