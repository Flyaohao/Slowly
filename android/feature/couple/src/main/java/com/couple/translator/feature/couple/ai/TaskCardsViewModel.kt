package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.data.repository.HomeRepository
import com.couple.translator.core.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskCardsUiState(
    val isLoading: Boolean = true,
    /** GET /home 的 task_cards（契约 §3.1）；后端未落地/字段缺失 → 空列表 = 不渲染。 */
    val cards: List<HomeDto.TaskCard> = emptyList(),
)

/**
 * 军师首页任务卡（契约 §3.1）。
 * 只消费 `GET /home` 的 `task_cards`，与会话状态无关，所以独立成 VM。
 */
@HiltViewModel
class TaskCardsViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskCardsUiState())
    val uiState: StateFlow<TaskCardsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val cards = homeRepository.getHomeData().getOrNull()?.taskCards ?: emptyList()
            _uiState.update { it.copy(isLoading = false, cards = cards) }
        }
    }
}

/**
 * type + id → 本 app 根路由（DEV 建议 FE 自行映射，不信任原始 route 字符串拼法）。
 * 返回 null = 没有可去的目标页（feedback_outcome 结果页属 §3.4 后续、未知 type），
 * 卡片渲染为不可点击的信息行——绝不 navigate 到未注册路由（会直接崩溃）。
 */
fun routeForTaskCard(card: HomeDto.TaskCard): String? = when (card.type) {
    "mediation_invite" ->
        "${Screen.MediationInvite.route}?sessionId=${card.id}&isInviter=false"
    "dual_perspective" -> "${Screen.DualPerspectiveDetail.route}/${card.id}"
    "pending_letter" -> "${Screen.LetterDetail.route}/${card.id}"
    "questionnaire" -> Screen.QuestionnaireIntro.route
    // feedback_outcome：待反馈结果页本轮未建（见最终报告），未知 type 同样不导航
    else -> null
}

/** title 为空时的兜底文案（后端按 type 生成 title，兜底只为形状漂移时不显示空行）。 */
fun fallbackTitleForTaskCard(type: String): String = when (type) {
    "mediation_invite" -> "双人调解邀请"
    "dual_perspective" -> "双视角记录等你提交"
    "pending_letter" -> "有一封信在等你"
    "feedback_outcome" -> "上次建议采用了么？"
    "questionnaire" -> "关系画像未完成"
    else -> "待办"
}
