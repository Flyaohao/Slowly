package com.couple.translator.feature.couple.mediation.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppInfoBanner
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppTagTone
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppOnAccent
import com.couple.translator.core.ui.theme.AppPrimaryGradient
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
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

            // 去AI味 P-3f：表单页也有第一眼——渐变 hero 与首页/关系页同一套语言，
            // 先给「这是一场正式的对话」的仪式感，再进表单。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.xl))
                    .background(AppPrimaryGradient)
                    .border(0.5.dp, AppOnAccent.copy(alpha = 0.25f), RoundedCornerShape(AppRadius.xl))
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                Text(
                    text = "共同调解室",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppOnAccent.copy(alpha = 0.85f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "发起调解",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = AppOnAccent,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "把事情讲清楚，军师在场，双方当面把话说开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppOnAccent.copy(alpha = 0.8f),
                )
            }

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

            // 风格双卡：名称+描述一张卡说清，选中描边高亮，替代挤在一行的 FilterChip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.styles.forEach { style ->
                    StyleCard(
                        selected = uiState.styleKey == style.key,
                        label = style.label,
                        description = style.description,
                        onClick = { viewModel.update { it.copy(styleKey = style.key) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (uiState.error.isNotBlank()) {
                AppInfoBanner(
                    text = uiState.error,
                    tone = AppTagTone.Warm,
                    modifier = Modifier.fillMaxWidth(),
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

/** 军师风格选择卡：选中=品牌色描边+浅底，未选中=静默底。 */
@Composable
private fun StyleCard(
    selected: Boolean,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(if (selected) AppAccentFaint else AppSurfaceMuted)
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) AppAccent else AppBorderLight,
                shape = RoundedCornerShape(AppRadius.lg),
            )
            .pressFeedback(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) AppAccent else AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
        )
    }
}
