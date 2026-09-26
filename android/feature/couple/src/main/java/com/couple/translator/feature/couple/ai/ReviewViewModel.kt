package com.couple.translator.feature.couple.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 复盘页的两种形态（§8.7）。由 [ReviewUiState.mode] 单点裁决，UI 不再各写一套 if。 */
enum class ReviewMode {
    /** 输入一次经过 → 发起复盘 */
    INPUT,

    /** 看某一次复盘的结果（刚生成完 / 从历史点进来） */
    RESULT,
}

/**
 * 「重新复盘一次」时要把输入恢复成什么。
 *
 * 只放两件事（输入框初值走 `remember(attempt)`，不回写 UiState，避免每次
 * 输入都触发一次全页重组）；[recordId] 非空表示这次是基于**哪一条历史**
 * 重来的，新的一次仍会独立留档，覆盖的说法在这里是被禁止的。
 */
data class ReviewReplay(val description: String, val context: String, val recordId: Long?)

data class ReviewUiState(
    /** 用户输入的事件经过 */
    val description: String = "",
    val context: String = "",
    /** 事件发生时间（选填，`YYYY-MM-DD`） */
    val eventTime: String = "",
    val isLoading: Boolean = false,
    val error: String = "",
    // ---- 展示形态（§8.7）----
    val mode: ReviewMode = ReviewMode.INPUT,
    /** 当前展示的那一条留档 id；0 = 还没落档（流式进行中） */
    val reviewId: Long = 0,
    val meta: AiDto.ReviewRecord? = null,
    // ---- 结构化复盘结果（来自 ai_generation，退出重进可回读）----
    val review: AiDto.ReviewResult? = null,
    val savedContent: String = "",
    // ---- 后续结果 ----
    val outcome: String = "",
    val isSubmittingOutcome: Boolean = false,
    val message: String = "",
    /** 一次性导航意图：UI 看到 true 就跳历史页并调 [ReviewViewModel.consumeNavigateToHistory] */
    val navigateToHistory: Boolean = false,
    /** 最近一次复盘（无参入口顶部那条入口；null = 还没复盘过） */
    val latest: AiDto.ReviewHistoryItem? = null,
    // ---- 流式生成中的中间态 ----
    val isGenerating: Boolean = false,
    val streamText: String = "",
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val thinkingSeconds: Int = 0,
    val isStructuring: Boolean = false,
    /** 输入框初值快照 + 版本号：每次「重新复盘一次」自增，UI 据此重置输入框 */
    val replay: ReviewReplay = ReviewReplay("", "", null),
    val replayAttempt: Int = 0,
) {
    /** 已经有可展示的复盘内容（回读到的或在生成的） */
    val hasReview: Boolean
        get() = review != null || savedContent.isNotBlank() || isGenerating

    /** 是否要渲染输入表单（§8.7：结果态下不再把输入框藏死，随时可重新复盘） */
    val showForm: Boolean
        get() = mode == ReviewMode.INPUT && !isGenerating

    /** 当前这条复盘是否已有后续结果（有则展示回看，无则可回填） */
    val hasOutcome: Boolean
        get() = meta?.outcome?.isNotBlank() == true
}

/**
 * 关系复盘 ViewModel。
 *
 * 对应功能设计 六.9：输入一次争吵 / 冷战 / 和好的经过，AI 输出触发点、
 * 双方真实需求、误解发生处、升级冲突的话语、降温话术、下次可提前使用的表达。
 *
 * 整改 §8.7 修掉的两个真实缺陷：
 * 1. **覆盖式只存最新一次**：回读原来打 `ai_generation`（同用户同 kind 只有一行），
 *    历史根本不存在。现在每次复盘都在 `ai_relationship_review` 追加留档，
 *    页面按 id 回读，历史列表另开一页。
 * 2. **「重新复盘一次」不真的重来**：原实现只清空两个输入框（用户还看不见），
 *    结果卡片原地不动。现在 [restartReview] 显式清结果 + 恢复输入 + 自增
 *    [ReviewUiState.replayAttempt] 让输入框真的被重置。
 *
 * 三条与其它流式页面一致的硬约束：
 * 1. 解码一律复用 core 的 [GenerationStreamEvent] 基建，不自己解析 SSE；
 * 2. 生成期间挂 [AiStreamKeepAlive] 前台服务，退后台进程不被冻结；
 * 3. 所有 isGenerating → false 的路径（停止 / 失败 / done / 兜底 / onCleared）
 *    都必须 stop 保活，漏一条会让前台服务常驻。
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState(isLoading = true))
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var stopRequested = false
    private var streamStartedAt = 0L
    private var generationId: Long = 0

    /**
     * 页面初始化。契约（§8.7）要求两个入口都能用，参数由 UI 层显式喂进来：
     * - 不调 = 从军师行动行进来，回读最近一条；
     * - 传 id = 从历史列表 / 首页回访卡进来，直接看那一条。
     *
     * 刻意**不读 SavedStateHandle**：同一 composable 的多个目的地共享同一个
     * handle，带参版本会用 query 参数把上一次的值留在里面（首页回访卡曾因此
     * 打开上一条复盘）。UI 传值则永远由当前 back stack entry 决定，不会被
     * handle 复用污染。
     */
    fun initialize(reviewId: Long) {
        if (initialized) return
        initialized = true
        if (reviewId > 0L) {
            loadDetail(reviewId)
        } else {
            loadLatestHint()
        }
    }

    private var initialized = false

    /**
     * 无参入口：给输入表单，并在有历史时挂一条「最近一次复盘」的入口。
     *
     * 为什么**不**默认展示最近一次结果：这个入口的真话是「我要记一次新的」
     * （军师行动行「记录为关系复盘」、历史页「写一次新的复盘」都走这里）。
     * 一进来就把上次的卡片铺满屏，用户得先把它关掉才能写字。想回看时，
     * 顶部那条提示与右上角历史按钮都只要一次点击。
     */
    private fun loadLatestHint() {
        viewModelScope.launch {
            aiRepository.reviewHistory(page = 1, pageSize = 1).fold(
                onSuccess = { resp ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            mode = ReviewMode.INPUT,
                            latest = resp?.items?.firstOrNull(),
                        )
                    }
                },
                onFailure = {
                    // 读不到历史不该拦住写新的——空表单照常可用，历史页自己会报错
                    _uiState.update {
                        it.copy(isLoading = false, mode = ReviewMode.INPUT, latest = null)
                    }
                },
            )
        }
    }

    /** 读一条留档并渲染成结果态。 */
    fun loadDetail(reviewId: Long) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            aiRepository.reviewDetail(reviewId).fold(
                onSuccess = { record ->
                    if (record == null) {
                        _uiState.update { it.copy(isLoading = false, mode = ReviewMode.INPUT) }
                        return@fold
                    }
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            mode = if (record.hasResult) ReviewMode.RESULT else ReviewMode.INPUT,
                            reviewId = record.reviewId,
                            meta = record,
                            review = aiRepository.reviewResultOf(record),
                            savedContent = if (aiRepository.reviewResultOf(record) == null) {
                                record.content
                            } else {
                                ""
                            },
                            outcome = record.outcome.orEmpty(),
                            // 历史回看时把「当时说了什么」也放回输入区（表单可展开）
                            replay = ReviewReplay(
                                description = record.description,
                                context = record.context,
                                recordId = record.reviewId,
                            ),
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "复盘记录加载失败")
                    }
                },
            )
        }
    }

    /** 打开历史列表（§8.7「可回看历史」的入口）。 */
    fun openHistory() {
        // 列表在独立页（ReviewHistoryScreen），这里只上报意图——
        // 导航归 UI 层，VM 不持有 NavController，也不该替页面的返回语义做主。
        _uiState.update { it.copy(navigateToHistory = true) }
    }

    fun consumeNavigateToHistory() {
        _uiState.update { it.copy(navigateToHistory = false) }
    }

    /** 回输入表单（新的一次复盘，空白开始）。历史仍在，不会被清。 */
    fun backToInput() {
        _uiState.update {
            it.copy(
                mode = ReviewMode.INPUT,
                review = null,
                savedContent = "",
                meta = null,
                reviewId = 0,
                streamText = "",
                thinkingText = "",
                outcome = "",
            )
        }
    }

    fun onDescriptionChange(value: String) {
        _uiState.update { it.copy(description = value) }
    }

    fun onContextChange(value: String) {
        _uiState.update { it.copy(context = value) }
    }

    fun onEventTimeChange(value: String) {
        _uiState.update { it.copy(eventTime = value) }
    }

    fun onOutcomeChange(value: String) {
        _uiState.update { it.copy(outcome = value) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = "") }
    }

    /**
     * 「重新复盘一次」（§8.7 硬要求：真正清理结果并恢复输入状态）。
     *
     * 恢复的是**上一条的原文**——用户多半是想改改细节重来，而不是从空白开始；
     * 输入框靠 [ReviewUiState.replayAttempt] 自增被强制重置，不依赖它自己变化。
     * 结果区被清空（review/savedContent/meta 都置空），不会留着旧卡片骗人。
     */
    fun restartReview() {
        val state = _uiState.value
        if (state.isGenerating) return
        val seed = ReviewReplay(
            description = state.meta?.description ?: state.description,
            context = state.meta?.context ?: state.context,
            recordId = state.reviewId.takeIf { it > 0L },
        )
        _uiState.update {
            it.copy(
                mode = ReviewMode.INPUT,
                description = seed.description,
                context = seed.context,
                review = null,
                savedContent = "",
                meta = null,
                reviewId = 0,
                streamText = "",
                thinkingText = "",
                thinkingSeconds = 0,
                outcome = "",
                replay = seed,
                replayAttempt = it.replayAttempt + 1,
                error = "",
            )
        }
    }

    /** 用户点「停止生成」：收敛本地状态，服务端靠连接断开自己止血。 */
    fun stopReview() {
        if (!_uiState.value.isGenerating) return
        stopRequested = true
        streamJob?.cancel()
        streamJob = null
        AiStreamKeepAlive.stop(appContext)
        _uiState.update { it.copy(isGenerating = false, isThinking = false, isStructuring = false) }
    }

    /** 开始/重新开始一次复盘（每次都追加留档，绝不覆盖历史）。 */
    fun startReview() {
        val state = _uiState.value
        val description = state.description.trim()
        if (description.isBlank()) {
            _uiState.update { it.copy(error = "先说说发生了什么吧") }
            return
        }
        streamJob?.cancel()
        stopRequested = false
        streamStartedAt = System.currentTimeMillis()
        generationId = 0

        _uiState.update {
            it.copy(
                isGenerating = true,
                isThinking = true,
                isStructuring = false,
                streamText = "",
                thinkingText = "",
                thinkingSeconds = 0,
                review = null,
                savedContent = "",
                meta = null,
                reviewId = 0,
                outcome = "",
                error = "",
            )
        }

        AiStreamKeepAlive.start(appContext)

        streamJob = viewModelScope.launch {
            aiRepository.reviewStream(
                description,
                state.context.trim().ifBlank { null },
                state.eventTime.trim().ifBlank { null },
            ).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> generationId = ev.generationId

                    is GenerationStreamEvent.Thinking -> _uiState.update {
                        it.copy(thinkingText = it.thinkingText + ev.content)
                    }

                    is GenerationStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            streamText = it.streamText + ev.content,
                            isThinking = false,
                            isStructuring = false,
                            thinkingSeconds = it.thinkingSeconds
                                .takeIf { s -> s > 0 } ?: elapsedSeconds(),
                        )
                    }

                    is GenerationStreamEvent.Structuring -> _uiState.update {
                        it.copy(isStructuring = true, isThinking = false)
                    }

                    is GenerationStreamEvent.Finished -> finishStream(ev)

                    is GenerationStreamEvent.Failure -> if (!stopRequested) {
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                isThinking = false,
                                isStructuring = false,
                                error = ev.message,
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时状态必须收敛
            if (!stopRequested && _uiState.value.isGenerating) {
                _uiState.update {
                    it.copy(isGenerating = false, isThinking = false, isStructuring = false)
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /**
     * done 帧收尾：结构化字段齐全就填卡片，转不出来则退回纯正文
     * （模型偶尔不吐 JSON，此时正文还在，比一片空白强）。
     *
     * 收尾后再读一次**留档**：服务端在 done 之前已经写完（`_save` → `on_saved`
     * 的顺序保证六项字段与回访时间都已落库），所以这次回读能拿到 review_id 与
     * 后续结果所需的元信息——历史回看、回访卡都靠它。
     */
    private fun finishStream(ev: GenerationStreamEvent.Finished) {
        val parsed = aiRepository.parseReview(ev.structured)
        _uiState.update { state ->
            state.copy(
                isGenerating = false,
                isThinking = false,
                isStructuring = false,
                streamText = "",
                mode = ReviewMode.RESULT,
                review = parsed,
                savedContent = if (parsed == null) ev.content else "",
            )
        }
        AiStreamKeepAlive.stop(appContext)
        refreshMeta()
    }

    /** 拉回刚生成那条留档的元信息（review_id / 发生时间 / 回访时间）。 */
    private fun refreshMeta() {
        viewModelScope.launch {
            val latest = aiRepository.reviewHistory(page = 1, pageSize = 1)
                .getOrNull()?.items?.firstOrNull() ?: return@launch
            aiRepository.reviewDetail(latest.reviewId).onSuccess { record ->
                if (record == null) return@onSuccess
                _uiState.update { it.copy(reviewId = record.reviewId, meta = record) }
            }
        }
    }

    /** §8.7：回填「后来怎么样了」。提交成功后回访任务随之消失。 */
    fun submitOutcome() {
        val state = _uiState.value
        val reviewId = state.reviewId
        if (reviewId <= 0L) {
            _uiState.update { it.copy(error = "这条复盘还没落档，稍后再试") }
            return
        }
        val text = state.outcome.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(error = "说说后来怎么样了") }
            return
        }
        _uiState.update { it.copy(isSubmittingOutcome = true, error = "") }
        viewModelScope.launch {
            aiRepository.submitReviewOutcome(reviewId, text).fold(
                onSuccess = { record ->
                    _uiState.update {
                        it.copy(
                            isSubmittingOutcome = false,
                            meta = record ?: it.meta,
                            outcome = record?.outcome.orEmpty(),
                            message = "已记录结果，谢谢反馈",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isSubmittingOutcome = false,
                            error = e.message ?: "结果提交失败",
                        )
                    }
                },
            )
        }
    }

    /** 从发问算起的等待秒数，至少 1 秒（避免显示「已深度思考 0 秒」）。 */
    private fun elapsedSeconds(): Int {
        if (streamStartedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - streamStartedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }
}
