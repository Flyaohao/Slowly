package com.couple.translator.feature.couple.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.repository.HomeRepository
import com.couple.translator.feature.couple.data.repository.AnniversaryRepository
import com.couple.translator.feature.couple.data.repository.LetterRepository
import com.couple.translator.feature.couple.data.repository.RelationshipEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 共同时间线的单条记录（「我们的空间」核心区块，2026-09-29 重构）。
 *
 * 时间线是**共同经历的倒序混流**（不是聊天流）：信件 / 观点 / 纪念日 / 关系事件
 * 四类内容按发生时间归并，回答「我们一起经历了什么」。
 */
data class TimelineEntry(
    /** 稳定唯一键（多源可能同 id，必须带前缀）：LazyColumn key 用。 */
    val key: String,
    val kind: TimelineKind,
    val title: String,
    val excerpt: String?,
    /** 原始时间戳（ISO），用于排序与展示；为 null 的条目排在最后。 */
    val occurredAt: String?,
    /** 点击跳转的路由；null = 不可点。 */
    val route: String?,
)

enum class TimelineKind {
    /** 信件（信箱） */
    Letter,

    /** 观点（内部数据仍是 diary_entry） */
    Viewpoint,

    /** 纪念日（里程碑节点） */
    Anniversary,

    /** 关系事件（里程碑节点） */
    Event,
}

data class SpaceTimelineUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val entries: List<TimelineEntry> = emptyList(),
    /** 首次加载且全部数据源都失败（页面级错误态）；单源失败只丢对应类型。 */
    val loadError: Boolean = false,
)

/**
 * 「共同时间线」VM（2026-09-29 新增，随「我们的空间」重构）。
 *
 * 设计（用户拍板 D4）：**前端多源归并**，不新增后端接口——四个源各取一页，
 * 在客户端按时间倒序合并。任一路失败只丢该类内容、不拖垮整页（与
 * 「单源独立降级」同款约定）；只有全部失败才报整页错误。
 *
 * 四类源：
 * - 信件：`GET /couple/letters`（LetterRepository）
 * - 观点：`GET /home` 响应里的 `recent_diaries`（HomeRepository）——
 *   **注意**：观点（diary）的完整列表接口挂在 feature:single，而
 *   feature:couple **不能**依赖 feature:single（模块边界，见 build.gradle.kts）。
 *   若在此处直接注入 SingleApiService 会导致 kapt 解析失败、整个模块编译不过。
 *   因此改取 `/home` 聚合里已经带回的 recent_diaries（同一份 diary_entry 数据），
 *   零新增接口、零模块依赖变更。
 * - 纪念日：`GET /anniversaries`（AnniversaryRepository）
 * - 关系事件：`GET /relationship-events`（RelationshipEventRepository）
 *
 * 降级语义（不改）：读不到 ≠ 没有——单源失败时保留上一次成功的内容，
 * 不把「读不到」渲染成「空时间线」。
 */
@HiltViewModel
class SpaceTimelineViewModel @Inject constructor(
    private val letterRepository: LetterRepository,
    private val homeRepository: HomeRepository,
    private val anniversaryRepository: AnniversaryRepository,
    private val relationshipEventRepository: RelationshipEventRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpaceTimelineUiState())
    val uiState: StateFlow<SpaceTimelineUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun refresh() = load(isRefresh = true)

    /**
     * 拉取并归并四个源。
     *
     * [isRefresh] = true 时不清空已有内容（回前台 / 下拉刷新时页面原地更新，
     * 不闪骨架）。全源失败时沿用上一次成功的结果，避免「网络抖一下时间线就空了」。
     */
    fun load(isRefresh: Boolean = false) {
        if (isRefresh && _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = !isRefresh, isRefreshing = isRefresh) }

        viewModelScope.launch {
            val result = coroutineScope {
                // 四路并发，全部用 runCatching 包住：网络异常直接抛，
                // 不能让一路异常把整页协程打挂。
                val lettersDeferred = async {
                    runCatching { letterRepository.getLetters() }.getOrElse { Result.failure(it) }
                }
                val homeDeferred = async {
                    runCatching { homeRepository.getHomeData() }.getOrElse { Result.failure(it) }
                }
                val anniversariesDeferred = async {
                    runCatching { anniversaryRepository.getAnniversaries() }.getOrElse { Result.failure(it) }
                }
                val eventsDeferred = async {
                    runCatching { relationshipEventRepository.listEvents() }.getOrElse { Result.failure(it) }
                }

                val letters = lettersDeferred.await().getOrNull()
                // 观点：/home 聚合里的 recent_diaries（见类注释的模块边界说明）
                val home = homeDeferred.await().getOrNull()
                val diaries = home?.recentDiaries
                val anniversaries = anniversariesDeferred.await().getOrNull()
                val events = eventsDeferred.await().getOrNull()

                // 失败判定：Result 未 success 或 data 为 null 都算这一路没拿到。
                val lettersFailed = letters == null
                val diariesFailed = home == null
                val anniversariesFailed = anniversaries == null
                val eventsFailed = events == null

                val allFailed = lettersFailed && diariesFailed &&
                    anniversariesFailed && eventsFailed

                val entries = buildList {
                    letters?.items?.forEach { letter ->
                        // 草稿不进时间线（还没发生）；只收已发出的信。
                        if (letter.status == "draft") return@forEach
                        add(
                            TimelineEntry(
                                key = "letter-${letter.id}",
                                kind = TimelineKind.Letter,
                                title = letter.title?.takeIf { it.isNotBlank() } ?: "一封信",
                                excerpt = letter.content?.replace("\n", " ")?.take(60),
                                occurredAt = letter.sendTime ?: letter.createdAt,
                                route = "letter_detail/${letter.id}",
                            ),
                        )
                    }
                    diaries?.forEach { diary ->
                        add(
                            TimelineEntry(
                                key = "viewpoint-${diary.id}",
                                kind = TimelineKind.Viewpoint,
                                title = diary.title?.takeIf { it.isNotBlank() } ?: "一个观点",
                                excerpt = diary.mood?.takeIf { it.isNotBlank() },
                                occurredAt = diary.createdAt,
                                route = "diary_detail/${diary.id}",
                            ),
                        )
                    }
                    anniversaries?.items?.forEach { anniversary ->
                        add(
                            TimelineEntry(
                                key = "anniv-${anniversary.id}",
                                kind = TimelineKind.Anniversary,
                                title = anniversary.title,
                                excerpt = anniversary.description?.takeIf { it.isNotBlank() },
                                occurredAt = anniversary.anniversaryDate,
                                route = "anniversary_list",
                            ),
                        )
                    }
                    events?.items?.forEach { event ->
                        add(
                            TimelineEntry(
                                key = "event-${event.id}",
                                kind = TimelineKind.Event,
                                title = event.title,
                                excerpt = event.description?.takeIf { it.isNotBlank() },
                                occurredAt = event.eventTime,
                                route = "relationship_event",
                            ),
                        )
                    }
                }.sortedWith(
                    // 倒序：最近的在前。无时间戳的沉底（保持稳定）。
                    compareByDescending<TimelineEntry> { it.occurredAt != null }
                        .thenByDescending { it.occurredAt },
                )

                entries to allFailed
            }

            val (entries, allFailed) = result
            _uiState.update { prev ->
                if (allFailed) {
                    // 全源失败：保留上一次成功的内容（若有）；首次加载才显示错误态。
                    prev.copy(
                        isLoading = false,
                        isRefreshing = false,
                        loadError = prev.entries.isEmpty(),
                    )
                } else {
                    prev.copy(
                        isLoading = false,
                        isRefreshing = false,
                        loadError = false,
                        entries = entries,
                    )
                }
            }
        }
    }
}
