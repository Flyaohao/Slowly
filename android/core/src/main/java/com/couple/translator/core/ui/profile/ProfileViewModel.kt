package com.couple.translator.core.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.UserDto
import com.couple.translator.core.data.repository.UserRepository
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

data class ProfileUiState(
    val nickname: String = "",
    val gender: String = "",
    val birthday: String = "",
    /** 出生时辰 0-23，null = 未填（不拿正午当默认值去编一个上升星座） */
    val birthHour: Int? = null,
    /** MBTI 16 型代号，"" = 未填 */
    val mbti: String = "",
    /** 出生地（如 "杭州"），"" = 未填；命中城市表才用于上升精算 */
    val birthPlace: String = "",
    val city: String = "",
    val signature: String = "",
    val avatarUrl: String = "",
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

sealed class ProfileUiEvent {
    data object SaveSuccess : ProfileUiEvent()
    data class ShowError(val message: String) : ProfileUiEvent()
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<ProfileUiEvent>()
    val event: SharedFlow<ProfileUiEvent> = _event.asSharedFlow()

    init {
        loadProfile()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            userRepository.getCurrentUser().fold(
                onSuccess = { user ->
                    _uiState.update {
                        it.copy(
                            nickname = user.nickname ?: "",
                            gender = user.gender ?: "",
                            birthday = user.birthday ?: "",
                            birthHour = user.birthHour,
                            mbti = user.mbti ?: "",
                            birthPlace = user.birthPlace ?: "",
                            city = user.city ?: "",
                            signature = user.signature ?: "",
                            avatarUrl = user.avatarUrl ?: "",
                            isRefreshing = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isRefreshing = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            userRepository.getCurrentUser().fold(
                onSuccess = { user ->
                    _uiState.update {
                        it.copy(
                            nickname = user.nickname ?: "",
                            gender = user.gender ?: "",
                            birthday = user.birthday ?: "",
                            birthHour = user.birthHour,
                            mbti = user.mbti ?: "",
                            birthPlace = user.birthPlace ?: "",
                            city = user.city ?: "",
                            signature = user.signature ?: "",
                            avatarUrl = user.avatarUrl ?: "",
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

    fun onNicknameChange(value: String) {
        _uiState.update { it.copy(nickname = value) }
    }

    fun onGenderChange(value: String) {
        _uiState.update { it.copy(gender = value) }
    }

    fun onBirthdayChange(value: String) {
        _uiState.update { it.copy(birthday = value) }
    }

    fun onBirthHourChange(value: Int?) {
        _uiState.update { it.copy(birthHour = value) }
    }

    fun onMbtiChange(value: String) {
        _uiState.update { it.copy(mbti = value) }
    }

    fun onBirthPlaceChange(value: String) {
        _uiState.update { it.copy(birthPlace = value) }
    }

    fun onCityChange(value: String) {
        _uiState.update { it.copy(city = value) }
    }

    fun onSignatureChange(value: String) {
        _uiState.update { it.copy(signature = value) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun toggleEditing() {
        _uiState.update { it.copy(isEditing = !it.isEditing) }
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val request = UserDto.UpdateProfileRequest(
                nickname = state.nickname.ifBlank { null },
                gender = state.gender.ifBlank { null },
                birthday = state.birthday.ifBlank { null },
                birthHour = state.birthHour,
                mbti = state.mbti.ifBlank { null },
                birthPlace = state.birthPlace.ifBlank { null },
                city = state.city.ifBlank { null },
                signature = state.signature.ifBlank { null },
            )
            userRepository.updateProfile(request).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, isEditing = false) }
                    _event.emit(ProfileUiEvent.SaveSuccess)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "保存失败")
                    }
                },
            )
        }
    }
}
