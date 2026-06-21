package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAiChatScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSessionList: () -> Unit,
    onNavigateToMediation: () -> Unit,
    viewModel: AiChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showModeSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .imePadding(),
    ) {
        AiTopBar(
            currentMode = uiState.sceneKey,
            onOpenDrawer = onOpenDrawer,
            onOpenHistory = onNavigateToSessionList,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (uiState.messages.isEmpty()) {
                item {
                    Text(
                        text = "可以直接说你想说的话。\n我会帮你表达，也会帮你理解 TA。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                item {
                    QuickSceneChips(
                        currentScene = uiState.sceneKey,
                        onSceneSelected = { viewModel.setSceneKey(it) },
                    )
                }
            }

            items(uiState.messages) { message ->
                if (message.role == "user") {
                    UserBubble(content = message.content)
                } else {
                    AiReplyBubble(content = message.content)
                }
            }

            if (uiState.isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                                .background(Surface)
                                .padding(16.dp),
                        ) {
                            Text(
                                text = "正在整理...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextTertiary,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        AiInputBar(
            value = uiState.inputText,
            onValueChange = viewModel::onInputChange,
            onSend = viewModel::sendMessage,
            onRewrite = viewModel::rewriteExpression,
            isLoading = uiState.isLoading,
            onOpenModeSheet = { showModeSheet = true },
            onOpenQuote = {},
            showRewriteButton = uiState.sceneKey == "expression_rewrite",
        )
    }

    if (showModeSheet) {
        ModeDrawerSheet(
            onDismiss = { showModeSheet = false },
            onModeSelected = { mode ->
                viewModel.setSceneKey(mode)
                showModeSheet = false
            },
        )
    }

    // 表达改写结果底部弹窗
    if (uiState.showRewriteSheet && uiState.rewriteVersions.isNotEmpty()) {
        RewriteResultSheet(
            original = uiState.rewriteOriginal,
            versions = uiState.rewriteVersions,
            onApply = { viewModel.applyRewrite(it) },
            onDismiss = { viewModel.dismissRewriteSheet() },
        )
    }
}

@Composable
private fun AiTopBar(
    currentMode: String,
    onOpenDrawer: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val modeLabel = when (currentMode) {
        "private_advisor" -> "日常"
        "partner_translate" -> "听懂 TA"
        "expression_rewrite" -> "帮我表达"
        "cold_war" -> "冷静一下"
        "mediation" -> "双人调解"
        else -> "日常"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(AccentLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = "打开侧边栏",
                    tint = Accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Text(
            text = modeLabel,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )

        IconButton(onClick = onOpenHistory) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = "历史会话",
                tint = TextSecondary,
            )
        }
    }
}

@Composable
private fun UserBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(TextPrimary)
                .padding(12.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = Surface,
            )
        }
    }
}

@Composable
private fun AiReplyBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .background(Surface)
                .padding(12.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
        }
    }
}

@Composable
private fun AiInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onRewrite: () -> Unit = {},
    isLoading: Boolean,
    onOpenModeSheet: () -> Unit,
    onOpenQuote: () -> Unit,
    showRewriteButton: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("想说点什么…", color = TextTertiary) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = BorderLight,
                cursorColor = TextPrimary,
            ),
            maxLines = 4,
        )

        Spacer(modifier = Modifier.width(6.dp))

        IconButton(
            onClick = onOpenModeSheet,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = "模式",
                tint = TextSecondary,
            )
        }

        if (showRewriteButton && value.isNotBlank()) {
            IconButton(
                onClick = onRewrite,
                enabled = !isLoading,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "改写",
                    tint = Accent,
                )
            }
        }

        IconButton(
            onClick = onOpenQuote,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = "引用",
                tint = TextSecondary,
            )
        }

        IconButton(
            onClick = onSend,
            enabled = value.isNotBlank() && !isLoading,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (value.isNotBlank() && !isLoading) TextPrimary else BorderLight),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = Surface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class QuickScene(
    val key: String,
    val label: String,
)

private val quickScenes = listOf(
    QuickScene("private_advisor", "帮我理清"),
    QuickScene("partner_translate", "听懂 TA"),
    QuickScene("expression_rewrite", "帮我表达"),
    QuickScene("cold_war", "冷静一下"),
)

@Composable
private fun QuickSceneChips(
    currentScene: String,
    onSceneSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = Modifier.width(4.dp))
        quickScenes.forEach { scene ->
            FilterChip(
                selected = currentScene == scene.key,
                onClick = { onSceneSelected(scene.key) },
                label = {
                    Text(
                        text = scene.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentLight,
                    selectedLabelColor = Accent,
                ),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RewriteResultSheet(
    original: String,
    versions: List<com.couple.translator.core.data.model.AiDto.RewriteVersion>,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "改写结果",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "原文：${original.take(50)}${if (original.length > 50) "..." else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            versions.forEach { version ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = version.style,
                            style = MaterialTheme.typography.labelLarge,
                            color = Accent,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = version.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { onApply(version.content) },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("使用这个版本", color = Accent)
                        }
                    }
                }
            }
        }
    }
}
