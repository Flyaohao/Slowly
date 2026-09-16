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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationPrefUiState())
    val state: StateFlow<NotificationPrefUiState> = _state.asStateFlow()

    init {
        refresh()
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
}
