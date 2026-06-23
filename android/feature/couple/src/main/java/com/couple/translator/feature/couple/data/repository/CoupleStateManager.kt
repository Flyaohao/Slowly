package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.network.SharedApiService
import com.couple.translator.feature.couple.network.CoupleApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class AppMode {
    SINGLE,      // 未绑定
    COUPLE,      // 已绑定
    UNBINDING,   // 解绑冷静期（视为情侣模式）
}

data class CoupleState(
    val mode: AppMode = AppMode.SINGLE,
    val isLoading: Boolean = true,
    val coupleInfo: CoupleDto.CoupleRelationResponse? = null,
    val userNickname: String? = null,
)

@Singleton
class CoupleStateManager @Inject constructor(
    private val apiService: CoupleApiService,
    private val sharedApiService: SharedApiService,
) {
    private val _state = MutableStateFlow(CoupleState())
    val state: StateFlow<CoupleState> = _state.asStateFlow()

    val isCoupleMode: Boolean get() = _state.value.mode != AppMode.SINGLE

    suspend fun refresh() {
        try {
            val response = apiService.getCoupleInfo()
            val info = response.data
            if (response.isSuccess && info != null) {
                val mode = when (info.status) {
                    "active" -> AppMode.COUPLE
                    "unbinding" -> AppMode.UNBINDING
                    else -> AppMode.SINGLE
                }
                _state.update {
                    it.copy(mode = mode, coupleInfo = info, isLoading = false)
                }
            }
            // API 返回成功但 data 为 null，保持当前状态不清除
        } catch (e: Exception) {
            // 网络错误时保持当前状态，不回退到 SINGLE
            _state.update { it.copy(isLoading = false) }
        }

        // 加载用户昵称（单身/情侣模式通用）
        try {
            val homeResponse = sharedApiService.getHomeData()
            if (homeResponse.isSuccess && homeResponse.data != null) {
                val data = homeResponse.data!!
                val nickname = data.userNickname ?: data.relation?.userNickname
                _state.update { it.copy(userNickname = nickname) }
            }
        } catch (_: Exception) {}
    }

    fun setCoupleBound(info: CoupleDto.CoupleRelationResponse) {
        _state.update {
            it.copy(
                mode = AppMode.COUPLE,
                coupleInfo = info,
                isLoading = false,
            )
        }
    }

    fun clearCouple() {
        _state.update {
            it.copy(
                mode = AppMode.SINGLE,
                coupleInfo = null,
                isLoading = false,
            )
        }
    }

    /**
     * 登录后直接设置模式（从 login 响应的 mode 字段）
     * 减少额外的 /couples/info 请求
     */
    fun setMode(mode: String) {
        val appMode = when (mode) {
            "couple" -> AppMode.COUPLE
            else -> AppMode.SINGLE
        }
        _state.update {
            it.copy(mode = appMode, isLoading = false)
        }
    }
}
