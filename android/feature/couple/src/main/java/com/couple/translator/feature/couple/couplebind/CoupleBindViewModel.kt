package com.couple.translator.feature.couple.couplebind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.UserDto
import com.couple.translator.core.data.repository.UserRepository
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
    val showUnbindDialog: Boolean = false,
    val unbindMessage: String = "",
    /**
     * 当前用户的性别（「男」/「女」/空）。
     *
     * 2026-09-29：绑定链路后端强制校验双方性别（自己没填 → 30008，对方没填 → 30009），
     * 而刚注册的用户还没进过「我的 → 资料编辑」，会卡在「生成恋爱码」这一步却不知为何。
     * 故把性别设置搬进本页：生成恋爱码前若未设置，先就地落库。
     * 只允许男/女两个值（与后端 has_explicit_gender 一致），允许双方同性。
     */
    val gender: String = "",
    /**
     * 是否需要弹「先选性别」弹窗。
     *
     * 触发时机（两者共用同一份弹窗）：
     * ① 点「生成恋爱码」时性别未设置（后端 generate_invite_code 会 30008）；
     * ② 点「绑定」时性别未设置（后端 bind_couple 会 30008）。
     * 选定并保存成功后自动续跑被中断的那个动作（见 pendingAction）。
     */
    val showGenderDialog: Boolean = false,
    /** 性别是否已确认落库过（用于「取消弹窗」时决定是否保留已选值） */
    val genderSaved: Boolean = false,
) {
    /** 性别是否已设置（男/女）。空串视为未设置。 */
    val genderSelected: Boolean get() = gender == GENDER_MALE || gender == GENDER_FEMALE

    companion object {
        const val GENDER_MALE = "男"
        const val GENDER_FEMALE = "女"
        /** 未选性别时拦下生成/绑定用的提示文案 */
        const val MSG_GENDER_REQUIRED = "请先选择你的性别"
    }
}

/** 性别弹窗确认后要续跑的动作 */
private enum class PendingAction { NONE, GENERATE, BIND }

sealed class CoupleBindUiEvent {
    data object BindSuccess : CoupleBindUiEvent()
    data class ShowError(val message: String) : CoupleBindUiEvent()
}

@HiltViewModel
class CoupleBindViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
    private val userRepository: UserRepository,
    private val coupleStateManager: CoupleStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoupleBindUiState())
    val uiState: StateFlow<CoupleBindUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<CoupleBindUiEvent>()
    val event: SharedFlow<CoupleBindUiEvent> = _event.asSharedFlow()

    /** 性别弹窗确认后要续跑的动作（生成 / 绑定 / 无） */
    private var pendingAction = PendingAction.NONE

    init {
        // 预填已有性别：老用户（在资料页填过）不该被本页再问一次
        loadGender()
    }

    private fun loadGender() {
        viewModelScope.launch {
            userRepository.getCurrentUser().onSuccess { user ->
                val g = user.gender.orEmpty()
                if (g == CoupleBindUiState.GENDER_MALE || g == CoupleBindUiState.GENDER_FEMALE) {
                    _uiState.update { it.copy(gender = g, genderSaved = true) }
                }
            }
        }
    }

    /** 用户在弹窗里选性别（男/女）。选中即写本地，点「确认」时才落库。 */
    fun onGenderChange(gender: String) {
        _uiState.update { it.copy(gender = gender, error = "") }
    }

    /** 关闭性别弹窗（用户取消）。取消时清掉未确认的选择，避免残留误导。 */
    fun dismissGenderDialog() {
        _uiState.update {
            it.copy(
                showGenderDialog = false,
                gender = if (it.genderSaved) it.gender else "",
                error = "",
            )
        }
        pendingAction = PendingAction.NONE
    }

    /**
     * 弹窗「确认」：把选中的性别落库（PUT /users/me），成功后自动续跑
     * 之前被中断的动作（生成恋爱码 / 绑定）。
     */
    fun confirmGender() {
        val gender = _uiState.value.gender
        if (gender != CoupleBindUiState.GENDER_MALE && gender != CoupleBindUiState.GENDER_FEMALE) {
            _uiState.update { it.copy(error = CoupleBindUiState.MSG_GENDER_REQUIRED) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            userRepository.updateProfile(UserDto.UpdateProfileRequest(gender = gender)).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, showGenderDialog = false, genderSaved = true)
                    }
                    resumePendingAction()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "性别保存失败，请重试")
                    }
                },
            )
        }
    }

    /** 续跑被性别弹窗打断的动作。 */
    private fun resumePendingAction() {
        when (pendingAction) {
            PendingAction.GENERATE -> {
                pendingAction = PendingAction.NONE
                generateInviteCode()
            }
            PendingAction.BIND -> {
                pendingAction = PendingAction.NONE
                bindCouple()
            }
            PendingAction.NONE -> Unit
        }
    }

    fun onInputCodeChange(code: String) {
        _uiState.update { it.copy(inputCode = code, error = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun generateInviteCode() {
        // 性别未设置 → 弹窗问一次，选完自动续跑（后端缺失会 30008，不先解决必撞墙）
        if (!_uiState.value.genderSelected) {
            pendingAction = PendingAction.GENERATE
            _uiState.update { it.copy(showGenderDialog = true, error = "") }
            return
        }
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
            _uiState.update { it.copy(error = "请输入恋爱码") }
            return
        }

        // 性别未设置 → 同一个弹窗（后端 bind_couple 同样会 30008）
        if (!_uiState.value.genderSelected) {
            pendingAction = PendingAction.BIND
            _uiState.update { it.copy(showGenderDialog = true, error = "") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            coupleRepository.bindCouple(code).fold(
                onSuccess = { response ->
                    _uiState.update { it.copy(isLoading = false) }
                    // 立即设置情侣模式（用绑定响应数据）
                    if (response != null) {
                        coupleStateManager.setCoupleBound(response)
                    }
                    // 后台刷新获取完整信息（含空间等）
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
}
