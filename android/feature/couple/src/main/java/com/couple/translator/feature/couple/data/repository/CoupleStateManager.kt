package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.network.SharedApiService
import com.couple.translator.feature.couple.network.CoupleApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class AppMode {
    SINGLE,      // 未绑定
    COUPLE,      // 已绑定
    UNBINDING;   // 解绑冷静期（视为情侣模式）

    /** 与本地缓存/登录响应的字符串互转（couple / unbinding / single） */
    val storageValue: String get() = name.lowercase()

    companion object {
        fun fromStorage(value: String?): AppMode? =
            entries.firstOrNull { it.storageValue == value }
    }
}

data class CoupleState(
    val mode: AppMode = AppMode.SINGLE,
    val isLoading: Boolean = true,
    val coupleInfo: CoupleDto.CoupleRelationResponse? = null,
    val userNickname: String? = null,
    /** 顶栏叠头像的统一数据源：首页/信箱/军师都从这里取，避免有的页面显示真头像、有的显示"我"字 */
    val userAvatarUrl: String? = null,
    val partnerNickname: String? = null,
    val partnerAvatarUrl: String? = null,
)

@Singleton
class CoupleStateManager @Inject constructor(
    private val apiService: CoupleApiService,
    private val sharedApiService: SharedApiService,
    private val tokenStore: TokenStore,
) {
    private val _state = MutableStateFlow(CoupleState())
    val state: StateFlow<CoupleState> = _state.asStateFlow()

    val isCoupleMode: Boolean get() = _state.value.mode != AppMode.SINGLE

    /** 缓存写入用的独立作用域：setCoupleBound/clearCouple 是同步函数，不能挂起 */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 模式是否已拿到过「可信值」（网络成功 / 绑定操作 / 本地缓存兜底）。
     * 修的 BUG：/couples/me 失败一次（含响应解析失败）曾让情侣用户当场
     * 变成单身模式——因为状态初值就是 SINGLE，catch 里「保持当前状态」
     * 保持的正是这个初值。现在刷新前先用上次成功的值垫底。
     */
    @Volatile
    private var modeResolved = false

    suspend fun refresh() {
        if (!modeResolved) {
            seedModeFromCache()
        }
        try {
            val response = apiService.getCoupleInfo()
            val info = response.data
            if (response.isSuccess && info != null) {
                val mode = when (info.status) {
                    "active" -> AppMode.COUPLE
                    "unbinding" -> AppMode.UNBINDING
                    else -> AppMode.SINGLE
                }
                modeResolved = true
                persistMode(mode)
                _state.update {
                    it.copy(mode = mode, coupleInfo = info, isLoading = false)
                }
            } else {
                // 请求失败 / data 为 null：保持当前模式（已由缓存或上次成功值垫底），
                // 只关 loading——绝不因一次失败把情侣用户摔成单身模式
                _state.update { it.copy(isLoading = false) }
            }
        } catch (e: Exception) {
            // 网络错误 / 响应解析失败：同上，保持当前模式
            _state.update { it.copy(isLoading = false) }
        }

        // 加载用户昵称 / 头像（单身、情侣模式通用），顶栏叠头像统一用这份
        try {
            val homeResponse = sharedApiService.getHomeData()
            if (homeResponse.isSuccess && homeResponse.data != null) {
                val data = homeResponse.data!!
                val relation = data.relation
                _state.update {
                    it.copy(
                        userNickname = relation?.userNickname ?: data.userNickname,
                        userAvatarUrl = relation?.userAvatarUrl ?: data.userAvatarUrl,
                        partnerNickname = relation?.partnerNickname,
                        partnerAvatarUrl = relation?.partnerAvatarUrl,
                    )
                }
            }
        } catch (_: Exception) {}
    }

    fun setCoupleBound(info: CoupleDto.CoupleRelationResponse) {
        modeResolved = true
        persistMode(AppMode.COUPLE)
        _state.update {
            it.copy(
                mode = AppMode.COUPLE,
                coupleInfo = info,
                isLoading = false,
            )
        }
    }

    fun clearCouple() {
        modeResolved = true
        persistMode(AppMode.SINGLE)
        _state.update {
            it.copy(
                mode = AppMode.SINGLE,
                coupleInfo = null,
                partnerNickname = null,
                partnerAvatarUrl = null,
                isLoading = false,
            )
        }
    }

    /** 首次刷新前用上次成功的模式垫底（登录响应写入或上次 /couples/me 成功时写入） */
    private suspend fun seedModeFromCache() {
        val cached = AppMode.fromStorage(tokenStore.getLastMode()) ?: return
        modeResolved = true
        _state.update { it.copy(mode = cached) }
    }

    private fun persistMode(mode: AppMode) {
        persistScope.launch {
            runCatching { tokenStore.saveLastMode(mode.storageValue) }
        }
    }
}
