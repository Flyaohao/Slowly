package com.couple.translator.core.ui.questionnaire

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionnaireIntroScreen(
    onNavigateBack: () -> Unit,
    onNavigateToQuestionnaire: (Long) -> Unit,
    onNavigateToHistory: () -> Unit = {},
    viewModel: QuestionnaireIntroViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is QuestionnaireIntroUiEvent.NavigateToQuestionnaire -> {
                    onNavigateToQuestionnaire(event.questionnaireId)
                }
                is QuestionnaireIntroUiEvent.ShowError -> {}
            }
        }
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.clearError() },
        )
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "关系画像",
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .padding(padding)
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                SkeletonPageHeader()
                Spacer(modifier = Modifier.height(AppSpacing.section))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            return@Scaffold
        }

        // 入场动画
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, easing = FastOutSlowInEasing)) +
                        slideInVertically(
                            animationSpec = tween(500, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 6 },
                        ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "了解你们的关系模式",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = uiState.questionnaire?.description
                            ?: "通过回答一系列问题，我们将为你生成专属的关系画像，帮助你们更好地理解彼此。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val total = uiState.totalQuestions.takeIf { it > 0 }
                        ?: uiState.questionnaire?.let { 50 }
                        ?: 0
                    if (uiState.isSubmitted) {
                        Text(
                            text = "你已完成过此问卷，可以重新作答",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextTertiary,
                            textAlign = TextAlign.Center,
                        )
                    } else if (uiState.answeredCount > 0) {
                        Text(
                            text = "你有未完成的进度（已答 ${uiState.answeredCount}/$total 题），可继续作答",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppAccent,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            text = "问卷共 $total 题，约需 12-15 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    AppPrimaryButton(
                        text = if (uiState.isSubmitted) "重新作答" else if (uiState.answeredCount > 0) "继续作答" else "开始作答",
                        onClick = { viewModel.onStartQuestionnaire() },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(modifier = Modifier.fillMaxWidth(fraction = 0.6f)) {
                        AppSecondaryButton(
                            text = "作答历史",
                            onClick = onNavigateToHistory,
                        )
                    }
                }
            }
        }
    }
}
