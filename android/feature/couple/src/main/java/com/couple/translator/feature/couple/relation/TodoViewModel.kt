package com.couple.translator.feature.couple.relation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.CoupleDto
import com.couple.translator.core.network.SharedApiService
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.repository.CoupleRepository
import com.couple.translator.feature.couple.data.repository.DualPerspectiveRepository
import com.couple.translator.feature.couple.data.repository.MediationRepository
import com.couple.translator.feature.couple.data.repository.MediationRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * 2026-09-27 关系页改版（用户裁决 ①A②A④A）：
 *
 * 调解邀请 / 双视角「我未提交」/ 解绑确认 三类待办从关系页迁出，
 * 收敛为侧边栏「待办」条目（角标 = 待办数，为 0 时仍显示条目、不显示角标）
 * 与独立的待办列表页。本 VM 是这三类数据的**唯一**加载方：
 * CoupleShell（角标）与 TodoListScreen（列表）共用同一个实例。
 *
 * 2026-09-29：原 RelationViewModel 随关系页删除（[UnbindStatusUi] 迁入本文件），
 * 待办数据源与角标口径不变。
 */
data class TodoUiState(
    val isLoading: Boolean = true,
    val pendingReliable: Boolean = true,
    /** §2.3-3：role=invited 的调解邀请。 */
    val mediationInvites: List<MediationDto.MediationListItem> = emptyList(),
    /** status=one_side 且 records 里没有我的双视角事件（我还没写）。 */
    val myUnsubmittedDuals: List<DualPerspectiveDto.DualEventResponse> = emptyList(),
    val unbindStatus: UnbindStatusUi? = null,
    /**
     * 2026-09-28 共同调解室（D-IA：双方强提醒）：进行中的房间数
     * （active/settling/settlement_ready）。任一方开房，两端都进待办。
     */
    val mediationRoomActive: Int = 0,
) {
    val pendingCount: Int
        get() = mediationInvites.size + myUnsubmittedDuals.size +
            (if (unbindStatus != null) 1 else 0) + mediationRoomActive
}

@HiltViewModel
class TodoViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
    private val mediationRepository: MediationRepository,
    private val dualPerspectiveRepository: DualPerspectiveRepository,
    private val mediationRoomRepository: MediationRoomRepository,
    private val sharedApiService: SharedApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoUiState())
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * 刷新三类待办。已是最新数据时重复调用代价是 4 路轻量请求
     * （me / invited 列表 / 事件列表 + 至多 3 条详情），CoupleShell 在
     * ON_RESUME 时调用以保证角标不滞后。
     */
    fun load() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val myUserId = runCatching {
                sharedApiService.getCurrentUser().data?.userId
            }.getOrNull()

            val state = coroutineScope {
                val coupleDeferred = async { coupleRepository.getCoupleInfo() }
                val invitesDeferred = async { mediationRepository.getMediationList("invited") }
                val dualsDeferred = async { loadMyUnsubmittedDuals(myUserId) }
                val roomsDeferred = async { mediationRoomRepository.listRooms() }

                val coupleResult = coupleDeferred.await()
                val invitesResult = invitesDeferred.await()
                val dualsResult = dualsDeferred.await()
                val roomsResult = roomsDeferred.await()

                val coupleInfo: CoupleDto.CoupleRelationResponse? = coupleResult.getOrNull()

                val pendingReliable = myUserId != null &&
                    invitesResult.isSuccess &&
                    dualsResult.isSuccess &&
                    roomsResult.isSuccess &&
                    coupleResult.isSuccess

                TodoUiState(
                    isLoading = false,
                    pendingReliable = pendingReliable,
                    mediationInvites = invitesResult.getOrNull() ?: emptyList(),
                    myUnsubmittedDuals = dualsResult.getOrNull() ?: emptyList(),
                    mediationRoomActive = roomsResult.getOrNull()?.activeCount ?: 0,
                    unbindStatus = coupleInfo?.unbindRequestedAt?.let { at ->
                        UnbindStatusUi(
                            requestedAt = at,
                            // 拿不到发起人就给 null（中性文案），不能当成 false
                            isInitiator = if (myUserId == null) {
                                null
                            } else {
                                coupleInfo.unbindRequestedBy?.let { by -> by == myUserId }
                            },
                        )
                    },
                )
            }
            _uiState.update { state }
        }
    }

    /**
     * 「我未提交」判定：one_side = 只有一方提交；detail.records 里没有我的 user_id
     * → 伴侣已提交、等我写。detail 拉取失败的事件不进入列表（宁缺毋错）。
     * 最多查 3 条详情，避免角标刷新串行打爆请求。
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
}
