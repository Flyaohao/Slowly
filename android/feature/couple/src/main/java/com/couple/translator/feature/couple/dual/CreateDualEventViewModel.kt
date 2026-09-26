package com.couple.translator.feature.couple.dual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.feature.couple.data.repository.DualPerspectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class CreateDualEventUiState(
    val title: String = "",
    val eventTime: String = "",
    /**
     * 整改 §8.6：邀请语。非空时创建后通知伴侣一起写。
     * [inviteMode] 由入口决定（军师行动行 → true，自己进列表页 → false，但用户
     * 仍可手动填写邀请语）。
     */
    val inviteMessage: String = "",
    val inviteMode: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
    val created: Boolean = false,
    val createdEventId: Long = 0,
)

@HiltViewModel
class CreateDualEventViewModel @Inject constructor(
    private val repository: DualPerspectiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateDualEventUiState())
    val uiState: StateFlow<CreateDualEventUiState> = _uiState.asStateFlow()

    /**
     * 入口参数：军师行动行「邀请 TA 补充双视角」进来时置位，
     * 页面据此把邀请语输入与按钮文案摆到正确形态。
     *
     * 由 UI 显式喂进来（`CreateDualEventScreen(invite = true)`），**不读
     * SavedStateHandle**：同一 composable 的多个目的地会共享 handle，
     * 带参版本会把上一次的值留在里面（复盘页曾因此打开上一条记录）。
     * 首次进入置位，用户随后手动改了就不覆盖。
     */
    fun initialize(invite: Boolean) {
        if (inviteInitialized) return
        inviteInitialized = true
        if (invite) {
            _uiState.update { it.copy(inviteMode = true) }
        }
    }

    private var inviteInitialized = false

    /** 关闭错误弹窗（B-05：此前 Screen 传空的 onDismiss，弹窗无法关闭） */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateEventTime(time: String) {
        _uiState.update { it.copy(eventTime = time) }
    }

    fun updateInviteMessage(text: String) {
        _uiState.update { it.copy(inviteMessage = text) }
    }

    fun createEvent() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "请输入事件标题") }
            return
        }
        val eventTime = normalizeEventTime(state.eventTime)
        if (eventTime == null) {
            _uiState.update { it.copy(error = "事件时间格式应为 YYYY-MM-DD 或 YYYY-MM-DD HH:mm") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            repository.createEvent(
                DualPerspectiveDto.CreateEventRequest(
                    title = state.title,
                    eventTime = eventTime,
                    inviteMessage = state.inviteMessage.trim().ifBlank { null },
                ),
            ).fold(
                onSuccess = { event ->
                    event?.let {
                        _uiState.update { state ->
                            state.copy(isLoading = false, created = true, createdEventId = it.id)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "创建失败")
                    }
                },
            )
        }
    }

    /**
     * 把 UI 里选填的时间文本归一化成后端要求的 datetime 字符串。
     *
     * 后端 `event_time` 是必填字段，因此空输入不再传 null，而是兜底为当前时间；
     * 只填日期时补零点，填了时分则补秒，最终统一为 `yyyy-MM-ddTHH:mm:ss`。
     * 返回 null 表示格式非法，由调用方提示用户。
     */
    private fun normalizeEventTime(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) {
            return LocalDateTime.now().withNano(0).format(ISO_SECONDS)
        }
        val dateOnly = Regex("""^\d{4}-\d{2}-\d{2}$""")
        val dateTime = Regex("""^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?$""")
        return when {
            dateOnly.matches(text) -> text + "T00:00:00"
            dateTime.matches(text) -> {
                val normalized = text.replace(' ', 'T')
                if (normalized.length == 16) normalized + ":00" else normalized
            }
            else -> null
        }
    }

    private companion object {
        val ISO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
