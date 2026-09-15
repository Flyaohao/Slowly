package com.couple.translator.feature.couple.couplebind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.feature.couple.data.repository.CoupleRepository
import com.couple.translator.feature.couple.data.repository.CoupleStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CoupleInfoUiState(
    val coupleInfo: CoupleDto.CoupleRelationResponse? = null,
    val isLoading: Boolean = true,
    val error: String = "",
    val showUnbindDialog: Boolean = false,
    val unbindMessage: String = "",
)

sealed class CoupleInfoEvent {
    data object NavigateToMain : CoupleInfoEvent()
}

@HiltViewModel
class CoupleInfoViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
    private val coupleStateManager: CoupleStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoupleInfoUiState())
    val uiState: StateFlow<CoupleInfoUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<CoupleInfoEvent>()
    val event: SharedFlow<CoupleInfoEvent> = _event.asSharedFlow()

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

    fun navigateToMain() {
        viewModelScope.launch {
            _event.emit(CoupleInfoEvent.NavigateToMain)
        }
    }

    fun showUnbindDialog() {
        _uiState.update { it.copy(showUnbindDialog = true) }
    }

    fun dismissUnbindDialog() {
        _uiState.update { it.copy(showUnbindDialog = false) }
    }

    fun requestUnbind() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "", showUnbindDialog = false) }
            coupleRepository.requestUnbind().fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, unbindMessage = "解绑申请已发送，等待对方确认")
                    }
                    coupleStateManager.refresh()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "解绑申请失败")
                    }
                },
            )
        }
    }

    fun cancelUnbind() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            coupleRepository.cancelUnbind().fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, unbindMessage = "已取消解绑申请")
                    }
                    coupleStateManager.refresh()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "取消解绑失败")
                    }
                },
            )
        }
    }

    /**
     * 对方在冷静期满后确认解绑。
     * 注意：冷静期（72 小时）未满时后端会返回 code=30004「冷静期未满，无法确认解绑」。
     */
    fun confirmUnbind() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            coupleRepository.confirmUnbind().fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, unbindMessage = "解绑完成")
                    }
                    coupleStateManager.refresh()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "确认解绑失败")
                    }
                },
            )
        }
    }
}
