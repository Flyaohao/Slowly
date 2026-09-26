package com.couple.translator.core.ui.advisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AdvisorDto
import com.couple.translator.core.network.SharedApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdvisorSettingsUiState(
    val isLoading: Boolean = true,
    /** 编辑中的设置；GET 失败时为 [AdvisorDto.AdvisorSettings.defaults]（契约 §3.3 降级渲染）。 */
    val settings: AdvisorDto.AdvisorSettings = AdvisorDto.AdvisorSettings.defaults(),
    val isSaving: Boolean = false,
    /** 最近一次保存成功（页面展示「已保存」提示）。 */
    val saved: Boolean = false,
    /** GET 失败（端点未落地/网络错误）→ 页面提示将使用默认值。 */
    val loadFailed: Boolean = false,
    /** PUT 失败的明确错误文案——不静默吞错（含单模式 30005 等业务错误）。 */
    val error: String = "",
)

/**
 * 军师设置页（契约 §3.3）：GET/PUT `/api/v1/advisor/settings` 同构。
 * - GET 失败 → 默认值渲染 + loadFailed 提示（优雅降级，仍可编辑尝试保存）
 * - PUT 失败 → error 可见（单人模式会收到 30005，属后端存储缺口，见最终报告）
 */
@HiltViewModel
class AdvisorSettingsViewModel @Inject constructor(
    private val sharedApiService: SharedApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvisorSettingsUiState())
    val uiState: StateFlow<AdvisorSettingsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = "", saved = false) }
        viewModelScope.launch {
            val result = runCatching { sharedApiService.getAdvisorSettings() }
            result.fold(
                onSuccess = { response ->
                    if (response.isSuccess) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                settings = response.data ?: AdvisorDto.AdvisorSettings.defaults(),
                                loadFailed = false,
                            )
                        }
                    } else {
                        // 业务错误（如端点未落地的 404 被信封化）→ 降级为默认值
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                settings = AdvisorDto.AdvisorSettings.defaults(),
                                loadFailed = true,
                                error = "",
                            )
                        }
                    }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            settings = AdvisorDto.AdvisorSettings.defaults(),
                            loadFailed = true,
                            error = "",
                        )
                    }
                },
            )
        }
    }

    fun setAddressName(value: String) = updateSettings { it.copy(addressName = value) }

    fun setDetailLevel(value: String) = updateSettings { it.copy(detailLevel = value) }

    fun setProactivity(value: String) = updateSettings { it.copy(proactivity = value) }

    fun setShowEvidence(value: Boolean) = updateSettings { it.copy(showEvidence = value) }

    fun setVoiceStyle(value: String) = updateSettings { it.copy(voiceStyle = value) }

    fun clearMessages() {
        _uiState.update { it.copy(error = "", saved = false) }
    }

    fun save() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, error = "", saved = false) }
        viewModelScope.launch {
            val settings = _uiState.value.settings
            val result = runCatching { sharedApiService.updateAdvisorSettings(settings) }
            result.fold(
                onSuccess = { response ->
                    if (response.isSuccess) {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                settings = response.data ?: settings,
                                saved = true,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                saved = false,
                                error = response.message.ifBlank { "保存失败，请稍后重试" },
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            saved = false,
                            error = throwable.message ?: "保存失败，请稍后重试",
                        )
                    }
                },
            )
        }
    }

    private fun updateSettings(transform: (AdvisorDto.AdvisorSettings) -> AdvisorDto.AdvisorSettings) {
        _uiState.update {
            it.copy(
                settings = transform(it.settings),
                saved = false,
                error = "",
            )
        }
    }
}
