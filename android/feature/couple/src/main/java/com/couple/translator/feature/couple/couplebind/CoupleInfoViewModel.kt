package com.couple.translator.feature.couple.couplebind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.network.SharedApiService
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
    /**
     * 当前登录用户 id（解绑确认链门控用，契约 §2.6-2）。
     * 获取失败为 null → FE 降级旧行为（确认按钮始终展示，由服务端 30004/30006 拦截）。
     */
    val myUserId: Long? = null,
)

sealed class CoupleInfoEvent {
    data object NavigateToMain : CoupleInfoEvent()
}

@HiltViewModel
class CoupleInfoViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
    private val coupleStateManager: CoupleStateManager,
    private val sharedApiService: SharedApiService,
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

    /**
     * @param silent 静默刷新（解绑动作成功后刷新门控字段），不切骨架屏、不覆盖错误提示
     */
    fun loadCoupleInfo(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isLoading = true, error = "") }
            }
            // 解绑确认链门控（契约 §2.6-2）：拿不到 myUserId 时保持 null → FE 降级旧行为
            val myUserId = runCatching { sharedApiService.getCurrentUser().data?.userId }.getOrNull()
            coupleRepository.getCoupleInfo().fold(
                onSuccess = { info ->
                    _uiState.update {
                        it.copy(coupleInfo = info, isLoading = false, myUserId = myUserId)
                    }
                },
                onFailure = { error ->
                    if (!silent) {
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message ?: "加载失败")
                        }
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
                    // 刷新 unbind_requested_at/by → 展示冷却状态与确认链门控（契约 §2.6-2）
                    loadCoupleInfo(silent = true)
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
                    loadCoupleInfo(silent = true)
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
                    // 🔴 2026-09-29 修复：此处原先只调 refresh()，但解绑完成后
                    // /couples/me 会返回 30005（关系已失效），refresh() 的 catch 分支
                    // 是「保持当前模式」，于是用户解绑后仍停在情侣壳里——假解绑。
                    // clearCouple() 明确把模式置为未绑定，NavGraph 的 Main 目的地
                    // 随之渲染强制绑定页，用户被引导去重新绑定。
                    coupleStateManager.clearCouple()
                    loadCoupleInfo(silent = true)
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
