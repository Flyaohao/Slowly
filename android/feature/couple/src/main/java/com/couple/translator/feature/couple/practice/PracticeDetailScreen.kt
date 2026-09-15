package com.couple.translator.feature.couple.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.components.SkeletonTopBar
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary

private val practiceSteps = mapOf(
    "listening" to listOf("写下你想对伴侣说的话", "用你自己的话复述你理解的对方的意思"),
    "thanks" to listOf("写下你感谢伴侣的一件事", "写下这件事对你的意义"),
    "apology" to listOf("描述你做了什么", "说明你理解这如何影响了对方", "写下你打算怎么改变"),
    "need" to listOf("用「我感到…因为我需要…」的句式表达"),
    "reconcile" to listOf("回顾冲突中发生了什么", "写下你的感受", "写下对方可能的感受", "你希望怎么修复"),
    "comfort" to listOf("写下伴侣可能正在经历的", "写下你想给对方的安慰"),
    "clarify" to listOf("写下你理解的对方的意思", "写下你觉得被误解的地方", "用更清晰的方式重新表达"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeDetailScreen(
    practiceId: Long,
    recordId: Long,
    onNavigateBack: () -> Unit,
    onSubmitSuccess: (Long) -> Unit,
    viewModel: PracticeDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(recordId) {
        viewModel.loadRecord(recordId)
    }

    LaunchedEffect(uiState.submitted) {
        if (uiState.submitted) onSubmitSuccess(recordId)
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    if (uiState.isLoading && uiState.record == null) {
        PracticeDetailSkeleton()
        return
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = uiState.record?.practiceTitle ?: "练习",
            )
        },
    ) { padding ->
        val record = uiState.record ?: return@Scaffold
        val steps = practiceSteps[record.practiceType] ?: listOf("写下你的想法")
        val currentStep = uiState.currentStep.coerceAtMost(steps.lastIndex)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenH),
        ) {
            LinearProgressIndicator(
                progress = { (currentStep + 1).toFloat() / steps.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = AppAccent,
                trackColor = AppAccentLight,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Text(
                    text = "步骤 ${currentStep + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "/ ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = steps[currentStep],
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.content,
                onValueChange = { viewModel.updateContent(it) },
                placeholder = { Text("写下你的想法...") },
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

            if (currentStep < steps.lastIndex) {
                AppAccentButton(
                    text = "下一步",
                    onClick = { viewModel.nextStep() },
                )
            } else {
                AppAccentButton(
                    text = "提交练习",
                    onClick = { viewModel.submitPractice(recordId) },
                )
            }
        }
    }
}

@Composable
private fun PracticeDetailSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        SkeletonTopBar()
        Column(modifier = Modifier.padding(horizontal = AppSpacing.screenH)) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            SkeletonPageHeader(showSubtitle = false)
            Spacer(modifier = Modifier.height(AppSpacing.section))
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(180.dp),
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
