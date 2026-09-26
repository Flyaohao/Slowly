package com.couple.translator.feature.couple.memorycard

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppMarkdownText
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary

/**
 * 回忆卡片页：一条记录（目前是纪念日）的 AI 长文，一段叙事 + 三个重访问题。
 * 从列表页带 targetType/targetId/itemTitle 进入，一条条目一张卡片。
 *
 * 后端 memory-card 端点本身在收敛期冻结（10006），页面保留备查；文案里
 * 不再出现「愿望」——愿望清单已随博物馆/练习一起冻结，不得再宣传。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryCardScreen(
    targetType: String,
    targetId: Long,
    itemTitle: String,
    onNavigateBack: () -> Unit,
    viewModel: MemoryCardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(targetType, targetId, itemTitle) {
        viewModel.initPage(targetType, targetId, itemTitle)
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.startGeneration() })
    }
    if (uiState.saveError.isNotEmpty()) {
        ErrorDialog(message = uiState.saveError, onDismiss = { viewModel.saveToMuseum() })
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "回忆卡片",
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
            Text(
                text = itemTitle.ifBlank { "回忆卡片" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                uiState.isGenerating -> {
                    AiThinkingPanel(
                        thinking = uiState.thinkingText,
                        isLive = uiState.isThinking,
                        seconds = uiState.thinkingSeconds,
                    )
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppSurface,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        if (uiState.streamText.isBlank()) {
                            AiWaitingBubble()
                        } else {
                            AiStreamingText(content = uiState.streamText, isStreaming = true)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.stopGeneration() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("停止生成", color = AppTextSecondary)
                    }
                }

                uiState.cardText.isNotBlank() -> {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppSurface,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        AppMarkdownText(markdown = uiState.cardText)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    when {
                        uiState.savedToMuseum -> {
                            AppSecondaryButton(
                                text = "已存入纪念馆",
                                onClick = {},
                            )
                        }

                        uiState.isSavingToMuseum -> {
                            AppSecondaryButton(
                                text = "正在存入…",
                                onClick = {},
                            )
                        }

                        else -> {
                            AppPrimaryButton(
                                text = "存入纪念馆",
                                onClick = { viewModel.saveToMuseum() },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.startGeneration() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重新生成卡片", color = AppTextSecondary)
                    }
                }

                uiState.cardLoaded -> {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppSurface,
                        contentPadding = PaddingValues(20.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "把这条${if (uiState.targetType == "anniversary") "纪念日" else "记录"}写成一张回忆卡片，配上三个适合你们一起聊的问题",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AppPrimaryButton(
                                text = "生成回忆卡片",
                                onClick = { viewModel.startGeneration() },
                            )
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}
