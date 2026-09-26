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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.components.AiRiskLevel
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppMarkdownText
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.AppTopBarAction
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAiChatScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSessionList: () -> Unit,
    // P-C3 §4.3：记忆管理页入口（此前 MemoryScreen 无任何导航接线，是孤儿页面）
    onNavigateToMemory: () -> Unit,
    onNavigateToMediation: () -> Unit,
    onNavigateToReview: () -> Unit,
    /**
     * 收敛期任务卡（契约 §3.1）：按 type+id 映射出的根路由导航。
     * 带默认值——遗留壳 MainScreen.kt 仍按旧签名实例化本页（隐藏 ≠ 删除，W6 才清理）。
     */
    onNavigateToRoute: (String) -> Unit = {},
    identity: TopBarIdentity = TopBarIdentity(),
    /** 沉浸模式：false = 底部 Tab 栏已隐藏（状态由 CoupleShell 持有） */
    tabBarVisible: Boolean = true,
    onToggleTabBar: () -> Unit = {},
    viewModel: AiChatViewModel = hiltViewModel(),
    taskCardsViewModel: TaskCardsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val taskCardsState by taskCardsViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showModeSheet by remember { mutableStateOf(false) }
    // P-B §1.6：回答深度三选一面板
    var showChatModeSheet by remember { mutableStateOf(false) }
    // P-C4：「更多」下拉面板（原「＋」菜单）+ 二级引用选择器（null = 关闭）
    var showMoreSheet by remember { mutableStateOf(false) }
    var quotePickerType by remember { mutableStateOf<QuotePickerType?>(null) }

    // 场景清单统一来自 AiSceneCatalog（远端拉取，collectAsState 保证拉到后会重组）
    val scenes by AiSceneCatalog.scenes.collectAsState()
    // 场景 chip 文案：口语化 chipLabel，未知 key 回退正式 label
    val currentSceneLabel = scenes.firstOrNull { it.key == uiState.sceneKey }?.chipLabel
        ?: AiSceneCatalog.labelOf(uiState.sceneKey)

    LaunchedEffect(uiState.messages.size, uiState.streamingContent, uiState.thinkingContent) {
        val extra = if (uiState.streamingContent.isNotEmpty() || uiState.thinkingContent.isNotEmpty()) 1 else 0
        val last = uiState.messages.size + extra - 1
        if (last >= 0) {
            listState.animateScrollToItem(last)
        }
    }

    // P0-10B 改动四：再进页面即刷新。放 Screen 的 LaunchedEffect 而非 VM init——
    // VM 由 hiltViewModel() 作用在 tab 的 NavBackStackEntry，切 tab 时 Screen
    // 重组而 VM 存活，init 不会重跑；LaunchedEffect(Unit) 正好实现「再进即刷新」。
    // 优先消费列表页传来的待进入会话，否则向服务端要 active。
    LaunchedEffect(Unit) {
        val pending = PendingSessionHolder.consume()
        if (pending != null && pending.newChat) {
            // P-A §3.1 D5：列表页「＋」→ 回来开新对话（lambda 内可安全 return）
            viewModel.startNewChat()
            // M4：newChat 分支原来直接 return，跳过了任务卡刷新——任何分支进页面都要刷新
            taskCardsViewModel.refresh()
            return@LaunchedEffect
        } else if (pending != null) {
            viewModel.setSceneKey(pending.sceneKey)
            viewModel.loadSession(pending.sessionId)
            // loadSession 只拉消息与 id，不同步标题/归档态——不补会残留
            // 上一段的「正在继续 · xxx」或把已结束会话显示成进行中
            viewModel.setSessionTitle(pending.title)
            viewModel.setSessionArchived(pending.archived)
        } else {
            viewModel.refreshActiveSession()
        }
        // 任务卡与会话无关，放在外面：任何分支都要「再进即刷新」
        taskCardsViewModel.refresh()
    }

    // P-A §2.1：错误呈现（ShowError 事件 + uiState.error 合并，只弹一个）
    var errorMessage by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        viewModel.event.collect { ev ->
            if (ev is AiChatUiEvent.ShowError) errorMessage = ev.message
        }
    }
    val dialogMessage = errorMessage.ifBlank { uiState.error }
    if (dialogMessage.isNotBlank()) {
        ErrorDialog(
            message = dialogMessage,
            onDismiss = {
                errorMessage = ""
                viewModel.clearError()
            },
        )
    }

    // P-A §2.5：chip 与抽屉共用同一分派（此前 chip 忽略 target，复盘点不进去）
    val dispatchScene: (AiScene) -> Unit = { scene ->
        when (scene.target) {
            AiSceneTarget.MEDIATION -> onNavigateToMediation()
            AiSceneTarget.REVIEW -> onNavigateToReview()
            AiSceneTarget.CHAT -> viewModel.selectScene(scene)
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
                    icon = Icons.Outlined.Psychology,
                    contentDescription = "记忆管理",
                    onClick = onNavigateToMemory,
                )
                AppTopBarAction(
                    icon = Icons.Outlined.History,
                    contentDescription = "历史会话",
                    onClick = onNavigateToSessionList,
                )
            },
        )

        // P0-10B 改动四：固定高度会话状态条（禁止 wrapContentHeight 抖动）
        SessionStatusBar(
            sessionId = uiState.sessionId,
            sessionTitle = uiState.sessionTitle,
            sceneLabel = AiSceneCatalog.labelOf(uiState.sceneKey),
            staleSessionTitle = uiState.staleSessionTitle,
            hasStale = uiState.staleSessionId != null,
            sessionArchived = uiState.sessionArchived,
            // P-C3 §3.3：「新对话」按钮左侧的用量小字（无会话不显示）
            usageText = uiState.sessionId
                ?.let { "⌾ ${formatUsage(uiState.tokenTotal)}/${formatUsage(uiState.budget)}" },
            onNewChat = viewModel::startNewChat,
            onResumeStale = viewModel::resumeStaleSession,
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
                // 快捷场景 chip 已下移到输入框下方（与深度档位同行），不再在顶部占位

                // 收敛期任务卡（契约 §3.1）：空态才展示；task_cards 未落地 → 空列表整块不渲染
                val taskCards = taskCardsState.cards
                if (taskCards.isNotEmpty()) {
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            taskCards.forEachIndexed { index, card ->
                                if (index > 0) AppListItemDivider()
                                val route = routeForTaskCard(card)
                                AppListItem(
                                    title = card.title.ifBlank { fallbackTitleForTaskCard(card.type) },
                                    subtitle = taskCardSubtitle(card.type),
                                    leadingIcon = taskCardIcon(card.type),
                                    showChevron = route != null,
                                    onClick = route?.let { target -> { onNavigateToRoute(target) } },
                                )
                            }
                        }
                    }
                }
            }

            items(uiState.messages) { message ->
                if (message.role == "user") {
                    UserBubble(content = message.content)
                } else {
                    AiReplyBubble(
                        content = message.content,
                        thinking = message.structuredOutput?.thinking,
                        riskLevel = message.riskLevel,
                    )
                }
            }

            // 流式增量：边收边显示，这是 SSE 相对一次性返回的唯一观感差异。
            // 思考过程与正文分开渲染：正文还没来时先显示思考面板，
            // 这样用户 0.5s 左右就能看到"模型在动"，而不是空白 20 多秒。
            if (uiState.thinkingContent.isNotEmpty() || uiState.streamingContent.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // P-A §2.4：与正文文字同一左起点（15dp）
                        AiThinkingPanel(
                            thinking = uiState.thinkingContent,
                            isLive = uiState.isThinking,
                            seconds = uiState.thinkingSeconds,
                            modifier = Modifier.padding(start = 15.dp),
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

            // P0-5：最近一次回答的判断依据（画像/记忆/理论），默认收起
            uiState.evidence?.let { evidence ->
                item {
                    EvidencePanel(evidence = evidence)
                }
            }

            // P0-10B：本次发生分段 → 轻量分隔，不弹窗不打断
            // P-C3 §3.5：分隔行带上分段原因（timeout/budget 来自 archive_reason，
            // user_ended/scene_switch 由客户端写入；无归因回退「新的对话」）
            if (uiState.segmentNotice) {
                val reasonText = when (uiState.segmentReason) {
                    "timeout" -> "上次聊到一半，已经是几小时前了"
                    "budget" -> "上一段聊得比较长，我把它收好了"
                    "user_ended" -> "你开了新的一段"
                    "scene_switch" -> "换了个场景，重新开始"
                    else -> "新的对话"
                }
                item {
                    Text(
                        text = "—— $reasonText ——",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // P0-8 §2.1：引用 chip 在输入区上方；为空时不渲染任何占位
        uiState.quoteChip?.let { chip ->
            QuoteChipRow(
                chip = chip,
                truncated = AiChatViewModel.willTruncate(
                    uiState.inputText.trim(),
                    chip,
                ),
                onRemove = viewModel::clearQuoteChip,
            )
        }

        // P-C3 §3.4：80% 一次性提示（每段会话各一次，可关）
        if (uiState.usageHintVisible) {
            UsageHintRow(
                budget = uiState.budget,
                onDismiss = viewModel::dismissUsageHint,
            )
        }

        // P-C3 §3.2：上下文用量进度条——sessionId == null 不显示；
        // 刷新点只有 进页面 / meta / done（不在逐帧的 Delta 里读，避免重组风暴）
        if (uiState.sessionId != null && uiState.budget > 0) {
            val usageProgress = (uiState.tokenTotal.toFloat() / uiState.budget)
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { usageProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = if (usageProgress > 0.8f) AppAccent else AppTextTertiary,
                trackColor = AppSurfaceMuted,
            )
        }

        AiInputBar(
            value = uiState.inputText,
            onValueChange = viewModel::onInputChange,
            onSend = viewModel::sendMessage,
            isLoading = uiState.isBusy,
            chatMode = uiState.chatMode,
            onOpenModePanel = { showChatModeSheet = true },
            sceneLabel = currentSceneLabel,
            onOpenScenePanel = { showModeSheet = true },
            onOpenMorePanel = { showMoreSheet = true },
            tabBarVisible = tabBarVisible,
            onToggleTabBar = onToggleTabBar,
        )
    }

    // P-C4：原「＋」一级菜单 → 输入框下方「更多 ▾」下拉（选择模式项由场景 chip 承担）
    if (showMoreSheet) {
        AiPlusSheet(
            showRewriteItem = uiState.sceneKey == "expression_rewrite" &&
                uiState.inputText.isNotBlank(),
            onDismiss = { showMoreSheet = false },
            onPickQuote = { type ->
                showMoreSheet = false
                quotePickerType = type
                viewModel.loadQuotePickerData(type)
            },
            onRewrite = {
                showMoreSheet = false
                viewModel.rewriteExpression()
            },
        )
    }

    // P0-8 二级引用选择
    quotePickerType?.let { type ->
        QuotePickerSheet(
            type = type,
            messages = uiState.messages,
            letters = uiState.quoteLetters,
            sentLetters = uiState.quoteSentLetters,
            anniversaries = uiState.quoteAnniversaries,
            loading = uiState.quotePickerLoading,
            error = uiState.quotePickerError,
            onDismiss = { quotePickerType = null },
            onPickMessage = { msg ->
                val label = if (msg.role == "user") "你说过" else "TA 说过"
                viewModel.setQuoteChip(
                    QuoteChip(
                        sourceLabel = label,
                        body = msg.content,
                        originId = msg.id,
                        originRole = msg.role,
                    )
                )
                quotePickerType = null
            },
            // P-C4：来源写进 sourceLabel——它会以【引用·…】进 message 即进提示词，
            // 两种来源作用不同（理解 TA / 改进我的表达），提示词必须可区分
            onPickLetter = { letter, fromPartner ->
                val title = letter.title ?: "无标题"
                val label = if (fromPartner) "TA写给你的信《$title》" else "你写给TA的信《$title》"
                viewModel.setQuoteChip(
                    QuoteChip(
                        sourceLabel = label,
                        body = letter.content ?: "",
                        originId = letter.id,
                    )
                )
                quotePickerType = null
            },
            onPickAnniversary = { ann ->
                viewModel.setQuoteChip(
                    QuoteChip(
                        sourceLabel = "纪念日${ann.title}（${ann.anniversaryDate}）",
                        body = ann.description ?: ann.title,
                        originId = ann.id,
                    )
                )
                quotePickerType = null
            },
        )
    }

    if (showModeSheet) {
        ModeDrawerSheet(
            // 只列出用户可选的场景：信件改写的入口在信件页，不在这里
            scenes = scenes.filter { it.showInDrawer },
            currentSceneKey = uiState.sceneKey,
            onDismiss = { showModeSheet = false },
            onModeSelected = { scene ->
                showModeSheet = false
                dispatchScene(scene)
            },
        )
    }

    // P-B §1.6：回答深度三选一（⚡快速 / 🧠深度 / 🎓专家）
    if (showChatModeSheet) {
        ChatModeSheet(
            current = uiState.chatMode,
            onDismiss = { showChatModeSheet = false },
            onSelect = { mode ->
                showChatModeSheet = false
                viewModel.setChatMode(mode)
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

/** P-A §2.4 D1：用户气泡右对齐，宽度随屏（0.82），不再固定 280dp。 */
@Composable
private fun UserBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
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
 * AI 回复（P-A §2.2/§2.3/§2.4）：
 * - 风险卡（[riskLevel] wire 值，normal/null 不渲染；流式不传）
 * - 终稿 Markdown（[AppMarkdownText]）/ 流式行内 Markdown（[AiStreamingText]）
 *   字号统一 16sp（bodyLarge），流式→终稿不跳字
 * - 无底文档流：左侧 3dp 竖线 + 缩进，全宽
 *
 * [thinking] 是**历史消息**里落库的思考过程（`structured_output.thinking`）：
 * 流式期间它由 [AiThinkingPanel] 实时渲染，重新进入会话时则从这里回读。
 */
@Composable
private fun AiReplyBubble(
    content: String,
    isStreaming: Boolean = false,
    thinking: String? = null,
    riskLevel: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!thinking.isNullOrBlank()) {
            // P-A §2.4：思考面板是次级容器，左缘与正文文字同一左起点（15dp）
            AiThinkingPanel(
                thinking = thinking,
                isLive = false,
                modifier = Modifier.padding(start = 15.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 风险卡在正文上方；流式期间不渲染（risk 只在 done 帧给，中途会闪）
        val risk = if (isStreaming) null else AiRiskLevel.fromWire(riskLevel)
        if (risk != null) {
            SafetyWarningCard(riskLevel = risk)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 去重：命中输入护栏时正文就是同一段安全文案，卡片已完整表达 → 不再重复渲染
        val canned = risk?.let { safetyCannedMessage(it) }
        val showBody = canned == null || !content.trim().startsWith(canned.take(12))

        if (showBody) {
            // App* 是 @Composable getter：先取值再进 drawBehind 闭包（红线）
            val lineColor = AppBorderLight
            val lineWidthPx = with(LocalDensity.current) { 3.dp.toPx() }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(color = lineColor, size = Size(lineWidthPx, size.height))
                    }
                    .padding(start = 15.dp),
            ) {
                if (isStreaming) {
                    AiStreamingText(
                        content = content,
                        isStreaming = true,
                        textSizeSp = 16f,
                    )
                } else {
                    AppMarkdownText(
                        markdown = content,
                        textSizeSp = 16f,
                    )
                }
            }
        }
    }
}

/**
 * P0-10B 改动四 + 收尾补丁 A2：会话状态条（固定高度）。
 *
 * 分支顺序即优先级（勿调）：
 * 1. hasStale → 「上次聊到 {title}」+「继续」
 * 2. sessionArchived → 「已结束的对话 · {title}」+「新对话」
 * 3. sessionId != null → 「正在继续 · {title ?: 场景名}」+「新对话」
 * 4. else → 「新的对话」+「新对话」
 *
 * P-C3 §3.3：[usageText]（如 `⌾ 3.2k/6k`）渲染在按钮**左边**；
 * 分支 1 是 stale 提示位（原因展示不能塞进它，历史坑），刻意不放。
 */
@Composable
private fun SessionStatusBar(
    sessionId: Long?,
    sessionTitle: String?,
    sceneLabel: String,
    staleSessionTitle: String?,
    hasStale: Boolean,
    sessionArchived: Boolean,
    usageText: String?,
    onNewChat: () -> Unit,
    onResumeStale: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            hasStale && staleSessionTitle != null -> {
                Text(
                    text = "上次聊到 $staleSessionTitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResumeStale) {
                    Text(
                        text = "继续",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppAccent,
                    )
                }
            }

            sessionArchived && sessionId != null -> {
                Text(
                    text = "已结束的对话 · ${sessionTitle ?: sceneLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                usageText?.let { usage ->
                    Text(
                        text = usage,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                TextButton(onClick = onNewChat) {
                    Text(
                        text = "新对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppAccent,
                    )
                }
            }

            sessionId != null -> {
                Text(
                    text = "正在继续 · ${sessionTitle ?: sceneLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                usageText?.let { usage ->
                    Text(
                        text = usage,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                TextButton(onClick = onNewChat) {
                    Text(
                        text = "新对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppAccent,
                    )
                }
            }

            else -> {
                Text(
                    text = "新的对话",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                    modifier = Modifier.weight(1f),
                )
                usageText?.let { usage ->
                    Text(
                        text = usage,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                TextButton(onClick = onNewChat) {
                    Text(
                        text = "新对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppAccent,
                    )
                }
            }
        }
    }
}

/**
 * P0-8 §2.1 引用 chip：来源标签 + 正文前 30 字 + ×删除。
 * 触发截断时追加一行「引用已自动精简」（不静默）。
 */
@Composable
private fun QuoteChipRow(
    chip: QuoteChip,
    truncated: Boolean,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppSurfaceMuted)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "引用·${chip.sourceLabel}：${chip.body.take(30)}" +
                    if (chip.body.length > 30) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "删除引用",
                    tint = AppTextTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (truncated) {
            Text(
                text = "引用已自动精简",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

/**
 * P-C3 §3.4：上下文用量达 80% 的一次性提示行。
 *
 * 「每段会话各一次」的判定在 ViewModel（UsageHintStore），
 * 这里只负责展示与关闭。
 */
@Composable
private fun UsageHintRow(
    budget: Int,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "聊到 $budget 我会自动把这段收好，你随时能在历史里回看",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextSecondary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭提示",
                tint = AppTextTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 收敛期任务卡（契约 §3.1）：type → 副标题。 */
private fun taskCardSubtitle(type: String): String = when (type) {
    "mediation_invite" -> "伴侣发起了双人调解"
    "dual_perspective" -> "对方已提交，等你写下视角"
    "pending_letter" -> "有一封信等你查看"
    "feedback_outcome" -> "告诉军师上次建议的实际效果"
    "questionnaire" -> "完善画像，军师的判断会更准"
    else -> ""
}

/** 收敛期任务卡（契约 §3.1）：type → 图标。 */
private fun taskCardIcon(type: String) = when (type) {
    "mediation_invite" -> Icons.Outlined.People
    "dual_perspective" -> Icons.Outlined.Visibility
    "pending_letter" -> Icons.Outlined.MailOutline
    "feedback_outcome" -> Icons.Outlined.StarOutline
    "questionnaire" -> Icons.Outlined.Quiz
    else -> Icons.Outlined.History
}

/**
 * P-C3 §3.3：用量数字格式化——`3200 → 3.2k`、`6000 → 6k`、`800 → 800`。
 * ≥1000 保留一位小数且去掉 `.0k` 的尾巴，<1000 直接显示整数。
 */
private fun formatUsage(value: Int): String {
    if (value < 1000) return value.toString()
    val k = Math.round(value / 1000.0 * 10) / 10.0
    return if (k % 1.0 == 0.0) "${k.toInt()}k" else "${k}k"
}

/** P-C4：输入区收敛为 [输入框] [发送]；选项全在下方一行 chip：
 *  [场景 chip]（帮我理清…）[档位 chip]（⚡快速/🧠深度/🎓专家）[更多 ▾]（原「＋」菜单）
 *  [隐藏/显示 Tab 栏]（沉浸模式开关）。对话中每一轮都可重选——不再藏进悬浮「＋」。 */
@Composable
private fun AiInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    chatMode: String,
    onOpenModePanel: () -> Unit,
    sceneLabel: String,
    onOpenScenePanel: () -> Unit,
    onOpenMorePanel: () -> Unit,
    tabBarVisible: Boolean,
    onToggleTabBar: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
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

        // P-B §1.6：档位 chip 在输入框下方常驻——档位决定这条消息多快多深，
        // 发送前必须一眼可见、一点可改；藏进悬浮按钮等于让用户以为没这个能力。
        // P-C4：场景 chip（原空会话顶部那排下移）与「更多」（原「＋」）同排——
        // 场景/档位/引用来源对话中随时可重选；三枚 chip 左对齐，右侧留白。
        val option = chatModeOptionOf(chatMode)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()) // chip 变多后窄屏不换行、不溢出
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InputOptionChip(label = sceneLabel, onClick = onOpenScenePanel)
            Spacer(modifier = Modifier.width(8.dp))
            InputOptionChip(
                label = "${option.icon} ${option.label}",
                onClick = onOpenModePanel,
            )
            Spacer(modifier = Modifier.width(8.dp))
            InputOptionChip(label = "更多", onClick = onOpenMorePanel)
            Spacer(modifier = Modifier.width(8.dp))
            // 沉浸模式开关：隐藏底部 Tab 栏给军师腾空间；开关常驻，随时显示回来
            InputOptionChip(
                label = if (tabBarVisible) "隐藏Tab栏" else "显示Tab栏",
                onClick = onToggleTabBar,
                showChevron = false,
            )
        }
    }
}

/** P-C4：输入框下方的下拉样式 chip（场景 / 深度档位共用）；[showChevron]=false 用于开关类。 */
@Composable
private fun InputOptionChip(label: String, onClick: () -> Unit, showChevron: Boolean = true) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AppSurfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppTextSecondary,
        )
        if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "▾",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
            )
        }
    }
}

/** P-B §1.6：三档选项定义——chip 与底部面板共用一份，文案是验收项（P2）逐字核对的。 */
private data class ChatModeOption(
    val key: String,
    val icon: String,
    val label: String,
    /** 可感知差异（速度/长度），不是参数罗列 */
    val benefit: String,
    /** 检索深度差异 */
    val retrieval: String,
)

private val CHAT_MODE_OPTIONS = listOf(
    ChatModeOption("quick", "⚡", "快速", "1 秒内先给一句能说的话", "不查记忆"),
    ChatModeOption("deep", "🧠", "深度", "会先想清楚再答，约 15 秒", "查记忆 + 理论"),
    ChatModeOption(
        "expert", "🎓", "专家",
        "分点讲清依据，附替代解释，约 20 秒", "查全部记忆 + 事件时间线",
    ),
)

private fun chatModeOptionOf(key: String): ChatModeOption =
    CHAT_MODE_OPTIONS.firstOrNull { it.key == key } ?: CHAT_MODE_OPTIONS[1] // 缺省 deep

/**
 * P-B §1.6：回答深度三选一面板。与 [ModeDrawerSheet] 同一套 ModalBottomSheet 视觉。
 * 每张卡写明可感知差异（速度 + 检索），只写档位名会被用户视为「劣质感」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatModeSheet(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "回答深度",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            CHAT_MODE_OPTIONS.forEach { option ->
                ChatModeOptionCard(
                    option = option,
                    selected = option.key == current,
                    onClick = { onSelect(option.key) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ChatModeOptionCard(
    option: ChatModeOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppAccentLight else AppSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${option.icon} ${option.label}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = option.benefit,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = option.retrieval,
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
            )
        }
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyLarge,
                color = AppAccent,
            )
        }
    }
}

/**
 * P0-5「我依据了什么」：可折叠依据面板。
 *
 * 三块内容全部来自后端 evidence 帧（零新增 LLM）：画像卡与 prompt 同源，
 * 记忆/理论是本轮真实召回。折叠态用 rememberSaveable 保持，且以 evidence
 * 为 key——新回答到来时自动回到默认收起。
 */
@Composable
private fun EvidencePanel(evidence: AiDto.EvidencePayload) {
    var expanded by rememberSaveable(evidence) { mutableStateOf(false) }
    val hasProfile = evidence.selfProfileCard.isNotBlank() ||
        evidence.partnerProfileCard.isNotBlank() ||
        evidence.relationshipPattern.isNotBlank()
    val hasMemory = evidence.recalledMemories.isNotEmpty()
    val hasTheory = evidence.theoryChunks.isNotEmpty()

    AppCard(
        // P-A §2.4：依据面板左缘与正文文字同一左起点（15dp）
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 15.dp),
        onClick = { expanded = !expanded },
        containerColor = AppSurface,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) "▾ 我依据了什么" else "▸ 我依据了什么",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (evidence.voiceStyleLabel.isNotBlank()) {
                Text(
                    text = "当前语气：${evidence.voiceStyleLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
        }

        if (expanded) {
            if (hasProfile) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📋 你们的画像",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                if (evidence.selfProfileCard.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = evidence.selfProfileCard,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextPrimary,
                    )
                }
                if (evidence.partnerProfileCard.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = evidence.partnerProfileCard,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextPrimary,
                    )
                }
                if (evidence.relationshipPattern.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = evidence.relationshipPattern,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextPrimary,
                    )
                }
            }

            if (hasMemory) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "🧠 我记得的",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                evidence.recalledMemories.forEach { mem ->
                    val meta = listOf(mem.source, mem.createdAt)
                        .filter { !it.isNullOrBlank() }
                        .joinToString(" · ")
                    Text(
                        text = "· ${mem.content}" + if (meta.isNotBlank()) "（$meta）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (hasTheory) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📚 参考的理论",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                evidence.theoryChunks.forEach { chunk ->
                    val head = chunk.title.ifBlank { "参考" }
                    Text(
                        text = "· $head：${chunk.snippet}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // P-C2 §5：本轮省略了什么（分层预算裁剪说明）——空列表整栏不显示
            if (evidence.omitted.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✂️ 本轮省略了什么",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                evidence.omitted.forEach { item ->
                    Text(
                        text = "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (!hasProfile && !hasMemory && !hasTheory) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "这一轮没有可用的画像、记忆或理论依据",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }
        }
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
