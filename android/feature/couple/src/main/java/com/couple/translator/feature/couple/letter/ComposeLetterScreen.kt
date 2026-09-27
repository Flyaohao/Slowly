package com.couple.translator.feature.couple.letter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppFilterChip
import com.couple.translator.core.ui.components.AppLinkText
import com.couple.translator.core.ui.components.AppTopBarAction
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

private val letterTemplates = listOf(
    "道歉信" to "我想对你说声对不起。我知道我的行为让你感到难过，这并不是我的本意。",
    "感谢信" to "谢谢你一直以来的陪伴和支持。有你在身边，让我觉得生活充满了温暖。",
    "表白信" to "有些话一直想对你说，却不知道怎么开口。我想告诉你，遇见你是我最幸运的事。",
    "和好信" to "我们最近的争吵让我很难过。我不想因为一时的情绪伤害我们的感情。",
    "想念信" to "最近总是会想起你。想起我们一起度过的那些美好时光。",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeLetterScreen(
    draftId: Long? = null,
    onNavigateBack: () -> Unit,
    onLetterSent: () -> Unit,
    isCoupleMode: Boolean = true,
    // 从别处带入的预填正文（默认空 = 不预填，根图旧注册点无需改动即可编译）
    content: String = "",
    viewModel: ComposeLetterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSendConfirm by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }

    LaunchedEffect(draftId, content) {
        when {
            // 草稿优先：已有草稿时绝不用外部正文覆盖（prefillContent 内还有双保险）
            draftId != null && draftId > 0 -> viewModel.loadDraft(draftId)
            content.isNotBlank() -> viewModel.prefillContent(content)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is ComposeLetterUiEvent.LetterSent -> onLetterSent()
                is ComposeLetterUiEvent.ShowError -> {}
                is ComposeLetterUiEvent.Rewritten -> {}
            }
        }
    }

    // Error dialog
    if (uiState.error.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("提示") },
            text = { Text(uiState.error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("确定")
                }
            },
        )
    }

    // Send confirmation dialog
    if (showSendConfirm) {
        if (!isCoupleMode) {
            // Single mode: can't send
            AlertDialog(
                onDismissRequest = { showSendConfirm = false },
                title = { Text("无法发送") },
                text = {
                    Column {
                        Text("你还没有绑定情侣关系，暂时无法发送信件。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "信件已保存为草稿，绑定情侣后可以发送。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSendConfirm = false }) {
                        Text("知道了")
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showSendConfirm = false },
                title = { Text("确认发送") },
                text = {
                    Column {
                        Text("确定要发送这封信吗？")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "发送后将无法编辑或撤回",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSendConfirm = false
                        viewModel.sendLetter()
                    }) {
                        Text("发送", color = AppAccent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSendConfirm = false }) {
                        Text("取消")
                    }
                },
            )
        }
    }

    // Template selection dialog
    if (showTemplates) {
        AlertDialog(
            onDismissRequest = { showTemplates = false },
            title = { Text("选择信件模板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    letterTemplates.forEach { (name, content) ->
                        TextButton(
                            onClick = {
                                viewModel.onContentChange(content)
                                showTemplates = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplates = false }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = {
                    viewModel.saveDraft()
                    onNavigateBack()
                },
                title = when {
                    uiState.isSending -> "发送中..."
                    uiState.isSaving -> "保存中..."
                    isCoupleMode -> "写信"
                    else -> "写观点"
                },
                subtitle = if (uiState.letterId != null && !uiState.isSaving && !uiState.isSending) {
                    "草稿已自动保存"
                } else {
                    null
                },
                trailing = {
                    if (isCoupleMode) {
                        AppTopBarAction(
                            icon = Icons.Filled.Send,
                            contentDescription = "发送",
                            tint = if (uiState.content.isNotBlank() && !uiState.isSending) AppAccent else AppTextSecondary,
                            onClick = {
                                if (!uiState.isSending && !uiState.isSaving && uiState.content.isNotBlank()) {
                                    showSendConfirm = true
                                }
                            },
                        )
                    } else {
                        AppLinkText(
                            label = if (uiState.isSaving) "保存中..." else "保存",
                            color = if (uiState.isSaving) AppTextSecondary else AppAccent,
                            onClick = {
                                if (!uiState.isSaving && uiState.content.isNotBlank()) {
                                    viewModel.saveDraft()
                                    onNavigateBack()
                                }
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            ComposeBottomBar(
                onAiAssist = { viewModel.showAiAssist() },
                onStopRewrite = { viewModel.stopRewrite() },
                onLetterType = { viewModel.onLetterTypeChange(it) },
                onTemplate = { showTemplates = true },
                currentType = uiState.letterType,
                isRewriting = uiState.isRewriting,
                isCoupleMode = isCoupleMode,
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
            Spacer(modifier = Modifier.height(8.dp))

            // Letter type badge
            AnimatedVisibility(visible = uiState.letterType != "normal", enter = fadeIn(), exit = fadeOut()) {
                AppFilterChip(
                    text = letterTypeName(uiState.letterType),
                    selected = true,
                    onClick = { viewModel.onLetterTypeChange("normal") },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            TextField(
                value = uiState.title,
                onValueChange = { viewModel.onTitleChange(it) },
                placeholder = { Text("标题（可选）", color = AppTextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppBackground,
                    unfocusedContainerColor = AppBackground,
                    focusedIndicatorColor = AppBackground,
                    unfocusedIndicatorColor = AppBackground,
                ),
                textStyle = MaterialTheme.typography.titleLarge,
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = uiState.content,
                onValueChange = { viewModel.onContentChange(it) },
                placeholder = {
                    Text(
                        if (isCoupleMode) "写下你想说的话..." else "写下此刻的心情...",
                        color = AppTextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppBackground,
                    unfocusedContainerColor = AppBackground,
                    focusedIndicatorColor = AppBackground,
                    unfocusedIndicatorColor = AppBackground,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
            )

            // Word count
            if (uiState.content.isNotBlank()) {
                Text(
                    text = "${uiState.content.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 4.dp),
                )
            }
        }

        if (uiState.showAiAssist) {
            AiAssistSheet(
                onDismiss = { viewModel.dismissAiAssist() },
                onSelectStyle = { style -> viewModel.rewriteLetter(style) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeBottomBar(
    onAiAssist: () -> Unit,
    onStopRewrite: () -> Unit = {},
    onLetterType: (String) -> Unit,
    onTemplate: () -> Unit,
    currentType: String,
    isRewriting: Boolean,
    isCoupleMode: Boolean = true,
) {
    if (!isCoupleMode) {
        // Single mode (diary): no bottom bar
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onTemplate) {
            Text("模板", color = AppAccent, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.width(4.dp))
        // 同一个按钮承担「打开 AI 辅助」和「停止生成」两种语义：
        // 流式改写期间它是停止入口，避免用户找不到中止的地方
        TextButton(onClick = if (isRewriting) onStopRewrite else onAiAssist) {
            Text(
                if (isRewriting) "停止改写" else "AI 辅助",
                color = AppAccent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = {
            // [W1.4 隐藏] 未来信/私密信冻结创建（契约 §2.5-1，POST 10006）——类型循环只保留
            // normal/unsaid/calm。旧枚举值与 letterTypeName 映射保留（隐藏 ≠ 删除）。
            val types = listOf("normal", "unsaid", "calm")
            val nextIndex = (types.indexOf(currentType) + 1) % types.size
            onLetterType(types[nextIndex])
        }) {
            Text(letterTypeName(currentType), color = AppAccent, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun letterTypeName(type: String): String = when (type) {
    "normal" -> "普通信"
    // 冻结类型（未来/私密）：徽标显示中性的「信件」，不出现冻结字样
    // （正常流程只会循环 normal/unsaid/calm，这里只为历史草稿兜底）
    "future" -> "信件"
    "calm" -> "冷静信"
    "unsaid" -> "未说出口"
    "private" -> "信件"
    else -> type
}
