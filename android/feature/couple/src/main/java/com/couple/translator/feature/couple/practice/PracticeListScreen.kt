package com.couple.translator.feature.couple.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.model.PracticeDto
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonPlainListPage
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToResult: (Long) -> Unit,
    viewModel: PracticeListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    if (uiState.isLoading) {
        SkeletonPlainListPage(cardRows = 4)
        return
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "关系练习",
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "通过结构化练习提升沟通能力",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 做过的练习：结果页里才有 AI 整理，没有这个入口等于做完就找不回来
            if (uiState.records.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "我的练习记录",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(uiState.records.take(5)) { record ->
                    PracticeRecordItem(
                        title = uiState.practices
                            .firstOrNull { it.id == record.practiceId }
                            ?.title ?: "关系练习",
                        status = record.status,
                        date = record.createdAt?.take(10),
                        onClick = { onNavigateToResult(record.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }

            items(uiState.practices) { practice ->
                PracticeListItem(
                    practice = practice,
                    onClick = {
                        viewModel.startPractice(practice.id) { recordId ->
                            onNavigateToDetail(recordId)
                        }
                    },
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
        }
    }
}

@Composable
private fun PracticeListItem(
    practice: PracticeDto.PracticeResponse,
    onClick: () -> Unit,
) {
    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = practiceTypeEmoji(practice.practiceType),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = practice.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (practice.description != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = practice.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                        maxLines = 2,
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = AppTextTertiary,
            )
        }
    }
}

@Composable
private fun PracticeRecordItem(
    title: String,
    status: String,
    date: String?,
    onClick: () -> Unit,
) {
    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        practiceStatusText(status),
                        date,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = AppTextTertiary,
            )
        }
    }
}

private fun practiceStatusText(status: String): String = when (status) {
    "initiated" -> "等待对方完成"
    "both_completed" -> "双方已完成"
    "summarized" -> "AI 已整理"
    else -> "进行中"
}

private fun practiceTypeEmoji(type: String): String = when (type) {
    "listening" -> "\uD83D\uDC42"
    "thanks" -> "\uD83D\uDE4F"
    "apology" -> "\u2764\uFE0F"
    "need" -> "\uD83D\uDCA1"
    "reconcile" -> "\uD83E\uDD1D"
    "comfort" -> "\uD83E\uDDE1"
    "clarify" -> "\uD83D\uDCAC"
    else -> "\u2764\uFE0F"
}
