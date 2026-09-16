package com.couple.translator.core.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.data.repository.ProfileRepository
import com.couple.translator.core.data.repository.QuestionnaireRepository
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.service.AiStreamKeepAlive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileResultUiState(
    val profile: ProfileDto.RelationshipProfileResponse? = null,
    val dimensions: List<ProfileDto.DimensionScoreResponse> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String = "",
    // 测评选择
    val submissions: List<QuestionnaireDto.SubmissionResponse> = emptyList(),
    val selectedSubmissionId: Long = 0,
    // 分析报告（来自选中的 submission）
    val profileAnalysis: String = "",
    val dimensionAnalyses: List<QuestionnaireDto.DimensionAnalysis> = emptyList(),
    val strengths: String = "",
    val growthTips: List<String> = emptyList(),
    val communicationGuide: String = "",
    // ---- 流式生成中的中间态 ----
    /** 已发起分析生成（从发起到收尾都为 true，用它决定报告区块的渲染方式） */
    val isGenerating: Boolean = false,
    /** 正文逐字追加；done 帧到达后清空，改由 profileAnalysis 等结构化字段渲染 */
    val streamText: String = "",
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val thinkingSeconds: Int = 0,
    val isStructuring: Boolean = false,
    // ---- AI 深度画像报告（独立于量表分析，纯 Markdown 长文）----
    /** 回读到的已保存报告 */
    val reportText: String = "",
    /** 首次回读是否已完成（完成前不显示「生成报告」按钮，避免回读慢时重复生成） */
    val reportLoaded: Boolean = false,
    val isReportGenerating: Boolean = false,
    val reportStreamText: String = "",
    val isReportThinking: Boolean = false,
    val reportThinkingText: String = "",
    val reportThinkingSeconds: Int = 0,
) {

    val profileTypeName: String
        get() = when (profile?.profileType) {
            "secure" -> "安全型依恋"
            "anxious" -> "焦虑依恋型"
            "dismissive" -> "疏离回避型"
            "fearful" -> "恐惧回避型"
            else -> "未知"
        }

    val profileTypeDescription: String
        get() = when (profile?.profileType) {
            "secure" -> "你在关系中感到安全和自在，能够平衡独立与亲密。"
            "anxious" -> "你渴望亲密，有时会担心伴侣不够在乎你。"
            "dismissive" -> "你重视独立自主，有时会回避过深的情感交流。"
            "fearful" -> "你既渴望亲密又害怕受伤，在关系中常感到矛盾。"
            else -> ""
        }

    val hasAnalysis: Boolean
        get() = profileAnalysis.isNotBlank() || communicationGuide.isNotBlank() || isGenerating
}

@HiltViewModel
class ProfileResultViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val questionnaireRepository: QuestionnaireRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileResultUiState())
    val uiState: StateFlow<ProfileResultUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var stopRequested = false
    private var streamStartedAt = 0L
    private var reportJob: Job? = null
    private var reportStopRequested = false
    private var reportStartedAt = 0L

    init {
        loadSubmissions()
        loadSavedReport()
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    /**
     * 加载所有测评提交记录
     */
    private fun loadSubmissions() {
        viewModelScope.launch {
            questionnaireRepository.getSubmissionHistory().fold(
                onSuccess = { submissions ->
                    _uiState.update { it.copy(submissions = submissions) }
                    // 默认选中最新的
                    val latest = submissions.firstOrNull()
                    if (latest != null) {
                        selectSubmission(latest.id)
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    fun refresh() {
        val currentSelectedId = _uiState.value.selectedSubmissionId
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = "") }
            questionnaireRepository.getSubmissionHistory().fold(
                onSuccess = { submissions ->
                    _uiState.update { it.copy(submissions = submissions) }
                    if (currentSelectedId > 0 && submissions.any { it.id == currentSelectedId }) {
                        refreshProfileFromSubmission(currentSelectedId)
                    } else {
                        val latest = submissions.firstOrNull()
                        if (latest != null) {
                            refreshProfileFromSubmission(latest.id)
                        } else {
                            _uiState.update { it.copy(isRefreshing = false) }
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isRefreshing = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    private fun refreshProfileFromSubmission(submissionId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedSubmissionId = submissionId) }
            questionnaireRepository.getSubmissionDetail(submissionId).fold(
                onSuccess = { submission ->
                    buildProfileFromSubmission(submission, isRefreshing = true)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isRefreshing = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    /**
     * 选择某次测评结果
     */
    fun selectSubmission(submissionId: Long) {
        if (_uiState.value.selectedSubmissionId == submissionId && _uiState.value.profile != null) return
        _uiState.update { it.copy(selectedSubmissionId = submissionId, isLoading = true) }
        loadProfileFromSubmission(submissionId)
    }

    private fun loadProfileFromSubmission(submissionId: Long) {
        viewModelScope.launch {
            questionnaireRepository.getSubmissionDetail(submissionId).fold(
                onSuccess = { submission ->
                    buildProfileFromSubmission(submission, isLoading = true)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "加载失败") }
                },
            )
        }
    }

    /**
     * 从 submission 构建 profile 并更新 UI。
     * 如果结构化分析字段为空，尝试触发 AI 分析生成。
     */
    private fun buildProfileFromSubmission(
        submission: QuestionnaireDto.SubmissionResponse,
        isLoading: Boolean = false,
        isRefreshing: Boolean = false,
    ) {
        val profile = ProfileDto.RelationshipProfileResponse(
            id = 0,
            userId = 0,
            profileType = submission.profileType ?: "",
            confidence = 0f,
            summary = submission.profileSummary,
            createdAt = submission.createdAt,
        )
        val dimensions = (submission.dimensionScores ?: emptyMap()).map { (key, score) ->
            ProfileDto.DimensionScoreResponse(
                dimensionKey = key,
                score = score,
            )
        }

        val hasStructured = !submission.profileAnalysis.isNullOrBlank()
        val hasLegacy = !submission.analysisText.isNullOrBlank()

        if (hasStructured) {
            // 有结构化分析，直接显示
            _uiState.update {
                it.copy(
                    profile = profile,
                    dimensions = dimensions,
                    profileAnalysis = submission.profileAnalysis ?: "",
                    dimensionAnalyses = submission.dimensionAnalyses ?: emptyList(),
                    strengths = submission.strengths ?: "",
                    growthTips = submission.growthTips ?: emptyList(),
                    communicationGuide = submission.communicationGuide ?: "",
                    isLoading = false,
                    isRefreshing = false,
                )
            }
        } else if (hasLegacy) {
            // 有旧格式分析文本，先展示它，同时补一份结构化分析
            _uiState.update {
                it.copy(
                    profile = profile,
                    dimensions = dimensions,
                    profileAnalysis = submission.analysisText ?: "",
                    isLoading = false,
                    isRefreshing = false,
                )
            }
            // 后台触发结构化分析
            triggerAnalysis(submission.questionnaireId, profile, dimensions)
        } else {
            // 无分析数据，显示基础画像并触发 AI 分析
            _uiState.update {
                it.copy(
                    profile = profile,
                    dimensions = dimensions,
                    isLoading = false,
                    isRefreshing = false,
                )
            }
            triggerAnalysis(submission.questionnaireId, profile, dimensions)
        }
    }

    /** 用户点「停止生成」：收敛本地状态，服务端靠连接断开自己止血。 */
    fun stopAnalysis() {
        if (!_uiState.value.isGenerating) return
        stopRequested = true
        streamJob?.cancel()
        streamJob = null
        AiStreamKeepAlive.stop(appContext)
        _uiState.update { it.copy(isGenerating = false, isThinking = false, isStructuring = false) }
    }

    /**
     * 触发 AI 分析生成（流式）。
     *
     * 为什么画像页也流式：这份报告是用户平时回来翻阅的长文，等 20 秒一次性
     * 蹦出来和边想边出，感受差得远；而且思考面板能让「它到底在干嘛」可见。
     */
    private fun triggerAnalysis(
        questionnaireId: Long,
        profile: ProfileDto.RelationshipProfileResponse,
        dimensions: List<ProfileDto.DimensionScoreResponse>,
    ) {
        if (questionnaireId <= 0) return
        streamJob?.cancel()
        stopRequested = false
        streamStartedAt = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                profile = profile,
                dimensions = dimensions,
                isGenerating = true,
                isThinking = true,
                isStructuring = false,
                streamText = "",
                thinkingText = "",
                thinkingSeconds = 0,
            )
        }

        // 生成期间挂前台服务保活：退后台进程不被冻结、网络不受限
        AiStreamKeepAlive.start(appContext)

        streamJob = viewModelScope.launch {
            questionnaireRepository.analyzeQuestionnaireStream(questionnaireId).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> Unit

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
                        // 生成失败不清空已有内容：基础画像与旧文本还在页面上，
                        // 用户至少能看到测评维度，而不是一片空白。
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                isThinking = false,
                                isStructuring = false,
                                streamText = "",
                                isRefreshing = false,
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时状态必须收敛
            if (!stopRequested && _uiState.value.isGenerating) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        isThinking = false,
                        isStructuring = false,
                        isRefreshing = false,
                    )
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /** done 帧收尾：结构化字段齐全就填卡片，转不出来则退回纯正文。 */
    private fun finishStream(ev: GenerationStreamEvent.Finished) {
        val parsed = questionnaireRepository.parseAnalysis(ev.structured)
        _uiState.update { state ->
            state.copy(
                isGenerating = false,
                isThinking = false,
                isStructuring = false,
                isRefreshing = false,
                streamText = "",
                profileAnalysis = parsed?.profileAnalysis ?: ev.content.ifBlank { state.profileAnalysis },
                dimensionAnalyses = parsed?.dimensionAnalyses ?: state.dimensionAnalyses,
                strengths = parsed?.strengths ?: state.strengths,
                growthTips = parsed?.growthTips ?: state.growthTips,
                communicationGuide = parsed?.communicationGuide ?: state.communicationGuide,
            )
        }
        AiStreamKeepAlive.stop(appContext)
    }

    /** 从发问算起的等待秒数，至少 1 秒（避免显示「已深度思考 0 秒」）。 */
    private fun elapsedSeconds(): Int {
        if (streamStartedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - streamStartedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
    }

    // ==================== AI 深度画像报告 ====================

    /** 进页面先回读上次保存的报告（ai_generation 覆盖式只留最新一条）。 */
    private fun loadSavedReport() {
        viewModelScope.launch {
            val result = profileRepository.getSavedProfileReport()
            val payload = result.getOrNull()
            _uiState.update {
                it.copy(
                    reportText = payload?.content?.trim().orEmpty(),
                    reportLoaded = true,
                )
            }
        }
    }

    /** 用户点「停止生成」：收敛本地状态，服务端靠连接断开自己止血。 */
    fun stopProfileReport() {
        if (!_uiState.value.isReportGenerating) return
        reportStopRequested = true
        reportJob?.cancel()
        reportJob = null
        AiStreamKeepAlive.stop(appContext)
        _uiState.update {
            it.copy(
                isReportGenerating = false,
                isReportThinking = false,
                // 已流出来的内容保留展示，不算白生成
                reportText = it.reportStreamText.trim(),
                reportStreamText = "",
            )
        }
    }

    /** 生成（或重新生成）AI 深度画像报告。 */
    fun startProfileReport() {
        reportJob?.cancel()
        reportStopRequested = false
        reportStartedAt = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                isReportGenerating = true,
                isReportThinking = true,
                reportStreamText = "",
                reportThinkingText = "",
                reportThinkingSeconds = 0,
                error = "",
            )
        }

        AiStreamKeepAlive.start(appContext)

        reportJob = viewModelScope.launch {
            profileRepository.profileReportStream().collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> Unit

                    is GenerationStreamEvent.Thinking -> _uiState.update {
                        it.copy(reportThinkingText = it.reportThinkingText + ev.content)
                    }

                    is GenerationStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            reportStreamText = it.reportStreamText + ev.content,
                            isReportThinking = false,
                            reportThinkingSeconds = it.reportThinkingSeconds
                                .takeIf { s -> s > 0 } ?: reportElapsedSeconds(),
                        )
                    }

                    is GenerationStreamEvent.Structuring -> Unit // 纯 Markdown，无结构化阶段

                    is GenerationStreamEvent.Finished -> {
                        _uiState.update { state ->
                            state.copy(
                                isReportGenerating = false,
                                isReportThinking = false,
                                reportStreamText = "",
                                reportText = ev.content.trim().ifBlank { state.reportText },
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }

                    is GenerationStreamEvent.Failure -> if (!reportStopRequested) {
                        _uiState.update {
                            it.copy(
                                isReportGenerating = false,
                                isReportThinking = false,
                                error = ev.message,
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时状态必须收敛
            if (!reportStopRequested && _uiState.value.isReportGenerating) {
                _uiState.update {
                    it.copy(
                        isReportGenerating = false,
                        isReportThinking = false,
                        reportText = it.reportStreamText.trim().ifBlank { it.reportText },
                        reportStreamText = "",
                    )
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    private fun reportElapsedSeconds(): Int {
        if (reportStartedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - reportStartedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        reportJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }
}
