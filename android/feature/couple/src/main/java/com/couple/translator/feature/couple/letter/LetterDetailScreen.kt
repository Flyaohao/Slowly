package com.couple.translator.feature.couple.letter

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.common.copyToClipboard
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppTopBarAction
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppMotion
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.text.displayTitle
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetterDetailScreen(
    letterId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToComposeReply: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: LetterDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    // AI 失败的提示独立于「页面加载失败」：前者只需关掉弹窗，
    // 后者要重新拉一次数据，两者的 dismiss 行为不一样。
    var aiError by remember { mutableStateOf("") }

    LaunchedEffect(letterId) {
        viewModel.loadLetter(letterId)
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is LetterDetailUiEvent.LetterDeleted -> onNavigateBack()
                // 此前这里是空实现，导致 AI 失败时用户看不到任何反馈
                is LetterDetailUiEvent.ShowError -> aiError = event.message
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这封信件吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteLetter()
                }) {
                    Text("删除", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.loadLetter(letterId) },
        )
    }

    if (aiError.isNotEmpty()) {
        ErrorDialog(
            message = aiError,
            onDismiss = { aiError = "" },
        )
    }

    if (uiState.isLoading) {
        SkeletonDetailPage()
        return
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "信件详情",
                trailing = {
                    val letter = uiState.letter
                    if (letter != null) {
                        // 草稿信件显示编辑按钮。
                        // isMine 守卫（2026-10-01 线上排查补充）：编辑与 AI 改写的后端
                        // 鉴权都只认发件人（letter_service.update_letter / letter_ai_service
                        // .prepare_rewrite_letter 均为 sender_id != user_id → 403），
                        // 收件方若能看到这个入口，点下去只能拿到「无权访问此信件」。
                        // 用 != false 而不是 == true：isMine 为 null（用户 id 尚未取到、
                        // 信件未加载）时不该让作者丢掉编辑能力，只拦「确定不是作者」。
                        if (letter.status == "draft" && uiState.isMine != false) {
                            AppTopBarAction(
                                icon = Icons.Default.Edit,
                                contentDescription = "编辑",
                                tint = AppAccent,
                                onClick = { onNavigateToEdit(letter.id) },
                            )
                        }
                        AppTopBarAction(
                            icon = if (letter.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (letter.isFavorite) AppAccent else AppTextSecondary,
                            onClick = { viewModel.toggleFavorite() },
                        )
                        // 删除权限分两种情况，别混：
                        // ① 草稿信 —— 后端 delete_letter 只要求「是收发双方之一」
                        //   （letter_service.py:272），但草稿语义上只属于发件人，
                        //   加守卫是为了「角色判定」不依赖数据可见性，以后草稿
                        //   共享逻辑一变也不会漏。
                        // ② 已送达的信 —— 后端明确允许收发双方删除，保持原行为。
                        // 用 != false：isMine 尚未取到时不剥夺作者的能力。
                        if (letter.status != "draft" || uiState.isMine != false) {
                            AppTopBarAction(
                                icon = Icons.Default.Delete,
                                contentDescription = "删除",
                                onClick = { showDeleteDialog = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val letter = uiState.letter
        if (letter == null) {
            Text(
                text = "信件不存在",
                modifier = Modifier.padding(padding).padding(24.dp),
                color = AppTextTertiary,
            )
            return@Scaffold
        }

        // 开信入场：骨架屏消失后内容一次性淡入上移（AppMotion 令牌）
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            entrance.animateTo(
                1f,
                tween(durationMillis = AppMotion.normal, easing = AppMotion.EaseOut),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState())
                // R 系列·信箱：开信「信封开启」感——内容淡入 + 轻微上移一次
                .graphicsLayer {
                    alpha = entrance.value
                    translationY = (1f - entrance.value) * 24.dp.toPx()
                },
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = letter.title.displayTitle(),
                style = MaterialTheme.typography.headlineSmall,
                color = AppTextPrimary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                Text(
                    text = letterTypeName(letter.letterType),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.width(12.dp))
                // 身份提示（2026-09-29）：一眼看清这封是我写的还是 TA 写给我的。
                // 此前详情页对两者一视同仁，A 打开自己发出的信会看到「回应」「怎么回这封信」，
                // 整页都在引导他回自己的信 —— 这是用户反馈「A 也来回信」的直接来源。
                if (uiState.isMine != null) {
                    Text(
                        text = if (uiState.isMine == true) "我发出的" else "来自 TA",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.isMine == true) AppTextSecondary else AppAccent,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = letter.createdAt ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextTertiary,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 正文头部的轻操作：复制全文 / 系统分享（纯 Intent，不需要任何权限）。
            // 与下方「回应 / AI 帮我理解」并存，不互相替代。
            val context = LocalContext.current
            Row {
                TextButton(onClick = { context.copyToClipboard(letter.content ?: "") }) {
                    Text("复制", color = AppAccent)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, buildShareText(letter.title, letter.content))
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "分享信件"))
                    },
                ) {
                    Text("分享", color = AppAccent)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = letter.content ?: "",
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                color = AppTextPrimary,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row {
                // 「回应」只对收信人成立（2026-09-29）。自己写的信不出现回信入口，
                // 否则 A 打开自己发出的信，会被引导去回自己的信。
                if (uiState.isMine == false) {
                    TextButton(onClick = { onNavigateToComposeReply(letter.id) }) {
                        Text("回应", color = AppAccent)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // 同一个按钮承担「开始」和「停止」两种语义：生成中它变成停止入口，
                // 否则用户找不到中止的地方（旧版这里是禁用的「AI 理解中...」）。
                TextButton(onClick = { viewModel.understandLetter() }) {
                    Text(
                        text = when {
                            uiState.isStreaming -> "停止生成"
                            uiState.showUnderstanding -> "重新理解"
                            else -> "AI 帮我理解"
                        },
                        color = AppAccent,
                    )
                }
                // 「AI 建议怎么回」同样是收信人专属。
                if (uiState.isMine == false) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // 读懂来信之后自然要多一步：这封该怎么回。接口一直有，此前没有入口。
                    TextButton(onClick = { viewModel.generateReply() }) {
                        Text(
                            text = if (uiState.isReplyLoading) "正在想…" else "AI 建议怎么回",
                            color = AppAccent,
                        )
                    }
                }
            }

            // 自己写的信：给一句状态说明，替代被移除的回信入口，避免用户以为功能缺失。
            if (uiState.isMine == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "这是你发出的信，等 TA 的回应。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }

            if (uiState.showReply) {
                Spacer(modifier = Modifier.height(16.dp))
                ReplySuggestionCard(
                    suggestion = uiState.reply,
                    isLoading = uiState.isReplyLoading,
                    error = uiState.replyError,
                    onDismiss = { viewModel.dismissReply() },
                )
            }

            if (uiState.showUnderstanding) {
                Spacer(modifier = Modifier.height(16.dp))
                LetterUnderstandingCard(
                    understanding = uiState.understanding,
                    streamContent = uiState.streamContent,
                    thinking = uiState.thinkingContent,
                    isStreaming = uiState.isStreaming,
                    isThinking = uiState.isThinking,
                    isStructuring = uiState.isStructuring,
                    thinkingSeconds = uiState.thinkingSeconds,
                    status = uiState.understandingStatus,
                    // 自己写的信不展示「回信建议」分组：那是收信人才需要的
                    showReplySuggestions = uiState.isMine != true,
                    // 方向也传给卡片：写信人视角下「TA 的情绪 / 回应」指的是
                    // TA 读完这封信的反应，与收信人视角的标签不是一回事
                    viewerIsSender = uiState.isMine == true,
                    onDismiss = { viewModel.dismissUnderstanding() },
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

/**
 * AI 回信建议卡：几个可以直接发出的版本 + 一句「别这么说」。
 *
 * 每个版本都能单独复制——用户要的是挑一条发出去，不是读一篇回信方法论。
 * 风险等级非 normal 时只提示「先看 AI 理解」，不在卡片里复述风险内容：
 * 同一件事说两遍，第二遍只会稀释第一遍的分量。
 */
@Composable
private fun ReplySuggestionCard(
    suggestion: ReplySuggestion?,
    isLoading: Boolean,
    error: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "怎么回这封信",
                style = MaterialTheme.typography.titleSmall,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("收起", color = AppAccent)
            }
        }

        when {
            isLoading -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "正在想…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextTertiary,
                )
            }

            suggestion == null -> {
                if (error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppErrorRed,
                    )
                }
            }

            else -> {
                if (suggestion.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = suggestion.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                    )
                }

                suggestion.variants.forEach { variant ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = variant.style,
                        style = MaterialTheme.typography.labelMedium,
                        color = AppAccent,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = variant.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                    )
                    TextButton(onClick = { context.copyToClipboard(variant.content) }) {
                        Text("复制这条", color = AppAccent)
                    }
                }

                if (suggestion.doNotSay.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "这次别说",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppErrorRed,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = suggestion.doNotSay,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }

                if (suggestion.riskLevel.isNotBlank() && suggestion.riskLevel != "normal") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "这封信里有需要当心的地方，回之前先看一眼上面「AI 帮我理解」的提示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppErrorRed,
                    )
                }
            }
        }
    }
}

/** 分享文案：有标题时「标题 + 换行 + 正文」，没有标题只发正文。 */
private fun buildShareText(title: String?, content: String?): String {
    val trimmedTitle = title?.takeIf { it.isNotBlank() } ?: return content.orEmpty()
    return "$trimmedTitle\n${content.orEmpty()}"
}

private fun letterTypeName(type: String): String = when (type) {
    "normal" -> "普通信"
    // 冻结类型（未来/私密/纪念，创建已禁用）：徽标只显示中性的「信件」，
    // 不再出现「未来信/私密信/纪念信」字样（历史数据仍可能带这些 type）
    "future" -> "信件"
    "calm" -> "冷静信"
    "unsaid" -> "未说出口"
    "private" -> "信件"
    "anniversary" -> "信件"
    "shared" -> "共同信"
    "reconcile" -> "和好信"
    else -> type
}
