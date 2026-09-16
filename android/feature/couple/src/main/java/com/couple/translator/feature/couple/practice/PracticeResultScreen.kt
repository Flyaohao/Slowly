package com.couple.translator.feature.couple.practice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppMarkdownText
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeResultScreen(
    recordId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToAddMuseum: () -> Unit,
    viewModel: PracticeResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(recordId) {
        viewModel.loadResult(recordId)
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.loadResult(recordId) })
    }

    if (uiState.isLoading) {
        SkeletonDetailPage()
        return
    }

    val record = uiState.record ?: return

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "练习结果",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenH),
        ) {
            AppPageHeader(title = record.practiceTitle)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = practiceStatusText(record.status),
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
            )

            if (record.mySubmission != null) {
                Spacer(modifier = Modifier.height(24.dp))
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = "我的提交",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppAccent,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = record.mySubmission,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (record.partnerSubmission != null) {
                Spacer(modifier = Modifier.height(16.dp))
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AppAccentLight,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = "对方的提交",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppAccent,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = record.partnerSubmission,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // === AI 练习整理：至少一方作答后才出现 ===
            if (uiState.canSummarize) {
                Spacer(modifier = Modifier.height(24.dp))
                AiSummarySection(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AiSummarySection(viewModel: PracticeResultViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Text(
        text = "AI 整理",
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
                Text("重新整理", color = AppTextSecondary)
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
                        text = "把这次练习的两份作答放在一起：看到了什么、哪里呼应、哪里还没对上",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AppPrimaryButton(
                        text = "生成 AI 整理",
                        onClick = { viewModel.startSummary() },
                    )
                }
            }
        }

        else -> Unit
    }
}

private fun practiceStatusText(status: String): String = when (status) {
    "initiated" -> "等待对方完成"
    "both_completed" -> "双方已完成"
    "summarized" -> "AI 已总结"
    else -> status
}
