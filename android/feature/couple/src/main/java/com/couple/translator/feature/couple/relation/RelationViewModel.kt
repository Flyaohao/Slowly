package com.couple.translator.feature.couple.relation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.data.repository.HomeRepository
import com.couple.translator.core.data.repository.ProfileRepository
import com.couple.translator.core.network.SharedApiService
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.repository.AnniversaryRepository
import com.couple.translator.feature.couple.data.repository.CoupleRepository
import com.couple.translator.feature.couple.data.repository.DualPerspectiveRepository
import com.couple.translator.feature.couple.data.repository.LetterRepository
import com.couple.translator.feature.couple.data.repository.MediationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** 解绑确认状态（契约 §2.6-2 的展示切片；完整对话框在 couple_info 页）。 */
data class UnbindStatusUi(
    val requestedAt: String,
    /** null = 服务端未给发起人，无法判定我方角色，展示中性文案。 */
    val isInitiator: Boolean? = null,
)

/**
 * 关系 tab MVP（契约 §3.5）。
 *
 * 待处理：调解邀请（§2.3-3）+ 双视角「我未提交」+ 解绑 confirm 状态。
 * 关系背景：纪念日、绑定信息（love_days）、关系画像摘要、信件收件箱入口。
 * 当前议题/共同约定/关系模式/关系脉络：阶段四占位，收敛期不建端点（无数据字段）。
 *
 * 每条数据源独立降级：任何一路失败只丢对应区块，不拖垮整页。
 */
data class RelationUiState(
    val isLoading: Boolean = true,

    /**
     * 整页加载失败（M3）：7 路数据源全失败，或关键源（home + couples/me）同时失败。
     * true 时页面显示错误 + 重试，而不是「暂无待处理事项」；部分失败仍走各源独立降级。
     */
    val loadError: Boolean = false,

    // ----- 待处理 -----
    /** §2.3-3：role=invited 的调解邀请（后端未落地 → 空列表）。 */
    val mediationInvites: List<MediationDto.MediationListItem> = emptyList(),
    /** status=one_side 且 records 里没有我的双视角事件（我还没写）。 */
    val myUnsubmittedDuals: List<DualPerspectiveDto.DualEventResponse> = emptyList(),
    val unbindStatus: UnbindStatusUi? = null,

    // ----- 关系背景 -----
    val loveDays: Int? = null,
    val partnerNickname: String? = null,
    val bindTime: String? = null,
    val upcomingAnniversary: AnniversaryDto.AnniversaryResponse? = null,
    val upcomingDaysUntil: Int? = null,
    val coupleSummary: String? = null,
    val inboxCount: Int = 0,
) {
    val pendingCount: Int
        get() = mediationInvites.size + myUnsubmittedDuals.size + (if (unbindStatus != null) 1 else 0)

    val hasBackground: Boolean
        get() = loveDays != null || upcomingAnniversary != null ||
            coupleSummary != null || bindTime != null || partnerNickname != null
}

@HiltViewModel
class RelationViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val coupleRepository: CoupleRepository,
    private val mediationRepository: MediationRepository,
    private val dualPerspectiveRepository: DualPerspectiveRepository,
    private val anniversaryRepository: AnniversaryRepository,
    private val profileRepository: ProfileRepository,
    private val letterRepository: LetterRepository,
    private val sharedApiService: SharedApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RelationUiState())
    val uiState: StateFlow<RelationUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, loadError = false) }
        viewModelScope.launch {
            // 我的 user_id 是双视角「我未提交」与解绑「谁发起」的判定基准；拿不到就各自降级。
            val myUserId = runCatching {
                sharedApiService.getCurrentUser().data?.userId
            }.getOrNull()

            val state = coroutineScope {
                val homeDeferred = async { homeRepository.getHomeData() }
                val coupleDeferred = async { coupleRepository.getCoupleInfo() }
                val invitesDeferred = async { mediationRepository.getMediationList("invited") }
                val dualsDeferred = async { loadMyUnsubmittedDuals(myUserId) }
                val anniversaryDeferred = async { anniversaryRepository.getAnniversaries() }
                val profileDeferred = async { profileRepository.getCoupleProfile() }
                val inboxDeferred = async { letterRepository.getInbox() }

                // 先拿 Result，成功/失败都要能区分——整页失败判定（M3）依赖它。
                val homeResult = homeDeferred.await()
                val coupleResult = coupleDeferred.await()
                val invitesResult = invitesDeferred.await()
                val dualsResult = dualsDeferred.await()
                val anniversaryResult = anniversaryDeferred.await()
                val profileResult = profileDeferred.await()
                val inboxResult = inboxDeferred.await()

                val home: HomeDto.HomeResponse? = homeResult.getOrNull()
                val coupleInfo: CoupleDto.CoupleRelationResponse? = coupleResult.getOrNull()
                val invites: List<MediationDto.MediationListItem> = invitesResult.getOrNull() ?: emptyList()
                val duals: List<DualPerspectiveDto.DualEventResponse> = dualsResult.getOrNull() ?: emptyList()
                val anniversaries: AnniversaryDto.AnniversaryListResponse? = anniversaryResult.getOrNull()
                val coupleSummary: String? = profileResult.getOrNull()?.summary
                val inboxCount: Int = inboxResult.getOrNull()?.total ?: 0

                val results = listOf(
                    homeResult, coupleResult, invitesResult, dualsResult,
                    anniversaryResult, profileResult, inboxResult,
                )
                // 整页失败（M3）：7 路全失败，或关键源 home + couples/me 同时失败；
                // 只要还有一路能出数据就继续独立降级渲染，不整页报错。
                val loadError = results.all { it.isFailure } ||
                    (homeResult.isFailure && coupleResult.isFailure)

                val nextAnniversary = anniversaries?.items
                    ?.let { pickUpcoming(it) }

                RelationUiState(
                    isLoading = false,
                    loadError = loadError,
                    mediationInvites = invites,
                    myUnsubmittedDuals = duals,
                    unbindStatus = coupleInfo?.unbindRequestedAt?.let { at ->
                        UnbindStatusUi(
                            requestedAt = at,
                            // M2：拿不到 myUserId 就无法判定发起方 → null（中性文案），不能当成 false
                            isInitiator = if (myUserId == null) {
                                null
                            } else {
                                coupleInfo.unbindRequestedBy?.let { by -> by == myUserId }
                            },
                        )
                    },
                    // 契约 §3.5 love_days 首选 couples/me（收敛期新增字段），未落地前回退 GET /home。
                    loveDays = coupleInfo?.loveDays ?: home?.relation?.loveDays,
                    partnerNickname = home?.relation?.partnerNickname,
                    bindTime = coupleInfo?.bindTime,
                    upcomingAnniversary = nextAnniversary?.first,
                    upcomingDaysUntil = nextAnniversary?.second,
                    coupleSummary = coupleSummary,
                    inboxCount = inboxCount,
                )
            }
            _uiState.update { state }
        }
    }

    /**
     * 「我未提交」判定：one_side = 只有一方提交；detail.records 里没有我的 user_id
     * → 伴侣已提交、等我写。detail 拉取失败的事件不进入列表（宁缺毋错）。
     * 最多查 3 条详情，避免关系 tab 打开时串行打爆请求。
     *
     * 返回 [Result]：事件列表本身拉不到才算失败（进入整页失败判定）；
     * 单条 detail 失败仍按「宁缺毋错」丢条目，不算整路失败。
     */
    private suspend fun loadMyUnsubmittedDuals(
        myUserId: Long?,
    ): Result<List<DualPerspectiveDto.DualEventResponse>> = runCatching {
        if (myUserId == null) return@runCatching emptyList()
        val events = dualPerspectiveRepository.getEvents().getOrThrow()?.items ?: emptyList()
        events
            .filter { it.status == "one_side" }
            .take(3)
            .filter { event ->
                val detail = dualPerspectiveRepository.getEventDetail(event.id).getOrNull()
                detail != null && detail.records.none { it.userId == myUserId }
            }
    }

    /**
     * 从纪念日列表挑下一次要到来的：按「今年的月日；已过则明年」滚动计算年周期。
     * 全部无法解析日期 → null（背景区块少一行，不报错）。
     */
    private fun pickUpcoming(
        items: List<AnniversaryDto.AnniversaryResponse>,
    ): Pair<AnniversaryDto.AnniversaryResponse, Int>? {
        val today = LocalDate.now()
        return items.mapNotNull { item ->
            val parsed = runCatching { LocalDate.parse(item.anniversaryDate.take(10)) }.getOrNull()
                ?: return@mapNotNull null
            val monthDay = MonthDay.from(parsed)
            var next = monthDay.atYear(today.year)
            if (next.isBefore(today)) {
                next = monthDay.atYear(today.year + 1)
            }
            item to ChronoUnit.DAYS.between(today, next).toInt()
        }.minByOrNull { it.second }
    }
}
