package com.couple.translator.feature.couple.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedbackOutcomeUiState(
    val isLoading: Boolean = true,
    /** 真正待回访的建议（服务端已去重、已排除明确未采用） */
    val items: List<PendingFeedback> = emptyList(),
    /** 当前正在填结果的那条（null = 列表态） */
    val editing: PendingFeedback? = null,
    val outcomeText: String = "",
    val isSubmitting: Boolean = false,
    val error: String = "",
    /** 提交成功一次性回执（页面据此 pop 或提示） */
    val submitted: Boolean = false,
)

/**
 * 整改 §8.3：待反馈结果页。
 *
 * 首页 `feedback_outcome` 任务卡此前渲染成**不可点击的信息行**——用户被告知
 * 「上次建议采用了么？」，却没有任何地方能回答这个问题。本页就是那个去处：
 * 列出近 7 天待回访的建议（复用 `GET /ai/feedback/pending`，与任务卡同源），
 * 点进去填「后来怎么样」，提交后该行从列表消失 → 任务卡随之消失。
 *
 * 也支持带 messageId 直接从某条 AI 回复进来（补填），此时列表里没有这项也照填。
 */
@HiltViewModel
class FeedbackOutcomeViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedbackOutcomeUiState())
    val uiState: StateFlow<FeedbackOutcomeUiState> = _uiState.asStateFlow()

    /**
     * 打开页面。
     *
     * @param sessionId 从任务卡带进来的会话（可空 = 只看列表）
     * @param messageId 从某条 AI 回复直接进来补填的那条消息（可空）
     */
    fun load(sessionId: Long?, messageId: Long?) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            aiRepository.getPendingFeedback().fold(
                onSuccess = { resp ->
                    val items = resp.items.map { it.toPending() }
                    _uiState.update { it.copy(isLoading = false, items = items) }
                    // 定向补填三优先级：
                    // 1) 带 messageId 且在列表里 → 直接填那一条；
                    // 2) 带 messageId 但不在列表（已过期/已表态）→ 用调用方给的会话兜一条；
                    // 3) 只带 sessionId（任务卡点进来）→ 直接落该会话第一条，别让用户
                    //    在一屏只有一项的列表里再点一次。
                    val target = messageId?.let { id -> items.firstOrNull { it.messageId == id } }
                        ?: messageId?.let { id ->
                            PendingFeedback(
                                sessionId = sessionId ?: items.firstOrNull()?.sessionId ?: 0L,
                                messageId = id,
                                sceneKey = "",
                                title = null,
                                adopted = true,
                                createdAt = null,
                            )
                        }
                        ?: sessionId?.let { sid -> items.firstOrNull { it.sessionId == sid } }
                    target?.let { startEditing(it) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun startEditing(item: PendingFeedback) {
        _uiState.update {
            it.copy(editing = item, outcomeText = "", submitted = false, error = "")
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editing = null, outcomeText = "", error = "") }
    }

    fun onOutcomeChange(text: String) {
        _uiState.update { it.copy(outcomeText = text) }
    }

    fun submit() {
        val state = _uiState.value
        val item = state.editing ?: return
        val text = state.outcomeText.trim()
        if (text.isEmpty()) {
            _uiState.update { it.copy(error = "说说后来怎么样了") }
            return
        }
        if (item.sessionId == 0L) {
            _uiState.update { it.copy(error = "找不到对应的会话") }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, error = "") }
        viewModelScope.launch {
            aiRepository.submitFeedback(
                item.sessionId,
                AiDto.FeedbackRequest(messageId = item.messageId, outcome = text),
            ).fold(
                onSuccess = {
                    // 服务端 upsert 后该行不再满足 pending 条件 → 本地同步移除
                    _uiState.update { s ->
                        s.copy(
                            isSubmitting = false,
                            editing = null,
                            outcomeText = "",
                            submitted = true,
                            items = s.items.filterNot { it.messageId == item.messageId },
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSubmitting = false, error = e.message ?: "提交失败") }
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }
}

/** DTO → UI 语义模型（DTO 层不做映射，避免 DTO 里堆展示逻辑） */
fun AiDto.PendingFeedbackItem.toPending(): PendingFeedback = PendingFeedback(
    sessionId = sessionId,
    messageId = messageId,
    sceneKey = sceneKey,
    title = title,
    adopted = adopted,
    createdAt = createdAt,
)
