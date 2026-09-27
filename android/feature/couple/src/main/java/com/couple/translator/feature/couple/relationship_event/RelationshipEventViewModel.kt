package com.couple.translator.feature.couple.relationship_event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.RelationshipEventDto
import com.couple.translator.feature.couple.data.repository.RelationshipEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 连线上后端的 wire 格式（ISO，秒级）。 */
private val WIRE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** 用户看到的格式。刻意用纯文本输入而不是日期选择器：
 *  事件要精确到「哪天什么时候」，文本一次就能表达完整，也不会因为
 *  系统日期/时间选择器的样式差异在不同 ROM 上跑偏。 */
internal val EVENT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun wireToDisplay(raw: String): String =
    runCatching { LocalDateTime.parse(raw.take(19)).format(EVENT_TIME_FORMATTER) }
        .getOrDefault(raw)

internal fun displayToWire(display: String): String? =
    runCatching {
        LocalDateTime.parse(display.trim(), EVENT_TIME_FORMATTER).format(WIRE_FORMATTER)
    }.getOrNull()

@HiltViewModel
class RelationshipEventListViewModel @Inject constructor(
    private val repository: RelationshipEventRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val items: List<RelationshipEventDto.RelationshipEventResponse> = emptyList(),
        val error: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            repository.listEvents()
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(isLoading = false, items = data?.items.orEmpty())
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "加载失败")
                    }
                }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteEvent(id)
                .onSuccess { load() }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "删除失败") }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }
}

@HiltViewModel
class RelationshipEventEditViewModel @Inject constructor(
    private val repository: RelationshipEventRepository,
) : ViewModel() {

    data class UiState(
        val isEditing: Boolean = false,
        val isLoading: Boolean = false,
        val saving: Boolean = false,
        val title: String = "",
        val timeText: String = "",
        val description: String = "",
        val reason: String = "",
        val polarity: String = RelationshipEventDto.Polarity.NEGATIVE,
        val error: String = "",
        val saved: Boolean = false,
        val deleted: Boolean = false,
    )

    private val _uiState = MutableStateFlow(
        UiState(timeText = LocalDateTime.now().format(EVENT_TIME_FORMATTER))
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var eventId: Long? = null

    /** [id] 为 null 表示新建。 */
    fun start(id: Long?) {
        eventId = id
        if (id == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "") }
            repository.getEvent(id)
                .onSuccess { item ->
                    if (item == null) {
                        _uiState.update { it.copy(isLoading = false, error = "事件不存在") }
                        return@onSuccess
                    }
                    _uiState.update {
                        it.copy(
                            isEditing = true,
                            isLoading = false,
                            title = item.title,
                            timeText = wireToDisplay(item.eventTime),
                            description = item.description.orEmpty(),
                            reason = item.reason,
                            polarity = item.polarity,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "加载失败")
                    }
                }
        }
    }

    fun updateTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun updateTime(value: String) = _uiState.update { it.copy(timeText = value) }
    fun updateDescription(value: String) = _uiState.update { it.copy(description = value) }
    fun updateReason(value: String) = _uiState.update { it.copy(reason = value) }
    fun updatePolarity(value: String) = _uiState.update { it.copy(polarity = value) }
    fun clearError() = _uiState.update { it.copy(error = "") }

    /**
     * 保存。前端先做一遍门槛校验只是为了即时反馈——
     * **真正的门槛在后端**（schema + service 两层），这里不承担安全责任。
     */
    fun save() {
        val s = _uiState.value
        val title = s.title.trim()
        val reason = s.reason.trim()
        if (title.isEmpty()) {
            _uiState.update { it.copy(error = "请填写事件标题") }
            return
        }
        if (reason.length < RelationshipEventDto.REASON_MIN_LEN) {
            _uiState.update {
                it.copy(error = "请写清这件事对你们关系的作用或影响（至少 8 个字）")
            }
            return
        }
        val wireTime = displayToWire(s.timeText)
        if (wireTime == null) {
            _uiState.update { it.copy(error = "时间格式应为 2026-09-20 21:30") }
            return
        }
        val id = eventId
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = "") }
            val result = if (id == null) {
                repository.createEvent(
                    RelationshipEventDto.CreateEventRequest(
                        title = title,
                        eventTime = wireTime,
                        description = s.description.trim().ifBlank { null },
                        reason = reason,
                        polarity = s.polarity,
                    )
                )
            } else {
                repository.updateEvent(
                    id,
                    RelationshipEventDto.UpdateEventRequest(
                        title = title,
                        eventTime = wireTime,
                        description = s.description.trim().ifBlank { null },
                        reason = reason,
                        polarity = s.polarity,
                    )
                )
            }
            result
                .onSuccess {
                    _uiState.update { it.copy(saving = false, saved = true) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(saving = false, error = e.message ?: "保存失败")
                    }
                }
        }
    }

    fun remove() {
        val id = eventId ?: return
        viewModelScope.launch {
            repository.deleteEvent(id)
                .onSuccess { _uiState.update { it.copy(deleted = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "删除失败") }
                }
        }
    }
}
