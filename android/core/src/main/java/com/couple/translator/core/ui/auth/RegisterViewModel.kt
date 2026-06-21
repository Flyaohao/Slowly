package com.couple.translator.core.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.repository.AuthRepository
import com.couple.translator.core.common.isValidEmail
import com.couple.translator.core.common.isValidPassword
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

data class RegisterUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val emailError: String = "",
    val passwordError: String = "",
    val confirmPasswordError: String = "",
    val generalError: String = "",
)

sealed class RegisterUiEvent {
    data object RegisterSuccess : RegisterUiEvent()
    data class ShowError(val message: String) : RegisterUiEvent()
}

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<RegisterUiEvent>()
    val event: SharedFlow<RegisterUiEvent> = _event.asSharedFlow()

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, emailError = "") }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, passwordError = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(generalError = "") }
    }

    fun onConfirmPasswordChange(confirmPassword: String) {
        _uiState.update { it.copy(confirmPassword = confirmPassword, confirmPasswordError = "") }
    }

    fun register() {
        val state = _uiState.value

        var hasError = false
        if (state.email.isBlank() || !state.email.isValidEmail) {
            _uiState.update { it.copy(emailError = "请输入有效的邮箱地址") }
            hasError = true
        }
        if (!state.password.isValidPassword) {
            _uiState.update {
                it.copy(passwordError = "密码至少8位，需含大小写字母和数字")
            }
            hasError = true
        }
        if (state.password != state.confirmPassword) {
            _uiState.update { it.copy(confirmPasswordError = "两次密码不一致") }
            hasError = true
        }
        if (hasError) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, generalError = "") }
            val result = authRepository.register(state.email, state.password)
            result.fold(
                onSuccess = {
                    authRepository.login(state.email, state.password).fold(
                        onSuccess = {
                            _uiState.update { it.copy(isLoading = false) }
                            _event.emit(RegisterUiEvent.RegisterSuccess)
                        },
                        onFailure = { loginError ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    generalError = "注册成功，请手动登录",
                                )
                            }
                        },
                    )
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            generalError = error.message ?: "注册失败",
                        )
                    }
                },
            )
        }
    }
}
