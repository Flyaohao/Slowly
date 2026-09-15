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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppSpacing

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

            if (record.summary != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "AI 总结",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = record.summary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun practiceStatusText(status: String): String = when (status) {
    "initiated" -> "等待对方完成"
    "both_completed" -> "双方已完成"
    "summarized" -> "AI 已总结"
    else -> status
}
