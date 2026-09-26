package com.couple.translator.feature.couple.dual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppMarkdownText
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPerspectiveDetailScreen(
    eventId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToSubmitRecord: (Long) -> Unit,
    viewModel: DualPerspectiveDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(eventId) {
        viewModel.loadEvent(eventId)
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.loadEvent(eventId) })
    }

    if (uiState.isLoading) {
        SkeletonDetailPage()
        return
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "事件详情",
            )
        },
    ) { padding ->
        val event = uiState.event ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenH),
        ) {
            AppPageHeader(
                title = event.title,
                subtitle = event.eventTime?.take(10),
            )
            Spacer(modifier = Modifier.height(24.dp))

            // P0-1（契约 §2.1）：reveal 前服务端只回本人 record；排序仍由 FE 兜底一次——
            // 先本人后对方（拿不到 myUserId 时保持服务端顺序，服务端已按同样规则排过）。
            val records = event.records.sortedBy { record ->
                if (uiState.myUserId != null && record.userId != uiState.myUserId) 1 else 0
            }
            val viewerRecord = records.firstOrNull()

            if (uiState.revealed && records.size >= 2) {
                DualPerspectiveComparison(
                    record1 = records[0],
                    record2 = records[1],
                )
            } else if (viewerRecord != null) {
                val isMyRecord = uiState.myUserId == null || viewerRecord.userId == uiState.myUserId
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AppAccentLight,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = if (isMyRecord) "我的视角" else "对方视角",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppAccent,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = viewerRecord.content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    // partnerSubmitted：true = 对方已提交（内容被服务端过滤掉，等确认公开）
                    text = if (event.partnerSubmitted == true) {
                        "双方都已写下，确认后公开并排查看"
                    } else {
                        "对方尚未提交，双方都提交后可并排查看"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 「提交我的视角」只在我还没写时出现：已提交时不再与「确认公开」并排重复。
            // 判定优先用本人 record；myUserId 拿不到时按 partner_submitted 降级；
            // 两者都没有（旧后端 + 身份未知）才沿用旧条件，保证不崩。
            val showSubmitButton = when {
                uiState.myUserId != null -> records.none { it.userId == uiState.myUserId }
                event.partnerSubmitted != null -> records.isEmpty()
                else -> records.isEmpty() || (records.size == 1 && !uiState.revealed)
            }
            if (showSubmitButton) {
                AppAccentButton(
                    text = "提交我的视角",
                    onClick = { onNavigateToSubmitRecord(eventId) },
                )
            }

            if (event.status == "both_sides" && !uiState.revealed) {
                Spacer(modifier = Modifier.height(12.dp))
                AppSecondaryButton(
                    text = "确认公开，查看对方视角",
                    onClick = { viewModel.revealRecords(eventId) },
                )
            }

            // === AI 双视角对照总结：只在同一事件的双方视角都可见后才有意义 ===
            if (uiState.revealed && uiState.canSummarize) {
                Spacer(modifier = Modifier.height(24.dp))
                AiSummarySection(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AiSummarySection(viewModel: DualPerspectiveDetailViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Text(
        text = "AI 对照总结",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = AppTextPrimary,
    )
    Spacer(modifier = Modifier.height(12.dp))

    when {
        uiState.isSummaryGenerating -> {
            AiThinkingPanel(
                thinking = uiState.summaryThinkingText,
                isLive = uiState.isSummaryThinking,
                seconds = uiState.summaryThinkingSeconds,
            )
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = AppSurface,
                contentPadding = PaddingValues(16.dp),
            ) {
                if (uiState.summaryStreamText.isBlank()) {
                    AiWaitingBubble()
                } else {
                    AiStreamingText(content = uiState.summaryStreamText, isStreaming = true)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.stopSummary() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止生成", color = AppTextSecondary)
            }
        }

        uiState.summaryText.isNotBlank() -> {
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = AppSurface,
                contentPadding = PaddingValues(16.dp),
            ) {
                AppMarkdownText(markdown = uiState.summaryText)
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.startSummary() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重新生成总结", color = AppTextSecondary)
            }
        }

        uiState.summaryLoaded -> {
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = AppSurface,
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "把两份视角放在一起看看：哪些是共识、哪些错过了彼此、各自真正在意什么",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AppPrimaryButton(
                        text = "生成 AI 对照总结",
                        onClick = { viewModel.startSummary() },
                    )
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun DualPerspectiveComparison(
    record1: DualPerspectiveDto.DualRecordResponse,
    record2: DualPerspectiveDto.DualRecordResponse,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PerspectiveCard(
            title = "我的视角",
            content = record1.content,
            modifier = Modifier.weight(1f),
        )
        PerspectiveCard(
            title = "对方视角",
            content = record2.content,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PerspectiveCard(
    title: String,
    content: String,
    modifier: Modifier = Modifier,
) {
    AppCard(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = AppAccent,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
