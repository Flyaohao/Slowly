package com.couple.translator.feature.single.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.repository.ProfileRepository
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.feature.single.data.model.DiaryDto
import com.couple.translator.feature.single.network.SingleApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiaryDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val diary: DiaryDto.DiaryResponse? = null,
    val isDeleted: Boolean = false,
    val error: String? = null,

    // ---- 让军师读一读（观点分析 → 补充画像）----
    /** 正在分析（含「深度思考」阶段）。 */
    val isAnalyzing: Boolean = false,
    /** 模型的思考过程增量，喂思考面板；不参与正文拼接。 */
    val analysisThinking: String = "",
    /** 分析正文（打字机可见），用户读的是这一段。 */
    val analysisContent: String = "",
    /** 结构化建议；为 null 表示还没分析过。 */
    val analysis: AiDto.ViewpointAnalysis? = null,
    /** 分析或补充过程中的错误。 */
    val analysisError: String? = null,
    /** 正在把建议写进画像。 */
    val isEnriching: Boolean = false,
    /** 写入成功后的提示（含新版本号与撤回路径）。 */
    val enrichResult: String? = null,
)

@HiltViewModel
class DiaryDetailViewModel @Inject constructor(
    private val apiService: SingleApiService,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiaryDetailUiState())
    val uiState: StateFlow<DiaryDetailUiState> = _uiState.asStateFlow()

    private var analyzeJob: Job? = null

    fun loadDiary(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.getDiary(id)
                if (response.isSuccess && response.data != null) {
                    _uiState.update { it.copy(isLoading = false, diary = response.data) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun toggleFavorite() {
        val diary = _uiState.value.diary ?: return
        viewModelScope.launch {
            try {
                val response = apiService.toggleDiaryFavorite(diary.id)
                if (response.isSuccess && response.data != null) {
                    _uiState.update { it.copy(diary = response.data) }
                } else {
                    _uiState.update { it.copy(error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "操作失败") }
            }
        }
    }

    fun refresh() {
        val id = _uiState.value.diary?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                val response = apiService.getDiary(id)
                if (response.isSuccess && response.data != null) {
                    _uiState.update { it.copy(isRefreshing = false, diary = response.data) }
                } else {
                    _uiState.update { it.copy(isRefreshing = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
    }

    fun deleteDiary() {
        val diary = _uiState.value.diary ?: return
        viewModelScope.launch {
            try {
                val response = apiService.deleteDiary(diary.id)
                if (response.isSuccess) {
                    _uiState.update { it.copy(isDeleted = true) }
                } else {
                    _uiState.update { it.copy(error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "删除失败") }
            }
        }
    }

    // ============ 观点分析（用户需求 #5）============

    /**
     * 让军师读一读这条观点。
     *
     * 分析结果**只是建议**：它回答「这段观点说明了什么」和「值不值得写进画像」。
     * 是否真的写，由用户在界面上决定（[enrichProfile]）——这条分界是刻意的，
     * 让模型直接改画像，等于把核心资产交给一次生成。
     */
    fun analyzeViewpoint() {
        val diary = _uiState.value.diary ?: return
        if (analyzeJob?.isActive == true) return

        analyzeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzing = true,
                    analysisThinking = "",
                    analysisContent = "",
                    analysis = null,
                    analysisError = null,
                    enrichResult = null,
                )
            }
            profileRepository.viewpointAnalysisStream(diary.id).collect { event ->
                when (event) {
                    is GenerationStreamEvent.Thinking ->
                        _uiState.update { it.copy(analysisThinking = it.analysisThinking + event.content) }

                    is GenerationStreamEvent.Delta ->
                        _uiState.update { it.copy(analysisContent = it.analysisContent + event.content) }

                    is GenerationStreamEvent.Finished -> {
                        val parsed = parseAnalysis(event.structured)
                        _uiState.update {
                            it.copy(
                                isAnalyzing = false,
                                analysis = parsed,
                                analysisContent = event.content.ifBlank { it.analysisContent },
                                // 模型给了正文却没给结构化建议：明确告诉用户"读完了但没形成建议"，
                                // 而不是留一个看起来加载失败的空白区
                                analysisError = if (parsed == null) "这次没能形成建议，可以再试一次" else null,
                            )
                        }
                    }

                    is GenerationStreamEvent.Failure ->
                        _uiState.update {
                            it.copy(isAnalyzing = false, analysisError = event.message)
                        }

                    else -> Unit
                }
            }
        }
    }

    /** 用户点「补充到画像」：把建议原样交给服务端，由它按幅度规则落库。 */
    fun enrichProfile() {
        val diary = _uiState.value.diary ?: return
        val analysis = _uiState.value.analysis ?: return
        val dims = analysis.dimensions
        if (dims.isEmpty()) {
            _uiState.update { it.copy(analysisError = "这次分析没有可补充的维度") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isEnriching = true, analysisError = null) }
            val request = ProfileDto.EnrichRequest(
                viewpointId = diary.id,
                dimensions = dims.map { it.dimensionKey },
                summary = analysis.summary,
                directions = dims.associate {
                    it.dimensionKey to ProfileDto.EnrichDirection(it.direction, it.strength)
                },
                confidence = analysis.confidence,
            )
            profileRepository.enrichProfile(request).fold(
                onSuccess = { r ->
                    _uiState.update {
                        it.copy(
                            isEnriching = false,
                            enrichResult = "已补充到画像（v${r.version}）。可在「人格画像 → 历史版本」里撤回。",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isEnriching = false, analysisError = e.message ?: "补充失败")
                    }
                },
            )
        }
    }

    fun dismissEnrichResult() {
        _uiState.update { it.copy(enrichResult = null) }
    }

    /**
     * 结构化结果 → 强类型。
     *
     * 手工取值而不是走 Moshi：`Finished.structured` 是通用 `Map<String, Any?>`，
     * 各生成类型的形状不同（信件解读、复盘、观点分析各有各的字段），
     * 用一个统一的 Moshi 模型反而要写一堆 `@Json` 别名。
     * 取不到就是 null，让上层显示"没形成建议"，而不是抛异常。
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseAnalysis(map: Map<String, Any?>?): AiDto.ViewpointAnalysis? {
        if (map.isNullOrEmpty()) return null
        val dimensions = (map["dimensions"] as? List<Any?>)
            .orEmpty()
            .mapNotNull { raw ->
                val d = raw as? Map<String, Any?> ?: return@mapNotNull null
                val key = d["dimension_key"] as? String ?: return@mapNotNull null
                val direction = d["direction"] as? String ?: return@mapNotNull null
                AiDto.SuggestedDimension(
                    dimensionKey = key,
                    direction = direction,
                    strength = d["strength"] as? String ?: "mild",
                )
            }
        return AiDto.ViewpointAnalysis(
            summary = map["summary"] as? String ?: "",
            values = (map["values"] as? List<Any?>).orEmpty().mapNotNull { it as? String },
            stance = map["stance"] as? String ?: "",
            confidence = (map["confidence"] as? Number)?.toFloat() ?: 0f,
            basis = (map["basis"] as? List<Any?>).orEmpty().mapNotNull { it as? String },
            suggestEnrich = map["suggest_enrich"] as? Boolean ?: false,
            dimensions = dimensions,
        )
    }
}
