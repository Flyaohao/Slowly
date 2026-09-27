package com.couple.translator.feature.couple.mediation.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.feature.couple.data.model.MediationRoomDto
import com.couple.translator.feature.couple.data.repository.MediationRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoomCreateUiState(
    val name: String = "",
    val eventTime: String = "",
    val causeText: String = "",
    val processText: String = "",
    val currentText: String = "",
    val styleKey: String = "gentle_empathy",
    val styles: List<MediationRoomDto.StyleItem> = emptyList(),
    val submitting: Boolean = false,
    val error: String = "",
    /** 创建成功的新房间 id（消费一次即清） */
    val createdRoomId: Long? = null,
)

@HiltViewModel
class MediationRoomCreateViewModel @Inject constructor(
    private val repository: MediationRoomRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomCreateUiState())
    val uiState: StateFlow<RoomCreateUiState> = _uiState

    init {
        viewModelScope.launch {
            repository.getStyles().onSuccess { styles ->
                _uiState.update { it.copy(styles = styles.orEmpty()) }
            }
        }
    }

    fun update(transform: (RoomCreateUiState) -> RoomCreateUiState) {
        _uiState.update(transform)
    }

    fun clearError() = _uiState.update { it.copy(error = "") }
    fun consumeCreated() = _uiState.update { it.copy(createdRoomId = null) }

    /** 事件卡四项必填（§二），一次成型。 */
    fun submit() {
        val s = _uiState.value
        if (s.submitting) return
        if (s.name.isBlank() || s.eventTime.isBlank() || s.causeText.isBlank() ||
            s.processText.isBlank() || s.currentText.isBlank()
        ) {
            _uiState.update { it.copy(error = "四项都要填写：名称、时间、起因、经过、现状") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, error = "") }
            repository.createRoom(
                MediationRoomDto.CreateRoomRequest(
                    name = s.name.trim(),
                    eventTime = s.eventTime.trim(),
                    causeText = s.causeText.trim(),
                    processText = s.processText.trim(),
                    currentText = s.currentText.trim(),
                    styleKey = s.styleKey,
                )
            ).fold(
                onSuccess = { room ->
                    if (room != null) {
                        _uiState.update { it.copy(submitting = false, createdRoomId = room.id) }
                    } else {
                        _uiState.update { it.copy(submitting = false, error = "创建失败") }
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(submitting = false, error = e.message ?: "创建失败") }
                },
            )
        }
    }
}

/**
 * 创建流：事件卡表单（§二）。
 * 三段结构化输入（起因/经过/现状）而非自由长文——事件卡就是军师的静态决策材料。
 */
@Composable
fun MediationRoomCreateScreen(
    onNavigateBack: () -> Unit,
    onRoomCreated: (Long) -> Unit,
    viewModel: MediationRoomCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.createdRoomId) {
        val id = uiState.createdRoomId
        if (id != null) {
            viewModel.consumeCreated()
            onRoomCreated(id)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppBackTopBar(onBack = onNavigateBack, title = "发起调解")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            SectionTitle("这次要调解什么")

            TextInputField(
                value = uiState.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = "调解室名称（事件名）",
                placeholder = "如：周末加班没去看电影",
            )
            TextInputField(
                value = uiState.eventTime,
                onValueChange = { v -> viewModel.update { it.copy(eventTime = v) } },
                label = "事件时间",
                placeholder = "如：2026-09-27 晚上",
            )

            SectionTitle("事情的来龙去脉")

            TextInputField(
                value = uiState.causeText,
                onValueChange = { v -> viewModel.update { it.copy(causeText = v) } },
                label = "起因",
                placeholder = "是什么引起的",
                singleLine = false,
            )
            TextInputField(
                value = uiState.processText,
                onValueChange = { v -> viewModel.update { it.copy(processText = v) } },
                label = "经过",
                placeholder = "后来发生了什么",
                singleLine = false,
            )
            TextInputField(
                value = uiState.currentText,
                onValueChange = { v -> viewModel.update { it.copy(currentText = v) } },
                label = "现状",
                placeholder = "现在是什么状态",
                singleLine = false,
            )

            SectionTitle("军师风格")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.styles.forEach { style ->
                    FilterChip(
                        selected = uiState.styleKey == style.key,
                        onClick = { viewModel.update { it.copy(styleKey = style.key) } },
                        label = { Text(style.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppAccent,
                        ),
                    )
                }
            }
            uiState.styles.firstOrNull { it.key == uiState.styleKey }?.let { style ->
                Text(
                    text = style.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                )
            }

            if (uiState.error.isNotBlank()) {
                Text(
                    text = uiState.error,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            AppPrimaryButton(
                text = if (uiState.submitting) "创建中…" else "创建调解室",
                onClick = viewModel::submit,
                enabled = !uiState.submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
