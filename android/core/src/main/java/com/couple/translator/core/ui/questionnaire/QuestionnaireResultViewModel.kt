package com.couple.translator.core.ui.questionnaire

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.QuestionnaireDto
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

data class QuestionnaireResultUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAnalyzing: Boolean = false,
    val analysis: QuestionnaireDto.AnalysisResponse? = null,
    val error: String = "",
    val coupleProfileReady: Boolean = false,
    // ---- 流式生成中的中间态 ----
    /** 正文逐字追加，done 帧到达后换成结构化卡片 */
    val streamText: String = "",
    /** 思考面板：正文一开始就转成折叠态 */
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val thinkingSeconds: Int = 0,
    /** 正文已完、正在生成结构化 JSON —— 这段静默期要明确告诉用户 */
    val isStructuring: Boolean = false,
) {
    /** 是否需要展示流式过程视图（而不是转圈） */
    val isStreaming: Boolean
        get() = isAnalyzing && (streamText.isNotEmpty() || thinkingText.isNotEmpty() || isThinking)
}

@HiltViewModel
class QuestionnaireResultViewModel @Inject constructor(
    private val questionnaireRepository: QuestionnaireRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuestionnaireResultUiState())
    val uiState: StateFlow<QuestionnaireResultUiState> = _uiState.asStateFlow()

    private var questionnaireId: Long = 0
    private var streamJob: Job? = null
    private var stopRequested = false
    private var streamStartedAt = 0L

    fun setCoupleProfileReady(ready: Boolean) {
        _uiState.update { it.copy(coupleProfileReady = ready) }
    }

    fun loadAnalysis(qId: Long) {
        questionnaireId = qId
        startAnalysis(qId)
    }

    fun retry() {
        startAnalysis(questionnaireId)
    }

    fun refresh() {
        if (questionnaireId <= 0) return
        // 下拉动画一直转到分析结束：中途收起会让人以为"刷新失败了"
        _uiState.update { it.copy(isRefreshing = true) }
        startAnalysis(questionnaireId)
    }

    /** 用户点「停止」：只收敛本地状态，服务端那侧靠连接断开自己止血。 */
    fun stopAnalysis() {
        if (!_uiState.value.isAnalyzing) return
        stopRequested = true
        streamJob?.cancel()
        streamJob = null
        AiStreamKeepAlive.stop(appContext)
        _uiState.update {
            it.copy(
                isAnalyzing = false,
                isRefreshing = false,
                isThinking = false,
                isStructuring = false,
                // 半截正文留着继续可读，比清空成"暂无分析"友好
                error = if (it.streamText.isBlank()) "已停止分析" else "",
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadFromSubmission(subId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            questionnaireRepository.getSubmissionDetail(subId).fold(
                onSuccess = { submission ->
                    // 优先使用结构化分析字段（后端已解析 analysis_text JSON）
                    val hasStructured = !submission.profileAnalysis.isNullOrBlank()
                    if (hasStructured) {
                        val analysis = QuestionnaireDto.AnalysisResponse(
                            analysis = "",
                            profileType = submission.profileType ?: "",
                            profileLabel = profileTypeLabels[submission.profileType] ?: submission.profileType ?: "",
                            confidence = submission.confidence,
                            dimensionScores = submission.dimensionScores ?: emptyMap(),
                            profileAnalysis = submission.profileAnalysis ?: "",
                            dimensionAnalyses = submission.dimensionAnalyses ?: emptyList(),
                            strengths = submission.strengths ?: "",
                            growthTips = submission.growthTips ?: emptyList(),
                            communicationGuide = submission.communicationGuide ?: "",
                        )
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAnalyzing = false,
                                analysis = analysis,
                                coupleProfileReady = submission.coupleProfileReady,
                            )
                        }
                    } else if (!submission.analysisText.isNullOrBlank()) {
                        // 兼容旧格式：纯文本 analysisText
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAnalyzing = false,
                                coupleProfileReady = submission.coupleProfileReady,
                                analysis = QuestionnaireDto.AnalysisResponse(
                                    analysis = submission.analysisText,
                                    profileType = submission.profileType ?: "",
                                    profileLabel = profileTypeLabels[submission.profileType] ?: submission.profileType ?: "",
                                    confidence = 0f,
                                    dimensionScores = submission.dimensionScores ?: emptyMap(),
                                ),
                            )
                        }
                    } else {
                        // 这次测评还没有分析，现场流式生成一份
                        questionnaireId = submission.questionnaireId
                        _uiState.update {
                            it.copy(coupleProfileReady = submission.coupleProfileReady)
                        }
                        startAnalysis(submission.questionnaireId)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    /**
     * 流式生成量表分析。
     *
     * 为什么这一条也要流式：分析报告是一篇要读几分钟的长文，最先出来的
     * 「依恋类型解读」恰恰是用户最想看的部分。等 20 秒一次性蹦出来，
     * 和边想边出，感受完全不同——这与信件解读、画像报告是同一套体验标准。
     */
    private fun startAnalysis(qId: Long) {
        if (qId <= 0) return
        streamJob?.cancel()
        stopRequested = false
        streamStartedAt = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                isLoading = false,
                isAnalyzing = true,
                isThinking = true,
                isStructuring = false,
                streamText = "",
                thinkingText = "",
                thinkingSeconds = 0,
                error = "",
            )
        }

        // 生成期间挂前台服务保活：退后台进程不被冻结、网络不受限，
        // 否则 socket 一断服务端就把这轮生成按 interrupted 收尾
        AiStreamKeepAlive.start(appContext)

        streamJob = viewModelScope.launch {
            questionnaireRepository.analyzeQuestionnaireStream(qId).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> Unit

                    // 思考增量只喂「深度思考」面板，不拼进正文
                    is GenerationStreamEvent.Thinking -> _uiState.update {
                        it.copy(thinkingText = it.thinkingText + ev.content)
                    }

                    is GenerationStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            streamText = it.streamText + ev.content,
                            // 正文一开始，思考就结束了
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
                                isAnalyzing = false,
                                isRefreshing = false,
                                isThinking = false,
                                isStructuring = false,
                                streamText = "",
                                error = ev.message,
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时，状态必须收敛，否则转圈不会停
            if (!stopRequested && _uiState.value.isAnalyzing) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        isRefreshing = false,
                        isThinking = false,
                        isStructuring = false,
                        error = if (it.analysis == null) "分析生成失败，请重试" else "",
                    )
                }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /**
     * done 帧收尾：把结构化字段转成页面要的分析结果。
     *
     * 结构化转不出来时退回纯正文——正文本身已经是一份完整的解读，
     * 只是少了分块卡片，不该让用户什么都看不到。
     */
    private fun finishStream(ev: GenerationStreamEvent.Finished) {
        val parsed = questionnaireRepository.parseAnalysis(ev.structured)
        val current = _uiState.value.analysis
        val fallback = current?.copy(
            profileAnalysis = ev.content.ifBlank { current.profileAnalysis },
        )
        _uiState.update {
            it.copy(
                isAnalyzing = false,
                isRefreshing = false,
                isThinking = false,
                isStructuring = false,
                streamText = "",
                analysis = parsed ?: fallback,
                error = if (parsed == null && ev.content.isBlank()) "分析生成失败，请重试" else "",
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

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }

    companion object {
        private val profileTypeLabels = mapOf(
            "secure" to "安全型依恋",
            "anxious" to "焦虑依恋型",
            "dismissive" to "疏离回避型",
            "fearful" to "恐惧回避型",
        )
    }
}
