package com.couple.translator.feature.couple.couplebind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class CoupleBindUiState(
    val inviteCode: String = "",
    val inputCode: String = "",
    val generatedCode: String = "",
    val codeExpiresAt: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
)

sealed class CoupleBindUiEvent {
    data object BindSuccess : CoupleBindUiEvent()
    data class ShowError(val message: String) : CoupleBindUiEvent()
}

@HiltViewModel
class CoupleBindViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
    private val coupleStateManager: CoupleStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoupleBindUiState())
    val uiState: StateFlow<CoupleBindUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<CoupleBindUiEvent>()
    val event: SharedFlow<CoupleBindUiEvent> = _event.asSharedFlow()

    fun onInputCodeChange(code: String) {
        _uiState.update { it.copy(inputCode = code, error = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun generateInviteCode() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            coupleRepository.generateInviteCode().fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(
                                generatedCode = it.inviteCode,
                                codeExpiresAt = it.expiresAt,
                                isLoading = false,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "生成失败")
                    }
                },
            )
        }
    }

    fun bindCouple() {
        val code = _uiState.value.inputCode.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(error = "请输入邀请码") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            coupleRepository.bindCouple(code).fold(
                onSuccess = { response ->
                    _uiState.update { it.copy(isLoading = false) }
                    // 刷新情侣状态
                    coupleStateManager.refresh()
                    _event.emit(CoupleBindUiEvent.BindSuccess)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "绑定失败")
                    }
                },
            )
        }
    }
}
