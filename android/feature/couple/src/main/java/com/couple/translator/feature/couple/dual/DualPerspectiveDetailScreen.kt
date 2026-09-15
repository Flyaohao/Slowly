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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppSpacing
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

            if (uiState.revealed && event.records.size >= 2) {
                DualPerspectiveComparison(
                    record1 = event.records[0],
                    record2 = event.records[1],
                )
            } else if (event.records.isNotEmpty()) {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AppAccentLight,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = "我的视角",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppAccent,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = event.records.first().content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "对方尚未提交，双方都提交后可并排查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (event.records.isEmpty() || (event.records.size == 1 && !uiState.revealed)) {
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
        }
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
