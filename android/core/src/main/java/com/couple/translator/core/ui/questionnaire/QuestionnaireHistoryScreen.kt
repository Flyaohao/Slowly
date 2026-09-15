package com.couple.translator.core.ui.questionnaire

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonListCard
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextTertiary
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val profileTypeNames = mapOf(
    "secure" to "安全型依恋",
    "anxious" to "焦虑依恋型",
    "dismissive" to "疏离回避型",
    "fearful" to "恐惧回避型",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionnaireHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToResult: (Long) -> Unit,
    viewModel: QuestionnaireHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var deleteTarget by remember { mutableStateOf<QuestionnaireDto.SubmissionResponse?>(null) }

    deleteTarget?.let { submission ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除记录") },
            text = { Text("确定要删除这条作答记录吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSubmission(submission.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (uiState.error.isNotEmpty()) {
        Snackbar(
            modifier = Modifier.padding(AppSpacing.lg),
            action = {
                TextButton(onClick = { viewModel.loadHistory() }) {
                    Text("重试", color = AppAccent)
                }
            },
        ) {
            Text(uiState.error)
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "作答历史",
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        SkeletonListCard(rows = 4)
                    }

                    uiState.submissions.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppEmptyState(
                                icon = Icons.Outlined.Quiz,
                                title = "还没有作答记录",
                                subtitle = "完成一次测评后，记录会保存在这里。",
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = AppSpacing.screenH),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = AppSpacing.lg),
                        ) {
                            items(uiState.submissions, key = { it.id }) { submission ->
                                SubmissionCard(
                                    submission = submission,
                                    onClick = { onNavigateToResult(submission.id) },
                                    onDelete = { deleteTarget = submission },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmissionCard(
    submission: QuestionnaireDto.SubmissionResponse,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = submission.questionnaireTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = formatDate(submission.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = AppTextTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        submission.profileType?.let { type ->
            Spacer(modifier = Modifier.height(AppSpacing.md))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(AppAccentLight)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = profileTypeNames[type] ?: type,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatItem(
                label = "答题",
                value = "${submission.answeredCount}/${submission.totalQuestions}",
            )
            submission.profileSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AppAccent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppTextTertiary,
        )
    }
}

private fun formatDate(isoString: String?): String {
    if (isoString == null) return ""
    return try {
        // 优先按带时区的格式解析（如 "2026-06-21T12:35:00+08:00"）
        val odt = java.time.OffsetDateTime.parse(isoString)
        odt.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (_: Exception) {
        try {
            // 退化为不带时区的 LocalDateTime（如 "2026-06-21T12:35:00"）
            val dt = LocalDateTime.parse(isoString, DateTimeFormatter.ISO_DATE_TIME)
            dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (_: Exception) {
            isoString.take(16)
        }
    }
}
