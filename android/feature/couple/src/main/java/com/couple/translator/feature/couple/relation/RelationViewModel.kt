package com.couple.translator.feature.couple.relation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.data.repository.HomeRepository
import com.couple.translator.core.data.repository.ProfileRepository
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.repository.AnniversaryRepository
import com.couple.translator.feature.couple.data.repository.CoupleRepository
import com.couple.translator.feature.couple.data.repository.LetterRepository
import com.couple.translator.feature.couple.data.repository.MediationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 解绑确认状态（契约 §2.6-2 的展示切片；完整对话框在 couple_info 页）。 */
data class UnbindStatusUi(
    val requestedAt: String,
    /** null = 服务端未给发起人，无法判定我方角色，展示中性文案。 */
    val isInitiator: Boolean? = null,
)

/**
 * 刷新失败时的提示文案（[RelationViewModel] 与单测共用，避免两处各写一份）。
 * 只报**条数**不报哪一项：用户要的是「显示的可能不是最新的」，不是诊断报告。
 */
internal fun refreshFailedMessage(failedCount: Int): String =
    "有 $failedCount 项没刷新出来，显示的是上次的内容"

/**
 * 关系 tab（契约 §3.5 / 整改 §8.4）。
 *
 * 2026-09-27 关系页改版：调解邀请 / 双视角「我未提交」/ 解绑确认三类待办
 * 迁往 [TodoViewModel]（侧边栏「待办」条目 + 待办列表页），本 VM 只剩
 * 关系背景：纪念日、绑定信息（love_days）、关系画像摘要、深度表达、调解回看。
 *
 * 每条数据源独立降级：任何一路失败只丢对应区块，不拖垮整页；
 * 失败时优先沿用上一次成功的结果，且不得把「读不到」渲染成「没有」。
 */
data class RelationUiState(
    val isLoading: Boolean = true,

    /**
     * 整页加载失败（M3）：关键源 home + couples/me **同时**失败——此时页面连
     * 「我有没有伴侣、绑定多久」都答不出来，只能整页报错 + 重试。
     *
     * 整改 §8.4：单项失败一律不得遮住其他成功数据。此前判定里还有一条
     * 「7 路全失败也算整页失败」，可邀请/纪念日/画像/收件箱四路对多数用户本来
     * 就是空的、且接口可能整体下线，四路一起失败却仍是「真的没有待办」，
     * 于是整页报错反而把已经拿到的 home + couples 数据盖掉了。
     */
    val loadError: Boolean = false,

    // ----- 关系背景 -----
    /**
     * §8.5-6「能回看」：我参与过的**已完成**调解（role=history）。
     * 后端已保证 completed 不再被拒（`_get_session` 放行、mine/all 保留），
     * 客户端这条入口把它变成用户看得见的一条路——此前列表只查 invited，
     * 调解一结束就从 App 里彻底消失了。渲染成「调解回看」行，仅非空时显示。
     */
    val completedMediations: List<MediationDto.MediationListItem> = emptyList(),
    val loveDays: Int? = null,
    val partnerNickname: String? = null,
    val bindTime: String? = null,
    val upcomingAnniversary: AnniversaryDto.AnniversaryResponse? = null,
    val upcomingDaysUntil: Int? = null,
    val coupleSummary: String? = null,
    val inboxCount: Int = 0,
)

@HiltViewModel
class RelationViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val coupleRepository: CoupleRepository,
    private val mediationRepository: MediationRepository,
    private val anniversaryRepository: AnniversaryRepository,
    private val profileRepository: ProfileRepository,
    private val letterRepository: LetterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RelationUiState())
    val uiState: StateFlow<RelationUiState> = _uiState.asStateFlow()

    // 上一次成功拿到的背景数据。**只增不减**：接口挂了要用它兜住已有内容，
    // 否则「网络抖一下」会让关系页看起来像被清空了（见 [load] 的说明）。
    // 仅内存缓存，不落盘——它只是渲染的兜底，不作为离线数据源。
    private var lastHome: HomeDto.HomeResponse? = null
    private var lastCouple: CoupleDto.CoupleRelationResponse? = null
    private var lastAnniversaries: AnniversaryDto.AnniversaryListResponse? = null
    private var lastSummary: String? = null
    private var lastInboxCount: Int? = null

    /**
     * 一次性提示（整页失败 / 刷新失败）。用 SharedFlow 而不是塞进 [RelationUiState]：
     * 弹过一次就该消失，留在状态里会让每次重组都重弹。
     */
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        load()
    }

    /**
     * 刷新一次关系页。
     *
     * [isRefresh] = true 时**不清空**当前数据、也不进入整页 loading：返回本页
     * （子流程走完回来 / 从后台切回）时页面内容必须原地更新，不能先闪成骨架、
     * 更不能把已经渲染好的内容抹掉。
     *
     * 失败处理同样是「保留旧值」而不是「写空」：`GET /anniversaries` 这类接口
     * 一挂就返回空列表，整体覆盖会让原本显示着的纪念日凭空消失，用户看到的是
     * 数据被删了。因此每次成功都记下结果，失败时按源回退到 [lastGood]。
     */
    fun load(isRefresh: Boolean = false) {
        if (isRefresh && _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = !isRefresh, loadError = false) }
        viewModelScope.launch {
            val state = coroutineScope {
                val homeDeferred = async { homeRepository.getHomeData() }
                val coupleDeferred = async { coupleRepository.getCoupleInfo() }
                // §8.5-6：已完成调解单独查一路（role=history），失败就整行不显示。
                val mediationHistoryDeferred = async { mediationRepository.getMediationList("history") }
                val anniversaryDeferred = async { anniversaryRepository.getAnniversaries() }
                val profileDeferred = async { profileRepository.getCoupleProfile() }
                val inboxDeferred = async { letterRepository.getInbox() }

                // 先拿 Result，成功/失败都要能区分——整页失败判定（M3）依赖它。
                val homeResult = homeDeferred.await()
                val coupleResult = coupleDeferred.await()
                val mediationHistoryResult = mediationHistoryDeferred.await()
                val anniversaryResult = anniversaryDeferred.await()
                val profileResult = profileDeferred.await()
                val inboxResult = inboxDeferred.await()

                val home: HomeDto.HomeResponse? = homeResult.getOrNull()
                val coupleInfo: CoupleDto.CoupleRelationResponse? = coupleResult.getOrNull()
                val anniversaries: AnniversaryDto.AnniversaryListResponse? = anniversaryResult.getOrNull()

                // 整页失败（M3 / 整改 §8.4）：只有关键源 home + couples/me **同时**失败
                // 才算整页失败。两路合起来提供伴侣昵称 / 绑定时间，都没有时页面无从渲染。
                // 其余任一路失败只丢对应区块。
                val loadError = homeResult.isFailure && coupleResult.isFailure

                // 成功就更新缓存，失败沿用上一次成功的结果（见 load() 的说明）。
                if (home != null) lastHome = home
                if (coupleInfo != null) lastCouple = coupleInfo
                if (anniversaries != null) lastAnniversaries = anniversaries
                if (profileResult.isSuccess) {
                    lastSummary = profileResult.getOrNull()?.summary
                }
                if (inboxResult.isSuccess) {
                    lastInboxCount = inboxResult.getOrNull()?.total ?: 0
                }

                val effectiveHome = home ?: lastHome
                val effectiveCouple = coupleInfo ?: lastCouple
                val effectiveAnniversaries = anniversaries ?: lastAnniversaries
                val effectiveSummary = if (profileResult.isSuccess) {
                    profileResult.getOrNull()?.summary
                } else {
                    lastSummary
                }
                val effectiveInbox = if (inboxResult.isSuccess) {
                    inboxResult.getOrNull()?.total ?: 0
                } else {
                    lastInboxCount ?: 0
                }

                val nextAnniversary = effectiveAnniversaries?.items
                    ?.let { pickUpcoming(it) }

                // 刷新失败计数：只看「整页失败态不覆盖」的那几路。
                val failedCount = listOf(homeResult, coupleResult).count { it.isFailure }

                RelationUiState(
                    isLoading = false,
                    loadError = loadError,
                    completedMediations = if (mediationHistoryResult.isSuccess) {
                        mediationHistoryResult.getOrNull() ?: emptyList()
                    } else {
                        emptyList()
                    },
                    // 契约 §3.5 love_days 首选 couples/me（收敛期新增字段），未落地前回退 GET /home。
                    loveDays = effectiveCouple?.loveDays ?: effectiveHome?.relation?.loveDays,
                    partnerNickname = effectiveHome?.relation?.partnerNickname,
                    bindTime = effectiveCouple?.bindTime,
                    upcomingAnniversary = nextAnniversary?.first,
                    upcomingDaysUntil = nextAnniversary?.second,
                    coupleSummary = effectiveSummary,
                    inboxCount = effectiveInbox,
                ) to failedCount
            }
            _uiState.update { state.first }

            // 刷新（页面已渲染）失败 → 只提示，不动已有内容；首载失败交给整页错误态。
            if (isRefresh && state.second > 0) {
                _messages.tryEmit(refreshFailedMessage(state.second))
            }
        }
    }

    /**
     * 从纪念日列表挑下一次要到来的。
     *
     * 整改 §8.8：**日期语义由服务端算好**（`next_occurrence_date` /
     * `days_until`），这里只做挑选，不再自己推日期。
     *
     * 之前客户端自己算「今年的月日，过了就明年」，后果就是契约点名的
     * 「还有 55 天 · 2025-11-20」：一次性纪念日被顶到下一年，年份和天数互相
     * 打架；而且两端各算一遍，首页和列表迟早给出两个不同的数字。
     *
     * `days_until == null`（一次性且已过去）→ 不参与「下一次」的角逐。
     */
    private fun pickUpcoming(
        items: List<AnniversaryDto.AnniversaryResponse>,
    ): Pair<AnniversaryDto.AnniversaryResponse, Int>? =
        items
            .mapNotNull { item -> item.daysUntil?.let { item to it } }
            .minByOrNull { it.second }
}
