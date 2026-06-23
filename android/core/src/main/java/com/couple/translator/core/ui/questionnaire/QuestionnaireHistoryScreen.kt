package com.couple.translator.core.ui.questionnaire

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary
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

    // Delete confirmation dialog
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
                    Text("删除", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    // Error snackbar
    if (uiState.error.isNotEmpty()) {
        androidx.compose.material3.Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { viewModel.loadHistory() }) {
                    Text("重试", color = Accent)
                }
            },
        ) {
            Text(uiState.error)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("作答历史") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        if (uiState.isLoading) {
            LoadingIndicator()
            return@PullToRefreshLayout
        }

        if (uiState.submissions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "暂无作答记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "完成问卷后，记录会显示在这里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                }
            }
            return@PullToRefreshLayout
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
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

@Composable
private fun SubmissionCard(
    submission: QuestionnaireDto.SubmissionResponse,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Header: title + delete
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatDate(submission.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Profile type badge
            submission.profileType?.let { type ->
                val typeName = profileTypeNames[type] ?: type
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentLight)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = typeName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatItem(
                    label = "答题",
                    value = "${submission.answeredCount}/${submission.totalQuestions}",
                )
                submission.profileSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
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
            color = Accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
        )
    }
}

private fun formatDate(isoString: String?): String {
    if (isoString == null) return ""
    return try {
        // Try with timezone offset first (e.g. "2026-06-21T12:35:00+08:00")
        val odt = java.time.OffsetDateTime.parse(isoString)
        odt.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (_: Exception) {
        try {
            // Fallback to plain LocalDateTime (e.g. "2026-06-21T12:35:00")
            val dt = LocalDateTime.parse(isoString, DateTimeFormatter.ISO_DATE_TIME)
            dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (_: Exception) {
            // Last resort: just take the first 16 chars
            isoString.take(16)
        }
    }
}
