package com.couple.translator.feature.couple.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.feature.couple.data.repository.PracticeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PracticeListUiState(
    val practices: List<PracticeDto.PracticeResponse> = emptyList(),
    /** 我做过的练习记录（后端按时间倒序，这里只取最近几条展示） */
    val records: List<PracticeDto.PracticeRecordResponse> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class PracticeListViewModel @Inject constructor(
    private val repository: PracticeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeListUiState())
    val uiState: StateFlow<PracticeListUiState> = _uiState.asStateFlow()

    init {
        loadPractices()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            repository.getPractices().fold(
                onSuccess = { practices ->
                    practices?.let {
                        _uiState.update { state ->
                            state.copy(practices = it, isRefreshing = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isRefreshing = false, error = error.message ?: "加载失败")
                    }
                },
            )
            loadRecordsInternal()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun loadPractices() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.getPractices().fold(
                onSuccess = { practices ->
                    practices?.let {
                        _uiState.update { state ->
                            state.copy(practices = it, isLoading = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
            loadRecordsInternal()
        }
    }

    /**
     * 拉取我做过的练习记录。
     *
     * 之前这个页面只有「发起新练习」一条路，做完的记录再也进不去——
     * 练习结果页（含 AI 整理）等于死链。这里补上回看入口。
     */
    private suspend fun loadRecordsInternal() {
        repository.getRecords().fold(
            onSuccess = { response ->
                val items = response?.items ?: emptyList()
                _uiState.update { it.copy(records = items) }
            },
            onFailure = { /* 记录拉不到不影响发起新练习，静默 */ },
        )
    }

    fun startPractice(practiceId: Long, onSuccess: (Long) -> Unit) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.startPractice(practiceId).fold(
                onSuccess = { record ->
                    record?.let {
                        _uiState.update { state -> state.copy(isLoading = false) }
                        onSuccess(it.id)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "发起失败")
                    }
                },
            )
        }
    }
}
