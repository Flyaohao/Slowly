package com.couple.translator.feature.couple.ai

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdWarScreen(
    onNavigateBack: () -> Unit,
    onNavigateToComposeLetter: (String) -> Unit,
    viewModel: ColdWarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "冷战开解",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
        ) {
            StepIndicator(currentStep = uiState.currentStep, steps = uiState.steps)

            Spacer(modifier = Modifier.height(24.dp))

            when (uiState.currentStep) {
                0 -> GoalStep(uiState, viewModel::onInputChange, viewModel::nextStep)
                1 -> FaceVsNeedStep(uiState, viewModel::onInputChange, viewModel::nextStep, viewModel::prevStep)
                2 -> ApproachStep(uiState, viewModel::onInputChange, viewModel::nextStep, viewModel::prevStep)
                3 -> OpeningLineStep(
                    uiState,
                    onNavigateToComposeLetter = onNavigateToComposeLetter,
                    onBack = viewModel::prevStep,
                )
            }

            if (uiState.isLoading) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = AppAccent,
                )
            }

            if (uiState.error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, steps: List<ColdWarStep>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, step ->
            val isActive = index == currentStep
            val isCompleted = index < currentStep
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(
                            when {
                                isActive -> AppAccent
                                isCompleted -> AppAccent.copy(alpha = 0.6f)
                                else -> AppSurface
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (isActive || isCompleted) AppSurface else AppTextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) AppAccent else AppTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun GoalStep(
    uiState: ColdWarUiState,
    onInputChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            Text(
                text = "你想结束冷战，真实目标是什么？",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "比如：我想和好 / 我想让对方理解我 / 我想解释误会",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextInputField(
                value = uiState.userInput,
                onValueChange = onInputChange,
                label = "你的真实目标",
                placeholder = "写下你的想法...",
                singleLine = false,
            )
            Spacer(modifier = Modifier.height(16.dp))
            AppPrimaryButton(text = "下一步", onClick = onNext, enabled = uiState.userInput.isNotBlank())
        }
    }
}

@Composable
private fun FaceVsNeedStep(
    uiState: ColdWarUiState,
    onInputChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            if (uiState.goalAnalysis.isNotBlank()) {
                Text(text = uiState.goalAnalysis, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = "哪些是面子，哪些是真实需求？",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "比如：面子=不想先低头，需求=想被关心",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextInputField(
                value = uiState.userInput,
                onValueChange = onInputChange,
                label = "你的分析",
                placeholder = "写下你的想法...",
                singleLine = false,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                AppSecondaryButton(
                    text = "上一步",
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                AppPrimaryButton(
                    text = "下一步",
                    onClick = onNext,
                    enabled = uiState.userInput.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ApproachStep(
    uiState: ColdWarUiState,
    onInputChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            if (uiState.faceVsNeed.isNotBlank()) {
                Text(text = uiState.faceVsNeed, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = "你觉得现在适合怎么做？",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "主动靠近：先打破沉默 / 给空间：等双方冷静",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextInputField(
                value = uiState.userInput,
                onValueChange = onInputChange,
                label = "你的选择",
                placeholder = "主动靠近 / 先给空间",
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                AppSecondaryButton(
                    text = "上一步",
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                AppPrimaryButton(
                    text = "生成开场白",
                    onClick = onNext,
                    enabled = uiState.userInput.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OpeningLineStep(
    uiState: ColdWarUiState,
    onNavigateToComposeLetter: (String) -> Unit,
    onBack: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            if (uiState.approachReason.isNotBlank()) {
                // 策略由模型给出（approach / give_space），此前该字段落库了却无处显示，
                // 界面永远按硬编码分支走。这里把它显式呈现出来。
                Text(
                    text = if (uiState.approach == "give_space") "建议策略：先给彼此空间" else "建议策略：主动破冰",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = uiState.approachReason, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "为你生成的开场白",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))

            uiState.openingLines.forEach { line ->
                OpeningLineCard(
                    text = line,
                    onCopy = {},
                    onEdit = {},
                    onSendAsLetter = { onNavigateToComposeLetter(line) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (uiState.avoidReminders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "提醒避免",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppErrorRed,
                )
                uiState.avoidReminders.forEach { reminder ->
                    Text(
                        text = "• $reminder",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            AppSecondaryButton(
                text = "返回上一步",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
