package com.couple.translator.feature.couple.relationship_event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.feature.couple.data.model.RelationshipEventDto

// --------------------------------------------------------------------------- //
// 列表
// --------------------------------------------------------------------------- //

@Composable
fun RelationshipEventScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Long?) -> Unit,
    viewModel: RelationshipEventListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = { AppBackTopBar(onBack = onNavigateBack, title = "纪念事件") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNavigateToEdit(null) },
                containerColor = AppAccent,
                contentColor = Color.White,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("记一件事") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH),
        ) {
            Text(
                text = "记下你们之间真实发生过的事。每件事都要写清它对你们关系起了什么作用 —— " +
                    "军师只依据有方向的事件去理解你们，凭空记一件事反而会把它带偏。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(AppSpacing.md))

            if (uiState.items.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有记录。\n想起来哪件事，就点右下角记下来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextTertiary,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.items, key = { it.id }) { item ->
                    EventCard(
                        item = item,
                        onClick = { onNavigateToEdit(item.id) },
                        onDelete = { viewModel.delete(item.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun EventCard(
    item: RelationshipEventDto.RelationshipEventResponse,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val positive = item.polarity == RelationshipEventDto.Polarity.POSITIVE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppSurface)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolynomialTag(positive = positive)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = wireToDisplay(item.eventTime),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除",
                    tint = AppTextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextPrimary,
            fontWeight = FontWeight.Medium,
        )
        if (!item.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.description!!,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
                maxLines = 2,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "作用：" + item.reason,
            style = MaterialTheme.typography.bodySmall,
            color = if (positive) AppAccent else AppTextSecondary,
            maxLines = 3,
        )
    }
}

@Composable
private fun PolynomialTag(positive: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (positive) AppAccentLight else AppBorderLight)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (positive) "积极" else "消极",
            style = MaterialTheme.typography.labelSmall,
            color = if (positive) AppAccent else AppTextSecondary,
        )
    }
}

// --------------------------------------------------------------------------- //
// 新建 / 编辑
// --------------------------------------------------------------------------- //

@Composable
fun RelationshipEventEditScreen(
    eventId: Long?,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: RelationshipEventEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(eventId) { viewModel.start(eventId) }
    LaunchedEffect(uiState.saved, uiState.deleted) {
        if (uiState.saved || uiState.deleted) onSaved()
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = if (uiState.isEditing) "编辑事件" else "记一件事",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH),
        ) {
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("发生了什么") },
                placeholder = { Text("例如：因为回消息慢吵了一架") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                colors = fieldColors(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.timeText,
                onValueChange = viewModel::updateTime,
                label = { Text("发生时间") },
                placeholder = { Text("2026-09-20 21:30") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                colors = fieldColors(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.description,
                onValueChange = viewModel::updateDescription,
                label = { Text("经过（选填）") },
                placeholder = { Text("简要写下当时的情况") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                shape = RoundedCornerShape(AppRadius.md),
                colors = fieldColors(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "这件事对你们的关系起了什么作用",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                PolarityOption(
                    label = "积极作用",
                    selected = uiState.polarity == RelationshipEventDto.Polarity.POSITIVE,
                    onClick = {
                        viewModel.updatePolarity(RelationshipEventDto.Polarity.POSITIVE)
                    },
                )
                Spacer(modifier = Modifier.width(10.dp))
                PolarityOption(
                    label = "消极作用",
                    selected = uiState.polarity == RelationshipEventDto.Polarity.NEGATIVE,
                    onClick = {
                        viewModel.updatePolarity(RelationshipEventDto.Polarity.NEGATIVE)
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.reason,
                onValueChange = viewModel::updateReason,
                label = { Text("为什么要记下来（必填）") },
                placeholder = { Text("说清它暴露或印证了什么，例如：暴露出我们在压力下会进入追问—退缩循环") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                shape = RoundedCornerShape(AppRadius.md),
                colors = fieldColors(),
                supportingText = {
                    Text(
                        text = "没有明确作用的事件会被军师当成噪声，因此不能保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                    )
                },
            )

            Spacer(modifier = Modifier.height(AppSpacing.section))

            AppPrimaryButton(
                text = if (uiState.isEditing) "保存修改" else "记下来",
                onClick = { viewModel.save() },
            )

            if (uiState.isEditing) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.md))
                        .clickable { viewModel.remove() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "删除这条记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSize.button))
        }
    }
}

@Composable
private fun PolarityOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(if (selected) AppAccentLight else AppSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) AppAccent else AppTextSecondary,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppAccent,
    unfocusedBorderColor = AppBorderLight,
    focusedContainerColor = AppSurface,
    unfocusedContainerColor = AppSurface,
)
