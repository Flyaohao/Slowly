package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdWarScreen(
    onNavigateBack: () -> Unit,
    onNavigateToComposeLetter: (String) -> Unit,
    viewModel: ColdWarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("冷战开解") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
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
                    color = Accent,
                )
            }

            if (uiState.error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
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
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isActive -> Accent
                            isCompleted -> Accent.copy(alpha = 0.6f)
                            else -> Surface
                        },
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 0.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (isActive || isCompleted) Surface else TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) Accent else TextSecondary,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "你想结束冷战，真实目标是什么？",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "比如：我想和好 / 我想让对方理解我 / 我想解释误会",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
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
            PrimaryButton(text = "下一步", onClick = onNext, enabled = uiState.userInput.isNotBlank())
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                color = TextSecondary,
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
                androidx.compose.material3.OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) { Text("上一步") }
                Spacer(modifier = Modifier.width(12.dp))
                PrimaryButton(text = "下一步", onClick = onNext, enabled = uiState.userInput.isNotBlank(), modifier = Modifier.weight(1f))
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                color = TextSecondary,
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
                androidx.compose.material3.OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) { Text("上一步") }
                Spacer(modifier = Modifier.width(12.dp))
                PrimaryButton(text = "生成开场白", onClick = onNext, enabled = uiState.userInput.isNotBlank(), modifier = Modifier.weight(1f))
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (uiState.approachReason.isNotBlank()) {
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
                    color = MaterialTheme.colorScheme.error,
                )
                uiState.avoidReminders.forEach { reminder ->
                    Text(
                        text = "• $reminder",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("返回上一步") }
        }
    }
}
