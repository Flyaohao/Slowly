package com.couple.translator.core.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.data.repository.ProfileRepository
import com.couple.translator.core.data.repository.QuestionnaireRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
        get() = profileAnalysis.isNotBlank() || communicationGuide.isNotBlank()
}

@HiltViewModel
class ProfileResultViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val questionnaireRepository: QuestionnaireRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileResultUiState())
    val uiState: StateFlow<ProfileResultUiState> = _uiState.asStateFlow()

    init {
        loadSubmissions()
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
            // 有旧格式分析文本，触发 AI 分析以获取结构化数据
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

    /**
     * 触发 AI 分析生成，完成后更新 UI
     */
    private fun triggerAnalysis(
        questionnaireId: Long,
        profile: ProfileDto.RelationshipProfileResponse,
        dimensions: List<ProfileDto.DimensionScoreResponse>,
    ) {
        viewModelScope.launch {
            questionnaireRepository.analyzeQuestionnaire(questionnaireId).fold(
                onSuccess = { analysis ->
                    _uiState.update {
                        it.copy(
                            profile = profile,
                            dimensions = dimensions,
                            profileAnalysis = analysis.profileAnalysis,
                            dimensionAnalyses = analysis.dimensionAnalyses,
                            strengths = analysis.strengths,
                            growthTips = analysis.growthTips,
                            communicationGuide = analysis.communicationGuide,
                        )
                    }
                },
                onFailure = {
                    // 分析失败，保留已有的基础数据
                },
            )
        }
    }
}
