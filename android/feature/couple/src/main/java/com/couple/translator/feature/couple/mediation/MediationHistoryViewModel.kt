package com.couple.translator.feature.couple.mediation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MediationDto
import com.couple.translator.feature.couple.data.repository.MediationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediationHistoryUiState(
    val items: List<MediationDto.MediationListItem> = emptyList(),
    val isLoading: Boolean = false,
    /** 请求真的失败了（区别于「拉到了但是空的」——§8.4 的读不到≠没有）。 */
    val loadFailed: Boolean = false,
)

/** 调解回看列表（§8.5-6）：只读，取 role=history 的已完成会话。 */
@HiltViewModel
class MediationHistoryViewModel @Inject constructor(
    private val mediationRepository: MediationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediationHistoryUiState())
    val uiState: StateFlow<MediationHistoryUiState> = _uiState.asStateFlow()

    fun load() {
        _uiState.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            mediationRepository.getMediationList("history").fold(
                onSuccess = { items ->
                    _uiState.update {
                        it.copy(items = items, isLoading = false, loadFailed = false)
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                },
            )
        }
    }
}
