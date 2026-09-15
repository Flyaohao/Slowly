package com.couple.translator.feature.couple.dual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.components.SkeletonTopBar
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitRecordScreen(
    eventId: Long,
    onNavigateBack: () -> Unit,
    onSubmitSuccess: () -> Unit,
    viewModel: SubmitRecordViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.submitted) {
        if (uiState.submitted) {
            onSubmitSuccess()
        }
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    if (uiState.isLoading) {
        SubmitRecordSkeleton()
        return
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "提交我的视角",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenH),
        ) {
            Text(
                text = "写下你对这件事的感受和想法",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "对方完成前无法看到你的内容",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = uiState.content,
                onValueChange = { viewModel.updateContent(it) },
                placeholder = { Text("从你的角度，发生了什么？你的感受是？") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppBorderLight,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            AppAccentButton(
                text = "提交",
                onClick = { viewModel.submitRecord(eventId) },
            )
        }
    }
}

@Composable
private fun SubmitRecordSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        SkeletonTopBar()
        Column(modifier = Modifier.padding(horizontal = AppSpacing.screenH)) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            SkeletonPageHeader()
            Spacer(modifier = Modifier.height(AppSpacing.section))
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                shape = RoundedCornerShape(AppRadius.md),
            )
            Spacer(modifier = Modifier.height(AppSpacing.block))
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(AppSize.button),
                shape = RoundedCornerShape(AppRadius.pill),
            )
        }
    }
}
