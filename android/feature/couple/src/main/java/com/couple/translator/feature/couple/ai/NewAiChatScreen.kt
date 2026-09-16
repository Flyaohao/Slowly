package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.outlined.Psychology
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
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppFilterChip
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.AppTopBarAction
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAiChatScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSessionList: () -> Unit,
    onNavigateToMediation: () -> Unit,
    onNavigateToReview: () -> Unit,
    identity: TopBarIdentity = TopBarIdentity(),
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
            .background(AppBackground)
            .imePadding(),
    ) {
        AppTopBar(
            onOpenDrawer = onOpenDrawer,
            identity = identity,
            trailing = {
                AppTopBarAction(
                    icon = Icons.Outlined.History,
                    contentDescription = "历史会话",
                    onClick = onNavigateToSessionList,
                )
            },
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
                // 空会话也当成一页来排：大标题说清"现在是什么模式"，副标题说清"能干什么"
                item {
                    AppPageHeader(
                        title = AiSceneCatalog.labelOf(uiState.sceneKey),
                        subtitle = "直接说你想说的话。我会帮你表达，也会帮你理解 TA。",
                        horizontalPadding = 0.dp,
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

            itemsIndexed(uiState.messages) { index, message ->
                if (message.role == "user") {
                    UserBubble(content = message.content)
                } else {
                    // Agent 轨迹只挂在最后一条助手消息上（Agent 回答不入库，刷新后消失）
                    val isLastAssistant = index == uiState.messages.lastIndex &&
                        uiState.agentToolCalls.isNotEmpty()
                    if (isLastAssistant) {
                        AgentTraceCard(toolCalls = uiState.agentToolCalls, steps = uiState.agentSteps)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
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
                    AiSceneTarget.REVIEW -> onNavigateToReview()
                    AiSceneTarget.CHAT -> viewModel.selectScene(scene)
                }
            },
        )
    }

    // 表达改写结果底部弹窗：流式生成期间也要展示（正文打字机 + 思考面板）
    if (uiState.showRewriteSheet &&
        (uiState.rewriteVersions.isNotEmpty() || uiState.isRewriteStreaming || uiState.rewriteStreamContent.isNotEmpty())
    ) {
        RewriteResultSheet(
            original = uiState.rewriteOriginal,
            versions = uiState.rewriteVersions,
            streamContent = uiState.rewriteStreamContent,
            thinking = uiState.rewriteThinking,
            isStreaming = uiState.isRewriteStreaming,
            onStop = viewModel::stopRewrite,
            onApply = { viewModel.applyRewrite(it) },
            onDismiss = { viewModel.dismissRewriteSheet() },
        )
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
                .background(AppTextPrimary)
                .padding(12.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = AppSurface,
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
                    .background(AppSurface)
                    .padding(12.dp),
            ) {
                Text(
                    // 流式过程中补一个光标，让"还在写"这件事可见
                    text = if (isStreaming) "$content▍" else content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextPrimary,
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
    onAgent: () -> Unit = {},
    onRewrite: () -> Unit = {},
    isLoading: Boolean,
    onOpenModeSheet: () -> Unit,
    onOpenQuote: () -> Unit,
    showRewriteButton: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("想说点什么…", color = AppTextTertiary) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppBorderLight,
                cursorColor = AppTextPrimary,
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
                tint = AppTextSecondary,
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
                    tint = AppAccent,
                )
            }
        }

        // Agent 模式：模型自主决定查画像 / 检索理论后再作答
        if (value.isNotBlank()) {
            IconButton(
                onClick = onAgent,
                enabled = !isLoading,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = "Agent 深度提问",
                    tint = AppAccent,
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
                tint = AppTextSecondary,
            )
        }

        IconButton(
            onClick = onSend,
            enabled = value.isNotBlank() && !isLoading,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (value.isNotBlank() && !isLoading) AppTextPrimary else AppBorderLight),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = AppSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Agent 工具调用轨迹：让"查了画像、翻了理论"的过程可见。 */
@Composable
private fun AgentTraceCard(toolCalls: List<AiDto.AgentToolCall>, steps: Int) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = AppAccentFaint,
        contentPadding = PaddingValues(12.dp),
    ) {
        Text(
            text = "Agent 深度提问 · " + (if (steps > 0) "$steps 轮" else "已完成"),
            style = MaterialTheme.typography.labelSmall,
            color = AppAccent,
        )
        if (toolCalls.isEmpty()) {
            Text(
                text = "本轮未调用工具，直接作答",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextSecondary,
            )
        } else {
            toolCalls.forEach { call ->
                Text(
                    text = "· " + toolLabel(call.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                )
            }
        }
    }
}

private fun toolLabel(name: String): String = when (name) {
    "get_relation_profile" -> "已查询关系画像"
    "search_theory" -> "已检索依恋理论"
    "get_ai_memory" -> "已查询 AI 记忆"
    else -> "已调用工具 $name"
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
            AppFilterChip(
                text = scene.chipLabel,
                selected = currentScene == scene.key,
                onClick = { onSceneSelected(scene) },
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
    streamContent: String = "",
    thinking: String = "",
    isStreaming: Boolean = false,
    onStop: () -> Unit = {},
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "改写结果",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (isStreaming) {
                    TextButton(onClick = onStop) {
                        Text("停止生成", color = AppAccent)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "原文：${original.take(50)}${if (original.length > 50) "..." else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 流式阶段：思考面板 + 打字机正文；完成后切到结构化版本卡片
            if (versions.isEmpty()) {
                if (thinking.isNotBlank()) {
                    AiThinkingPanel(thinking = thinking, isLive = isStreaming)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                when {
                    streamContent.isNotBlank() -> AiStreamingText(
                        content = streamContent,
                        isStreaming = isStreaming,
                    )
                    isStreaming -> AiWaitingBubble()
                }
            }

            versions.forEach { version ->
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = version.style,
                        style = MaterialTheme.typography.labelLarge,
                        color = AppAccent,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = version.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { onApply(version.content) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("使用这个版本", color = AppAccent)
                    }
                }
            }
        }
    }
}
