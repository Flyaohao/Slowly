package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ColdWarStep(
    val index: Int,
    val title: String,
    val description: String,
)

data class ColdWarUiState(
    val currentStep: Int = 0,
    val steps: List<ColdWarStep> = listOf(
        ColdWarStep(0, "确认目标", "你想和好、想解释，还是想被理解？"),
        ColdWarStep(1, "拆分面子与需求", "哪些是面子，哪些是真实需求？"),
        ColdWarStep(2, "选择方式", "主动靠近还是先给空间？"),
        ColdWarStep(3, "开场白", "为你生成低压力的开场白"),
    ),
    val userInput: String = "",
    val goalAnalysis: String = "",
    val faceVsNeed: String = "",
    val approach: String = "",
    val approachReason: String = "",
    val openingLines: List<String> = emptyList(),
    val avoidReminders: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
)

sealed class ColdWarUiEvent {
    data class ShowError(val message: String) : ColdWarUiEvent()
}

@HiltViewModel
class ColdWarViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ColdWarUiState())
    val uiState: StateFlow<ColdWarUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<ColdWarUiEvent>()
    val event: SharedFlow<ColdWarUiEvent> = _event.asSharedFlow()

    fun onInputChange(text: String) {
        _uiState.update { it.copy(userInput = text) }
    }

    fun nextStep() {
        val state = _uiState.value
        when (state.currentStep) {
            0 -> submitGoal()
            1 -> submitFaceVsNeed()
            2 -> submitApproachChoice()
            3 -> {}
        }
    }

    fun prevStep() {
        _uiState.update {
            if (it.currentStep > 0) it.copy(currentStep = it.currentStep - 1) else it
        }
    }

    private fun submitGoal() {
        val input = _uiState.value.userInput.trim()
        if (input.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val request = AiDto.ChatRequest(
                sceneKey = "cold_war",
                message = "我的冷战目标：$input",
            )
            aiRepository.chat(request).fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            currentStep = 1,
                            // cold_war 场景由 ColdWarOutput 产出，字段是 goal_analysis；
                            // 此前误读 summary（TranslateOutput 才有），永远拿不到值。
                            goalAnalysis = response.structuredOutput?.goalAnalysis
                                ?: response.content,
                            userInput = "",
                            isLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "请求失败") }
                },
            )
        }
    }

    private fun submitFaceVsNeed() {
        val input = _uiState.value.userInput.trim()
        if (input.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val request = AiDto.ChatRequest(
                sceneKey = "cold_war",
                message = "面子与需求分析：$input",
            )
            aiRepository.chat(request).fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            currentStep = 2,
                            faceVsNeed = response.structuredOutput?.faceVsNeed
                                ?: response.content,
                            userInput = "",
                            isLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "请求失败") }
                },
            )
        }
    }

    private fun submitApproachChoice() {
        val input = _uiState.value.userInput.trim()
        if (input.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val request = AiDto.ChatRequest(
                sceneKey = "cold_war",
                message = "生成开场白，用户选择：$input",
            )
            aiRepository.chat(request).fold(
                onSuccess = { response ->
                    // 本步用的仍是 cold_war 场景（ColdWarOutput），不是 TranslateOutput：
                    // 策略=approach、理由=approach_reason、开场白=opening_lines、
                    // 雷区=avoid_reminders。此前这几处分别误读了
                    // nextStep / summary / suggestedActions / doNotSay，全部落空。
                    val output = response.structuredOutput
                    val lines = output?.openingLines.orEmpty()
                    _uiState.update {
                        it.copy(
                            currentStep = 3,
                            approach = output?.approach ?: "approach",
                            approachReason = output?.approachReason.orEmpty(),
                            openingLines = lines.ifEmpty { listOf(response.content) },
                            avoidReminders = output?.avoidReminders.orEmpty(),
                            userInput = "",
                            isLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "请求失败") }
                },
            )
        }
    }
}
