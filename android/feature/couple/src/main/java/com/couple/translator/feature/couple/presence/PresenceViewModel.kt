package com.couple.translator.feature.couple.presence

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.data.repository.HomeRepository
import com.couple.translator.core.data.repository.UserRepository
import com.couple.translator.feature.couple.data.model.PresenceDto
import com.couple.translator.feature.couple.data.repository.PresenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class PresenceUiState(
    /** 下次见面日期（ISO yyyy-MM-dd），null 表示还没设置 */
    val nextMeetDate: String? = null,
    /** 双方最近动态（此刻 / 陪伴请求），按时间倒序 */
    val moments: List<PresenceDto.MomentResponse> = emptyList(),
    /** 当前用户 id，用于区分「我发的」和「TA 发的」 */
    val myUserId: Long = 0,
    val companionSent: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String = "",
) {

    val meetDate: LocalDate?
        get() = nextMeetDate?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
}

/**
 * 异地陪伴页：把散在首页的在场感能力聚合成一个独立空间——
 * 见面倒计时、共享此刻（互相发今天的状态）、一键陪伴请求。
 */
@HiltViewModel
class PresenceViewModel @Inject constructor(
    private val presenceRepository: PresenceRepository,
    private val homeRepository: HomeRepository,
    private val userRepository: UserRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PresenceUiState())
    val uiState: StateFlow<PresenceUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            userRepository.getCurrentUser().fold(
                onSuccess = { user -> _uiState.update { it.copy(myUserId = user?.userId ?: 0) } },
                onFailure = { /* 拿不到 id 只影响左右侧样式，不阻塞 */ },
            )

            // 见面日期挂在 couple_space 上，随首页聚合接口一起下发
            homeRepository.getHomeData().fold(
                onSuccess = { home ->
                    _uiState.update { it.copy(nextMeetDate = home.space?.nextMeetDate) }
                },
                onFailure = { /* 首页拉不到不阻塞动态流 */ },
            )

            presenceRepository.getFeed().fold(
                onSuccess = { moments ->
                    _uiState.update {
                        it.copy(moments = moments, isLoading = false, isRefreshing = false)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = error.message ?: "加载失败",
                        )
                    }
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    /** 发布共享此刻；成功后把新动态插到列表最前面（服务端按时间倒序返回）。 */
    fun shareMoment(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            presenceRepository.shareMoment(text).fold(
                onSuccess = { moment ->
                    moment?.let {
                        _uiState.update { state ->
                            state.copy(moments = listOf(it) + state.moments)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = error.message ?: "发布失败")
                    }
                },
            )
        }
    }

    /** 发出陪伴请求（想你了）。成功后本地置位，避免重复骚扰。 */
    fun sendCompanionRequest() {
        if (_uiState.value.companionSent) return
        viewModelScope.launch {
            presenceRepository.sendCompanionRequest().fold(
                onSuccess = {
                    _uiState.update { it.copy(companionSent = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = error.message ?: "发送失败")
                    }
                },
            )
        }
    }

    /** 设置下次见面日期（yyyy-MM-dd）。 */
    fun setMeetDate(date: LocalDate) {
        viewModelScope.launch {
            presenceRepository.setMeetDate(date.toString()).fold(
                onSuccess = { data ->
                    _uiState.update {
                        it.copy(nextMeetDate = data?.nextMeetDate ?: date.toString())
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = error.message ?: "设置失败")
                    }
                },
            )
        }
    }
}
