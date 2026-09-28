package com.couple.translator.core.ui.aiconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiConfigDto
import com.couple.translator.core.network.SharedApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AI 服务配置页状态。
 *
 * 业务码约定（与后端 v5.0 对齐）：
 * - 30010 未配置 → [configured]=false，表单清空等待填写；
 * - 30011 配置非法 → [error] 透传探测原因（连接失败/维度不符等）。
 */
data class AiConfigUiState(
    val isLoading: Boolean = true,
    val configured: Boolean = false,
    // 表单字段
    val providerType: String = "openai",
    val baseUrl: String = "",
    val modelName: String = "",
    val embeddingBaseUrl: String = "",
    // 预填 DashScope 默认 embedding 模型（1024 维，与 Chroma 集合一致），可改；
    // 大多数用户与聊天 key 同厂，这一行零改动即可用。
    val embeddingModel: String = "text-embedding-v4",
    val embeddingApiKey: String = "",
    val enableRateLimit: Boolean = true,
    val newKeys: List<String> = emptyList(),
    val keys: List<AiConfigDto.AiKeyItem> = emptyList(),
    // 过程状态
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val saved: Boolean = false,
    /** 「测试连接」通过的提示文案 */
    val testPassed: String = "",
    val error: String = "",
)

@HiltViewModel
class AiConfigViewModel @Inject constructor(
    private val sharedApiService: SharedApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiConfigUiState())
    val uiState: StateFlow<AiConfigUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = "", saved = false, testPassed = "") }
        viewModelScope.launch {
            val result = runCatching { sharedApiService.getAiConfig() }
            val response = result.getOrNull()
            when {
                response != null && response.isSuccess && response.data != null -> {
                    val cfg = response.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            configured = true,
                            providerType = cfg.providerType.ifBlank { "openai" },
                            baseUrl = cfg.baseUrl,
                            modelName = cfg.modelName,
                            embeddingBaseUrl = cfg.embeddingBaseUrl,
                            embeddingModel = cfg.embeddingModel.ifBlank { "text-embedding-v4" },
                            embeddingApiKey = "",
                            enableRateLimit = cfg.enableRateLimit,
                            newKeys = emptyList(),
                            keys = cfg.keys,
                        )
                    }
                }
                else -> {
                    // 未配置（30010/data=null）或读取失败 → 空表单等待填写
                    _uiState.update { it.copy(isLoading = false, configured = false) }
                }
            }
        }
    }

    fun setProviderType(v: String) = update { it.copy(providerType = v) }
    fun setBaseUrl(v: String) = update { it.copy(baseUrl = v) }
    fun setModelName(v: String) = update { it.copy(modelName = v) }
    fun setEmbeddingBaseUrl(v: String) = update { it.copy(embeddingBaseUrl = v) }
    fun setEmbeddingModel(v: String) = update { it.copy(embeddingModel = v) }
    fun setEmbeddingApiKey(v: String) = update { it.copy(embeddingApiKey = v) }
    fun setRateLimit(v: Boolean) = update { it.copy(enableRateLimit = v) }

    /** 新增一把 key（暂存表单，保存时随 PUT 一并校验入库）。 */
    fun addNewKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        update { it.copy(newKeys = it.newKeys + trimmed) }
    }

    fun removeNewKey(index: Int) = update {
        it.copy(newKeys = it.newKeys.filterIndexed { i, _ -> i != index })
    }

    fun toggleKey(keyId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val result = runCatching {
                sharedApiService.toggleAiKey(keyId, AiConfigDto.KeyToggleRequest(enabled))
            }
            val response = result.getOrNull()
            if (response != null && response.isSuccess) {
                update { state ->
                    state.copy(keys = state.keys.map {
                        if (it.id == keyId) it.copy(enabled = enabled) else it
                    })
                }
            } else {
                update { it.copy(error = response?.message ?: "操作失败，请稍后重试") }
            }
        }
    }

    fun deleteKey(keyId: Long) {
        viewModelScope.launch {
            val result = runCatching { sharedApiService.deleteAiKey(keyId) }
            val response = result.getOrNull()
            if (response != null && response.isSuccess) {
                // 最后一把被删 = 整份配置被清掉（后端约定）
                update {
                    it.copy(
                        keys = it.keys.filter { k -> k.id != keyId },
                        configured = it.keys.any { k -> k.id != keyId },
                    )
                }
            } else {
                update { it.copy(error = response?.message ?: "删除失败，请稍后重试") }
            }
        }
    }

    /** 只测不存（「测试连接」按钮）。 */
    fun testConnection() {
        val s = _uiState.value
        if (s.isTesting || s.isSaving) return
        _uiState.update { it.copy(isTesting = true, error = "", testPassed = "") }
        viewModelScope.launch {
            val result = runCatching {
                sharedApiService.testAiConfig(
                    AiConfigDto.TestRequest(
                        providerType = s.providerType,
                        baseUrl = s.baseUrl.trim(),
                        modelName = s.modelName.trim(),
                        embeddingBaseUrl = s.embeddingBaseUrl.trim(),
                        embeddingModel = s.embeddingModel.trim(),
                        embeddingApiKey = s.embeddingApiKey.trim(),
                        keys = allKeys(s),
                    )
                )
            }
            val response = result.getOrNull()
            when {
                response != null && response.isSuccess && response.data != null -> {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testPassed = "连接成功（Embedding ${response.data.embeddingDim ?: "?"} 维）",
                        )
                    }
                }
                else -> {
                    _uiState.update {
                        it.copy(isTesting = false, error = response?.message ?: "测试失败，请检查网络")
                    }
                }
            }
        }
    }

    /** 保存（后端强制双连通性测试，未通过返回 30011）。 */
    fun save() {
        val s = _uiState.value
        if (s.isSaving || s.isTesting) return
        _uiState.update { it.copy(isSaving = true, error = "", saved = false) }
        viewModelScope.launch {
            val result = runCatching {
                sharedApiService.saveAiConfig(
                    AiConfigDto.SaveRequest(
                        providerType = s.providerType,
                        baseUrl = s.baseUrl.trim(),
                        modelName = s.modelName.trim(),
                        embeddingBaseUrl = s.embeddingBaseUrl.trim(),
                        embeddingModel = s.embeddingModel.trim(),
                        embeddingApiKey = s.embeddingApiKey.trim(),
                        enableRateLimit = s.enableRateLimit,
                        newKeys = allKeys(s),
                    )
                )
            }
            val response = result.getOrNull()
            when {
                response != null && response.isSuccess && response.data != null -> {
                    val cfg = response.data
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            saved = true,
                            configured = true,
                            keys = cfg.keys,
                            newKeys = emptyList(),
                            embeddingApiKey = "",
                        )
                    }
                }
                else -> {
                    _uiState.update {
                        it.copy(isSaving = false, error = response?.message ?: "保存失败，请稍后重试")
                    }
                }
            }
        }
    }

    fun clearMessages() = update { it.copy(error = "", saved = false, testPassed = "") }

    private fun allKeys(s: AiConfigUiState): List<String> =
        s.newKeys.map { it.trim() }.filter { it.isNotEmpty() }

    private fun update(transform: (AiConfigUiState) -> AiConfigUiState) {
        _uiState.update {
            transform(it).copy(saved = false, error = "", testPassed = "")
        }
    }
}
