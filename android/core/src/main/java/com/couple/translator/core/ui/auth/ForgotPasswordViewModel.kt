package com.couple.translator.core.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ForgotPasswordUiState(
    val email: String = "",
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val step: ForgotStep = ForgotStep.ENTER_EMAIL,
    val isLoading: Boolean = false,
    val error: String = "",
    val successMessage: String = "",
)

enum class ForgotStep {
    ENTER_EMAIL,
    ENTER_CODE,
    DONE,
}

@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ForgotPasswordUiState())
    val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, error = "") }
    }

    fun onCodeChange(code: String) {
        _uiState.update { it.copy(code = code, error = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun onNewPasswordChange(password: String) {
        _uiState.update { it.copy(newPassword = password, error = "") }
    }

    fun onConfirmPasswordChange(password: String) {
        _uiState.update { it.copy(confirmPassword = password, error = "") }
    }

    fun sendCode() {
        val email = _uiState.value.email.trim()
        if (email.isBlank()) {
            _uiState.update { it.copy(error = "请输入邮箱") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            authRepository.forgotPassword(email).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            step = ForgotStep.ENTER_CODE,
                            successMessage = "验证码已发送到邮箱",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "发送失败")
                    }
                },
            )
        }
    }

    fun resetPassword() {
        val state = _uiState.value
        if (state.code.isBlank()) {
            _uiState.update { it.copy(error = "请输入验证码") }
            return
        }
        if (state.newPassword.length < 8) {
            _uiState.update { it.copy(error = "密码至少 8 位") }
            return
        }
        if (state.newPassword != state.confirmPassword) {
            _uiState.update { it.copy(error = "两次密码不一致") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            authRepository.resetPassword(state.email, state.code, state.newPassword).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, step = ForgotStep.DONE, successMessage = "密码重置成功")
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "重置失败")
                    }
                },
            )
        }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = "") }
    }
}
