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

    // 场景清单统一来自 AiSceneCatalog（远端拉取，collectAsState 保证拉到后会重组）
    val scenes by AiSceneCatalog.scenes.collectAsState()
    val quickChips = scenes.filter { it.showInQuickChips }

    LaunchedEffect(uiState.messages.size, uiState.streamingContent, uiState.thinkingContent) {
        val extra = if (uiState.streamingContent.isNotEmpty() || uiState.thinkingContent.isNotEmpty()) 1 else 0
        val last = uiState.messages.size + extra - 1
        if (last >= 0) {
            listState.animateScrollToItem(last)
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
                        scenes = quickChips,
                        currentScene = uiState.sceneKey,
                        onSceneSelected = viewModel::selectScene,
                    )
                }
            }

            items(uiState.messages) { message ->
                if (message.role == "user") {
                    UserBubble(content = message.content)
                } else {
                    AiReplyBubble(
                        content = message.content,
                        thinking = message.structuredOutput?.thinking,
                    )
                }
            }

            // 流式增量：边收边显示，这是 SSE 相对一次性返回的唯一观感差异。
            // 思考过程与正文分开渲染：正文还没来时先显示思考面板，
            // 这样用户 0.5s 左右就能看到"模型在动"，而不是空白 20 多秒。
            if (uiState.thinkingContent.isNotEmpty() || uiState.streamingContent.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AiThinkingPanel(
                            thinking = uiState.thinkingContent,
                            isLive = uiState.isThinking,
                            seconds = uiState.thinkingSeconds,
                        )
                        if (uiState.streamingContent.isNotEmpty()) {
                            if (uiState.thinkingContent.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            AiReplyBubble(content = uiState.streamingContent, isStreaming = true)
                        }
                    }
                }
            } else if (uiState.isLoading || uiState.isStreaming) {
                // 请求已发出但连思考增量都还没到（0.5s 内的极短窗口）
                item { AiWaitingBubble() }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        AiInputBar(
            value = uiState.inputText,
            onValueChange = viewModel::onInputChange,
            onSend = viewModel::sendMessage,
            onRewrite = viewModel::rewriteExpression,
            isLoading = uiState.isBusy,
            onOpenModeSheet = { showModeSheet = true },
            onOpenQuote = {},
            showRewriteButton = uiState.sceneKey == "expression_rewrite",
        )
    }

    if (showModeSheet) {
        ModeDrawerSheet(
            // 只列出用户可选的场景：信件改写的入口在信件页，不在这里
            scenes = scenes.filter { it.showInDrawer },
            onDismiss = { showModeSheet = false },
            onModeSelected = { scene ->
                showModeSheet = false
                // 有专属页面的场景跳转过去，其余作为聊天场景切换
                when (scene.target) {
                    AiSceneTarget.MEDIATION -> onNavigateToMediation()
                    AiSceneTarget.CHAT -> viewModel.selectScene(scene)
                }
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
    // 文案统一来自 AiSceneCatalog，不再在界面里写 when 硬编码
    val modeLabel = AiSceneCatalog.labelOf(currentMode)

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

/**
 * AI 回复气泡。
 *
 * [thinking] 是**历史消息**里落库的思考过程（`structured_output.thinking`）：
 * 流式期间它由 [AiThinkingPanel] 实时渲染，重新进入会话时则从这里回读，
 * 这样"深度思考过程可展开查看"在事后依然成立。
 */
@Composable
private fun AiReplyBubble(
    content: String,
    isStreaming: Boolean = false,
    thinking: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!thinking.isNullOrBlank()) {
            AiThinkingPanel(thinking = thinking, isLive = false)
            Spacer(modifier = Modifier.height(8.dp))
        }
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
                    // 流式过程中补一个光标，让"还在写"这件事可见
                    text = if (isStreaming) "$content▍" else content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
            }
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

@Composable
private fun QuickSceneChips(
    scenes: List<AiScene>,
    currentScene: String,
    onSceneSelected: (AiScene) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = Modifier.width(4.dp))
        scenes.forEach { scene ->
            FilterChip(
                selected = currentScene == scene.key,
                onClick = { onSceneSelected(scene) },
                label = {
                    Text(
                        text = scene.chipLabel,
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
