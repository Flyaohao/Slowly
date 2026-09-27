package com.couple.translator.core.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页里与后端有关的那部分状态（目前只有「邮件通知」开关）。
 *
 * 外观模式不在这里——它存在本地 DataStore（ThemeStore），改完立刻换肤，
 * 不该为了一个本地偏好跑一趟网络。
 */
data class NotificationPrefUiState(
    /** 开关当前值。**默认 false**：通知默认关闭是产品决定，不是初始加载态。 */
    val emailNotifyEnabled: Boolean = false,
    /** 收件邮箱，展示用 */
    val email: String = "",
    /** 服务端 SMTP 是否配置好；false 时开关置灰 */
    val emailReady: Boolean = false,
    /**
     * 是否压根没读到（网络/服务端问题）。
     * 必须和「服务端明确回复发信未配置」区分开：两者的界面文案完全不同，
     * 把 404/断网说成"服务端没配发信"会让排查方向跑偏。
     */
    val loadFailed: Boolean = false,
    val isLoading: Boolean = false,
    /** 一次性的错误提示，展示后由界面消费掉 */
    val error: String = "",
)

/**
 * 军师记忆沉淀总开关（关系级，情侣模式专属）。
 *
 * 字段名带 distill 前缀是为了与设计文档（决策点表）一一对应，便于对照评审。
 */
data class DistillSwitchUiState(
    /** 开关当前值（服务端关系级配置，双人共享） */
    val distillEnabled: Boolean = false,
    /** 读取/写入请求进行中：Switch 置灰防连点 */
    val distillLoading: Boolean = false,
    /** false = 未绑定关系（30005）或读取失败：整个「军师」分组隐藏 */
    val distillAvailable: Boolean = false,
    /** 一次性的写入错误提示，展示后由界面消费掉 */
    val distillError: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationPrefUiState())
    val state: StateFlow<NotificationPrefUiState> = _state.asStateFlow()

    private val _distillState = MutableStateFlow(DistillSwitchUiState())
    val distillState: StateFlow<DistillSwitchUiState> = _distillState.asStateFlow()

    init {
        refresh()
        refreshDistill()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            userRepository.getNotificationPref().fold(
                onSuccess = { pref ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            emailNotifyEnabled = pref.emailNotifyEnabled,
                            email = pref.email,
                            emailReady = pref.emailReady,
                        )
                    }
                },
                onFailure = { e ->
                    // 读失败就保持默认（关闭）——绝不能因为一次网络抖动
                    // 让界面显示成"已开启"，那会让用户误以为已经设好了
                    _state.update {
                        it.copy(isLoading = false, error = e.message ?: "读取通知设置失败")
                    }
                },
            )
        }
    }

    fun setEmailNotify(enabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            userRepository.setEmailNotify(enabled).fold(
                onSuccess = { pref ->
                    // 以服务端返回的值为准，不做乐观更新
                    _state.update {
                        it.copy(
                            isLoading = false,
                            emailNotifyEnabled = pref.emailNotifyEnabled,
                            email = pref.email,
                            emailReady = pref.emailReady,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(isLoading = false, error = e.message ?: "保存失败")
                    }
                },
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = "") }
    }

    fun refreshDistill() {
        viewModelScope.launch {
            _distillState.update { it.copy(distillLoading = true) }
            when (val outcome = userRepository.getDistillSwitch()) {
                is UserRepository.DistillSwitchOutcome.Ready -> _distillState.update {
                    it.copy(
                        distillLoading = false,
                        distillAvailable = true,
                        distillEnabled = outcome.enabled,
                    )
                }
                // 未绑定关系：整组隐藏（决策点④），不发写入请求，也不弹错误
                UserRepository.DistillSwitchOutcome.NoRelation -> _distillState.update {
                    it.copy(distillLoading = false, distillAvailable = false)
                }
                is UserRepository.DistillSwitchOutcome.Failed -> _distillState.update {
                    // 读失败保持分组隐藏，不弹 Snackbar——每次进页面都弹会变成骚扰
                    it.copy(distillLoading = false, distillAvailable = false)
                }
            }
        }
    }

    /**
     * 切换军师记忆沉淀开关。不做乐观更新：请求成功前 Switch 停在原值并置灰，
     * 失败原地不动 + Snackbar 提示（与邮件通知开关同一套模式）。
     * 开→关 的二次确认弹窗由界面负责，这里只收最终结果。
     */
    fun toggleDistill(enabled: Boolean) {
        viewModelScope.launch {
            _distillState.update { it.copy(distillLoading = true) }
            when (val outcome = userRepository.setDistillSwitch(enabled)) {
                is UserRepository.DistillSwitchOutcome.Ready -> _distillState.update {
                    it.copy(
                        distillLoading = false,
                        distillAvailable = true,
                        distillEnabled = outcome.enabled,
                    )
                }
                // 写的时候关系恰好被解绑：按 30005 处理，整组隐藏
                UserRepository.DistillSwitchOutcome.NoRelation -> _distillState.update {
                    it.copy(distillLoading = false, distillAvailable = false)
                }
                is UserRepository.DistillSwitchOutcome.Failed -> _distillState.update {
                    // 开关停在原值（从未乐观改过），错误必须可见
                    it.copy(distillLoading = false, distillError = outcome.message)
                }
            }
        }
    }

    fun clearDistillError() {
        _distillState.update { it.copy(distillError = "") }
    }
}
