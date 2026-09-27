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

/**
 * 与服务端 `profile_service.ENRICH_MIN_CONFIDENCE` 对齐。
 *
 * 只用来决定「这次点击要不要带 force」——真正拦不拦仍在服务端。
 * 如果两边漂移，最坏结果是多带一次 force（用户本来就有权覆盖），不会写坏数据。
 */
private const val MIN_ENRICH_CONFIDENCE = 0.6f

data class DiaryDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val diary: DiaryDto.DiaryResponse? = null,
    val isDeleted: Boolean = false,
    val error: String? = null,

    // ---- 深度解读 ----
    /** 正在解读（含「思考中」阶段）。 */
    val isAnalyzing: Boolean = false,
    /** 模型的思考过程增量，喂思考面板；不参与正文拼接。 */
    val analysisThinking: String = "",
    /** 解读正文（打字机可见），用户读的是这一段。 */
    val analysisContent: String = "",
    /** 结构化结果；为 null 表示还没解读过。 */
    val analysis: AiDto.ViewpointAnalysis? = null,
    /** 解读或开关操作过程中的错误。 */
    val analysisError: String? = null,

    // ---- 开关一：计入个人画像 ----
    /**
     * 当前画像是否已经包含这条观点。**取自服务端现状**（最新版本是不是由它派生），
     * 不是本地记账——否则重进页面就会显示成「未计入」，用户一点又补一次。
     */
    val profileLinked: Boolean = false,
    /** 关掉开关时要撤回到的那一版（这条观点补充之前的那一版）。 */
    val profileTargetVersionId: Long? = null,
    val isTogglingProfile: Boolean = false,

    // ---- 开关二：计入军师记忆 ----
    val memoryLinked: Boolean = false,
    val isTogglingMemory: Boolean = false,

    /** 开关操作成功后的提示。 */
    val toggleMessage: String? = null,
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
                    loadToggles(id)
                } else {
                    _uiState.update { it.copy(isLoading = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * 拉两个开关的初始状态。都问服务端，不靠本地记忆——
     * 开关最容易出的错就是「看起来是关的，其实是开的」。
     */
    private fun loadToggles(diaryId: Long) {
        loadProfileToggle(diaryId)
        loadMemoryToggle(diaryId)
    }

    private fun loadProfileToggle(diaryId: Long? = _uiState.value.diary?.id) {
        if (diaryId == null) return
        viewModelScope.launch {
            profileRepository.getProfileVersions().onSuccess { versions ->
                // 版本列表新→旧：最新一版由本观点派生 ⇒ 已计入
                val latest = versions.firstOrNull()
                // 撤回目标：这条观点补充之前的那一版（即第一个不是它派生的版本）
                val target = versions.firstOrNull { it.sourceViewpointId != diaryId }
                _uiState.update {
                    it.copy(
                        profileLinked = latest?.sourceViewpointId == diaryId,
                        profileTargetVersionId = target?.id,
                    )
                }
            }
        }
    }

    private fun loadMemoryToggle(diaryId: Long) {
        viewModelScope.launch {
            profileRepository.getViewpointMemory(diaryId).onSuccess { items ->
                _uiState.update { it.copy(memoryLinked = items.isNotEmpty()) }
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
                    loadToggles(id)
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

    // ============ 深度解读 ============

    /**
     * 让军师深度解读这条观点。
     *
     * 结果**只是建议**：它回答「这段观点说明了什么」「值不值得写进画像」
     * 「值不值得长期记住」。三件事要不要落地，由用户在界面上的那两个开关决定。
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
                    toggleMessage = null,
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
                                // 模型给了正文却没给结构化结果：明确说「读完了但没形成建议」，
                                // 而不是留一片看起来像加载失败的空白
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

    // ============ 开关一：计入个人画像 ============

    /** 打开：把这条观点的建议写进画像（派生一个新版本）。 */
    fun linkProfile() {
        val state = _uiState.value
        val diary = state.diary ?: return
        if (state.isTogglingProfile) return

        // 没有解读结果就没有维度可写。给明确引导，而不是让开关点了没反应。
        val analysis = state.analysis
        if (analysis == null) {
            _uiState.update {
                it.copy(analysisError = "先让军师深度解读一次，它才知道这条观点该往哪个维度写")
            }
            return
        }

        val dims = analysis.dimensions
        if (dims.isEmpty()) {
            _uiState.update { it.copy(analysisError = "军师这次没有找到可以写进画像的维度") }
            return
        }

        // AI 的置信度只是**建议**：它判断把握不足时用户仍可坚持计入，
        // 但必须由这一次点击把 force 带出去，不能由客户端默认替他做主。
        val needsForce = !(analysis.suggestEnrich && analysis.confidence >= MIN_ENRICH_CONFIDENCE)

        viewModelScope.launch {
            _uiState.update { it.copy(isTogglingProfile = true, analysisError = null) }
            val request = ProfileDto.EnrichRequest(
                viewpointId = diary.id,
                dimensions = dims.map { it.dimensionKey },
                summary = analysis.summary,
                directions = dims.associate {
                    it.dimensionKey to ProfileDto.EnrichDirection(it.direction, it.strength)
                },
                confidence = analysis.confidence,
                force = needsForce,
            )
            profileRepository.enrichProfile(request).fold(
                onSuccess = { r ->
                    _uiState.update {
                        it.copy(
                            isTogglingProfile = false,
                            profileLinked = true,
                            toggleMessage = "已计入画像（v${r.version}）。" +
                                "不想要可以随时关掉这个开关，或在「人格画像 → 历史版本」里撤回。",
                        )
                    }
                    loadProfileToggle(diary.id)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isTogglingProfile = false, analysisError = e.message ?: "计入画像失败")
                    }
                },
            )
        }
    }

    /** 关掉：把这条观点对画像的影响撤回（撤回到它补充之前的那一版）。 */
    fun unlinkProfile() {
        val state = _uiState.value
        if (state.isTogglingProfile) return
        val target = state.profileTargetVersionId
        if (target == null) {
            _uiState.update { it.copy(analysisError = "找不到这条观点补充之前的版本，可以在画像历史里手动撤回") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTogglingProfile = true, analysisError = null) }
            profileRepository.restoreProfileVersion(target).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isTogglingProfile = false,
                            profileLinked = false,
                            toggleMessage = "已撤回这条观点对画像的影响（撤回本身也存成了一个版本，随时能再撤回来）。",
                        )
                    }
                    loadProfileToggle()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isTogglingProfile = false, analysisError = e.message ?: "撤回失败")
                    }
                },
            )
        }
    }

    // ============ 开关二：计入军师记忆 ============

    /** 打开：把这条观点写进军师记忆（类型取 AI 建议，服务端按白名单校验）。 */
    fun linkMemory() {
        val state = _uiState.value
        val diary = state.diary ?: return
        if (state.isTogglingMemory) return

        // AI 建议的类型；它没给（或认为不值得记）就传空，由服务端兜到「核心诉求」
        val memoryType = state.analysis?.memoryType?.takeIf { it.isNotBlank() }

        viewModelScope.launch {
            _uiState.update { it.copy(isTogglingMemory = true, analysisError = null) }
            profileRepository.linkViewpointMemory(diary.id, memoryType).fold(
                onSuccess = { items ->
                    _uiState.update {
                        it.copy(
                            isTogglingMemory = false,
                            memoryLinked = items.isNotEmpty(),
                            toggleMessage = "军师会记住这件事（仅你可见，可以在「记忆」页随时删除）。",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isTogglingMemory = false, analysisError = e.message ?: "计入记忆失败")
                    }
                },
            )
        }
    }

    /** 关掉：撤除这条观点产生的记忆（连同向量库里的派生数据）。 */
    fun unlinkMemory() {
        val diary = _uiState.value.diary ?: return
        if (_uiState.value.isTogglingMemory) return

        viewModelScope.launch {
            _uiState.update { it.copy(isTogglingMemory = true, analysisError = null) }
            profileRepository.unlinkViewpointMemory(diary.id).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isTogglingMemory = false,
                            memoryLinked = false,
                            toggleMessage = "已让军师忘掉这件事。",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isTogglingMemory = false, analysisError = e.message ?: "撤除失败")
                    }
                },
            )
        }
    }

    fun dismissToggleMessage() {
        _uiState.update { it.copy(toggleMessage = null) }
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
            memoryType = map["memory_type"] as? String ?: "",
        )
    }
}
