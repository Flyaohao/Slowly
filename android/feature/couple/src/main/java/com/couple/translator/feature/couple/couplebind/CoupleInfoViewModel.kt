package com.couple.translator.feature.couple.couplebind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.feature.couple.data.repository.CoupleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CoupleInfoUiState(
    val coupleInfo: CoupleDto.CoupleRelationResponse? = null,
    val isLoading: Boolean = true,
    val error: String = "",
)

@HiltViewModel
class CoupleInfoViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoupleInfoUiState())
    val uiState: StateFlow<CoupleInfoUiState> = _uiState.asStateFlow()

    init {
        loadCoupleInfo()
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadCoupleInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            coupleRepository.getCoupleInfo().fold(
                onSuccess = { info ->
                    _uiState.update {
                        it.copy(coupleInfo = info, isLoading = false)
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
