package com.couple.translator.feature.couple.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.model.PresenceDto
import com.couple.translator.feature.couple.data.repository.CoupleRepository
import com.couple.translator.core.data.repository.HomeRepository
import com.couple.translator.feature.couple.data.repository.LetterRepository
import com.couple.translator.feature.couple.data.repository.PresenceRepository
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HomePrimaryAction {
    InvitePartner,
    ReadLetter,
    ContinueDraft,
    ViewAnniversary,
    ContinueRecord,
    ContinueMediation,
    WriteLetter,
}

data class RecentItem(
    val id: Long,
    val title: String,
    val excerpt: String,
    val timeLabel: String,
    val type: String,
)

data class NewHomeUiState(
    val nickname: String? = null,
    val coupleInfo: CoupleDto.CoupleRelationResponse? = null,
    val isBound: Boolean = false,
    val daysCount: Int = 0,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String = "",
    val primaryAction: HomePrimaryAction = HomePrimaryAction.WriteLetter,
    val primaryButtonText: String = "写一封信",
    val heroText: String = "这里是只属于你们的地方。",
    val recentItems: List<RecentItem> = emptyList(),
    val hasUnreadLetter: Boolean = false,
    val hasDraft: Boolean = false,
    val hasAnniversary: Boolean = false,
    // 聚合数据
    val homeData: HomeDto.HomeResponse? = null,
    val pendingLetterCount: Int = 0,
    val hasActiveMediation: Boolean = false,
    val hasFutureLetter: Boolean = false,
    val upcomingAnniversaryDays: Int? = null,
    val spaceName: String = "我们的空间",
    // 在场感：对方最新一条动态（此刻状态 / 陪伴请求）
    val partnerMoment: PresenceDto.MomentResponse? = null,
    val companionSent: Boolean = false,
)

@HiltViewModel
class NewHomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val coupleRepository: CoupleRepository,
    private val letterRepository: LetterRepository,
    private val homeRepository: HomeRepository,
    private val presenceRepository: PresenceRepository,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewHomeUiState())
    val uiState: StateFlow<NewHomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    /** 拉取在场感 feed，取对方最新一条动态展示在首页卡片。失败静默（不打扰首页）。 */
    private fun loadPresence(userId: Long?) {
        viewModelScope.launch {
            presenceRepository.getFeed().onSuccess { feed ->
                _uiState.update {
                    it.copy(partnerMoment = feed.firstOrNull { m -> m.userId != userId })
                }
            }
        }
    }

    /** 发送陪伴请求（对方首页可见）。 */
    fun sendCompanion() {
        viewModelScope.launch {
            presenceRepository.sendCompanionRequest().fold(
                onSuccess = {
                    _uiState.update { it.copy(companionSent = true) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "发送失败") }
                },
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = "") }

            try {
                val isLoggedIn = tokenStore.isLoggedIn()
                if (!isLoggedIn) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            isBound = false,
                            heroText = "这个空间还差一个人。",
                            primaryButtonText = "邀请 TA",
                            primaryAction = HomePrimaryAction.InvitePartner,
                        )
                    }
                    return@launch
                }

                val userResult = userRepository.getCurrentUser()
                val nickname = userResult.getOrNull()?.nickname

                val homeResult = homeRepository.getHomeData()
                val homeData = homeResult.getOrNull()

                if (homeData == null) {
                    _uiState.update {
                        it.copy(
                            nickname = nickname,
                            coupleInfo = null,
                            isBound = false,
                            isRefreshing = false,
                            heroText = "这个空间还差一个人。",
                            primaryButtonText = "邀请 TA",
                            primaryAction = HomePrimaryAction.InvitePartner,
                        )
                    }
                    return@launch
                }

                // 情侣模式已确认：拉在场感 feed（对方最新动态）
                loadPresence(userResult.getOrNull()?.userId)

                val relation = homeData.relation
                val loveDays = relation?.loveDays ?: 0
                val spaceName = homeData.space?.name ?: "我们的空间"

                val pendingLetters = homeData.pendingLetters
                val hasDraft = false
                val hasMediation = homeData.activeMediation != null
                val hasAnniversary = homeData.upcomingAnniversary != null

                val action: HomePrimaryAction
                val buttonText: String
                val heroText: String

                when {
                    pendingLetters.isNotEmpty() -> {
                        action = HomePrimaryAction.ReadLetter
                        buttonText = "去读信"
                        heroText = "有一封信还在等你。"
                    }
                    hasMediation -> {
                        action = HomePrimaryAction.ContinueMediation
                        buttonText = "继续调解"
                        heroText = "还有一场未完成的对话。"
                    }
                    hasAnniversary -> {
                        action = HomePrimaryAction.ViewAnniversary
                        buttonText = "看看纪念日"
                        heroText = "有一个特别的日子快到了。"
                    }
                    else -> {
                        action = HomePrimaryAction.WriteLetter
                        buttonText = "写一封信"
                        heroText = "这里是只属于你们的地方。"
                    }
                }

                val recentItems = mutableListOf<RecentItem>()
                pendingLetters.firstOrNull()?.let { letter ->
                    recentItems.add(
                        RecentItem(
                            id = letter.id,
                            title = letter.title ?: "无标题",
                            excerpt = "有一封信等你回应",
                            timeLabel = letter.sendTime?.take(10) ?: "",
                            type = "letter",
                        ),
                    )
                }
                homeData.futureLetter?.let { future ->
                    recentItems.add(
                        RecentItem(
                            id = future.id,
                            title = future.title ?: "未来信",
                            excerpt = "一封还未到时间的信",
                            timeLabel = "解锁于 ${future.unlockTime?.take(10) ?: ""}",
                            type = "future_letter",
                        ),
                    )
                }

                _uiState.update {
                    it.copy(
                        nickname = nickname,
                        coupleInfo = null,
                        isBound = true,
                        isRefreshing = false,
                        daysCount = loveDays,
                        heroText = heroText,
                        primaryButtonText = buttonText,
                        primaryAction = action,
                        recentItems = recentItems.take(2),
                        hasUnreadLetter = pendingLetters.isNotEmpty(),
                        hasDraft = hasDraft,
                        hasAnniversary = hasAnniversary,
                        homeData = homeData,
                        pendingLetterCount = homeData.pendingLetterCount,
                        hasActiveMediation = hasMediation,
                        hasFutureLetter = homeData.futureLetter != null,
                        upcomingAnniversaryDays = homeData.upcomingAnniversary?.daysUntil,
                        spaceName = spaceName,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = e.message ?: "加载失败",
                    )
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }

            try {
                val isLoggedIn = tokenStore.isLoggedIn()
                if (!isLoggedIn) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBound = false,
                            heroText = "这个空间还差一个人。",
                            primaryButtonText = "邀请 TA",
                            primaryAction = HomePrimaryAction.InvitePartner,
                        )
                    }
                    return@launch
                }

                val userResult = userRepository.getCurrentUser()
                val nickname = userResult.getOrNull()?.nickname

                // 尝试加载情侣首页聚合数据
                val homeResult = homeRepository.getHomeData()
                val homeData = homeResult.getOrNull()

                if (homeData == null) {
                    // 单身模式
                    _uiState.update {
                        it.copy(
                            nickname = nickname,
                            coupleInfo = null,
                            isBound = false,
                            isLoading = false,
                            heroText = "这个空间还差一个人。",
                            primaryButtonText = "邀请 TA",
                            primaryAction = HomePrimaryAction.InvitePartner,
                        )
                    }
                    return@launch
                }

                // 情侣模式：使用聚合数据
                // 情侣模式已确认：拉在场感 feed（对方最新动态）
                loadPresence(userResult.getOrNull()?.userId)

                val relation = homeData.relation
                val loveDays = relation?.loveDays ?: 0
                val spaceName = homeData.space?.name ?: "我们的空间"

                // 确定主操作
                val pendingLetters = homeData.pendingLetters
                val hasDraft = false // 从聚合数据中暂不包含草稿
                val hasMediation = homeData.activeMediation != null
                val hasAnniversary = homeData.upcomingAnniversary != null

                val action: HomePrimaryAction
                val buttonText: String
                val heroText: String

                when {
                    pendingLetters.isNotEmpty() -> {
                        action = HomePrimaryAction.ReadLetter
                        buttonText = "去读信"
                        heroText = "有一封信还在等你。"
                    }
                    hasMediation -> {
                        action = HomePrimaryAction.ContinueMediation
                        buttonText = "继续调解"
                        heroText = "还有一场未完成的对话。"
                    }
                    hasAnniversary -> {
                        action = HomePrimaryAction.ViewAnniversary
                        buttonText = "看看纪念日"
                        heroText = "有一个特别的日子快到了。"
                    }
                    else -> {
                        action = HomePrimaryAction.WriteLetter
                        buttonText = "写一封信"
                        heroText = "这里是只属于你们的地方。"
                    }
                }

                // 构建最近内容列表
                val recentItems = mutableListOf<RecentItem>()
                pendingLetters.firstOrNull()?.let { letter ->
                    recentItems.add(
                        RecentItem(
                            id = letter.id,
                            title = letter.title ?: "无标题",
                            excerpt = "有一封信等你回应",
                            timeLabel = letter.sendTime?.take(10) ?: "",
                            type = "letter",
                        ),
                    )
                }
                homeData.futureLetter?.let { future ->
                    recentItems.add(
                        RecentItem(
                            id = future.id,
                            title = future.title ?: "未来信",
                            excerpt = "一封还未到时间的信",
                            timeLabel = "解锁于 ${future.unlockTime?.take(10) ?: ""}",
                            type = "future_letter",
                        ),
                    )
                }

                _uiState.update {
                    it.copy(
                        nickname = nickname,
                        coupleInfo = null,
                        isBound = true,
                        isLoading = false,
                        daysCount = loveDays,
                        heroText = heroText,
                        primaryButtonText = buttonText,
                        primaryAction = action,
                        recentItems = recentItems.take(2),
                        hasUnreadLetter = pendingLetters.isNotEmpty(),
                        hasDraft = hasDraft,
                        hasAnniversary = hasAnniversary,
                        homeData = homeData,
                        pendingLetterCount = homeData.pendingLetterCount,
                        hasActiveMediation = hasMediation,
                        hasFutureLetter = homeData.futureLetter != null,
                        upcomingAnniversaryDays = homeData.upcomingAnniversary?.daysUntil,
                        spaceName = spaceName,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "加载失败",
                    )
                }
            }
        }
    }
}
