package com.couple.translator.feature.couple.mediation.room

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.ChatInputBar
import com.couple.translator.core.ui.components.SkeletonChatPage
import com.couple.translator.core.ui.components.rememberAppHaptics
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppMotion
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.AppWarm
import com.couple.translator.feature.couple.data.model.MediationRoomDto.RoomMessage
import com.couple.translator.feature.couple.data.model.MediationRoomDto.RoomState
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val ROLE_USER_A = "user_a"
private const val ROLE_USER_B = "user_b"
private const val SENDER_ADVISOR = "advisor"

/** 时间分隔阈值（D3 拍板：微信口径 5 分钟）。 */
private const val TIME_DIVIDER_MS = 5 * 60_000L

/** 连续消息合并阈值：同发送者 2 分钟内只保留首个昵称（M-6）。 */
private const val HEADER_MERGE_MS = 2 * 60_000L

private fun senderLabel(senderType: String, partnerName: String): String = when (senderType) {
    ROLE_USER_A, ROLE_USER_B -> partnerName
    SENDER_ADVISOR -> "军师"
    else -> ""
}

private fun isMine(senderType: String, myRole: String): Boolean = senderType == myRole

/** created_at（服务端 isoformat，可能无时区）→ 本地时间；解析失败返回 null。 */
private fun parseChatTime(iso: String?): ZonedDateTime? {
    if (iso.isNullOrBlank()) return null
    val instant = runCatching {
        val normalized = iso.replace(" ", "T")
        Instant.parse(normalized + if (normalized.length == 19) "Z" else "")
    }.getOrNull() ?: return null
    return instant.atZone(ZoneId.systemDefault())
}

private fun chatTimeLabel(t: ZonedDateTime): String {
    val now = ZonedDateTime.now()
    val pattern = if (t.toLocalDate() == now.toLocalDate()) "HH:mm" else "M月d日 HH:mm"
    return t.format(DateTimeFormatter.ofPattern(pattern))
}

/** 聊天列表行模型：时间分隔 / 普通消息 / 乐观上屏中的本地消息。 */
private sealed interface ChatRow {
    val key: String

    data class Divider(val label: String, override val key: String) : ChatRow

    data class Bubble(
        val msg: RoomMessage,
        val mine: Boolean,
        val advisor: Boolean,
        val showHeader: Boolean,
        override val key: String,
    ) : ChatRow

    data class Pending(val tempId: Long, val content: String, val failed: Boolean) : ChatRow {
        override val key: String = "pending_$tempId"
    }
}

/**
 * 共同调解室聊天页（全局 UI/UX 方案 M 系列）：三角色气泡 + 头像体系、
 * 时间分隔、连续消息合并、乐观上屏、贴近底部才自动跟随、军师打字动效。
 *
 * 注意 Compose 红线：inline composable（Column/Row/Box/LazyColumn item）里
 * 禁止提前 return@，全部用 if/else 分支表达。
 */
@Composable
fun MediationRoomChatScreen(
    roomId: Long,
    onNavigateBack: () -> Unit,
    myNickname: String? = null,
    partnerNickname: String? = null,
    viewModel: MediationRoomChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()

    // 输入草稿：跨导航返回不丢（rememberSaveable，NavHost dispose 红线）
    var draft by rememberSaveable { mutableStateOf("") }
    var mentionAdvisor by rememberSaveable { mutableStateOf(false) }
    var showSupplementDialog by remember { mutableStateOf(false) }

    LaunchedEffect(roomId) { viewModel.bind(roomId) }

    LaunchedEffect(uiState.toast) {
        if (uiState.toast.isNotBlank()) {
            haptics.error()
            Toast.makeText(context, uiState.toast, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    // 行模型：消息 + 乐观上屏 → 时间分隔 / 头像合并（M-6 / D3）
    val rows = remember(uiState.messages, uiState.pendingSends, uiState.state?.myRole) {
        buildChatRows(
            messages = uiState.messages,
            pendingSends = uiState.pendingSends,
            myRole = uiState.state?.myRole ?: ROLE_USER_A,
        )
    }

    // M-1：只有贴近底部才自动跟随；用户上翻回看不再被拽回
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(rows.size, uiState.advisorStreaming, uiState.advisorStreamingContent) {
        if (isNearBottom) {
            val last = rows.size - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    val myAvatarChar = myNickname?.trim()?.firstOrNull()?.toString() ?: "我"
    val partnerName = partnerNickname?.trim()?.takeIf { it.isNotEmpty() } ?: "对方"
    val partnerAvatarChar = partnerNickname?.trim()?.firstOrNull()?.toString() ?: "TA"

    Column(modifier = Modifier.fillMaxSize()) {
        AppBackTopBar(
            onBack = onNavigateBack,
            title = uiState.roomName.ifBlank { "共同调解室" },
            subtitle = uiState.styleLabel.ifBlank { null },
            trailing = {
                val st = uiState.state
                if (st != null && st.status == "active") {
                    TextButton(onClick = {
                        haptics.warning()
                        viewModel.voteEnd(!st.endVoteMe)
                    }) {
                        Text(
                            text = if (st.endVoteMe) "取消结束" else "结束调解",
                            color = AppErrorRed,
                        )
                    }
                }
            },
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Divider -> TimeDividerLabel(row.label)
                        is ChatRow.Bubble -> MessageBubble(
                            msg = row.msg,
                            mine = row.mine,
                            advisor = row.advisor,
                            showHeader = row.showHeader,
                            partnerName = partnerName,
                            partnerAvatarChar = partnerAvatarChar,
                            myAvatarChar = myAvatarChar,
                        )
                        is ChatRow.Pending -> PendingBubble(
                            content = row.content,
                            failed = row.failed,
                            myAvatarChar = myAvatarChar,
                            onRetry = { viewModel.retryPendingSend(row.tempId) },
                            onDismiss = { viewModel.dismissPendingSend(row.tempId) },
                        )
                    }
                }
                if (uiState.advisorStreaming) {
                    item(key = "streaming") {
                        AdvisorStreamingBubble(
                            thinking = uiState.advisorThinking,
                            content = uiState.advisorStreamingContent,
                        )
                    }
                } else if (uiState.advisorInputPending) {
                    item(key = "pending_typing") {
                        TypingIndicator()
                    }
                }
                item(key = "footer") {
                    StateFooter(
                        state = uiState.state,
                        viewModel = viewModel,
                        haptics = haptics,
                        onSupplement = { showSupplementDialog = true },
                    )
                }
            }

            // 初始加载态：聊天气泡骨架（G3），数据到位后无缝替换
            if (uiState.loading && rows.isEmpty() && !uiState.advisorStreaming) {
                SkeletonChatPage(modifier = Modifier.fillMaxSize())
            }

            // 回到底部：离开底部才浮出（Telegram 口径）
            ScrollToBottomFab(
                visible = !isNearBottom && !uiState.loading,
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem((rows.size - 1).coerceAtLeast(0))
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
            )
        }

        // 输入栏（状态收口：只有 active 才能发言）
        if (uiState.state?.status == "active") {
            ChatInputBar(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    haptics.confirm()
                    viewModel.sendMessage(draft, mentionAdvisor)
                    draft = ""
                    mentionAdvisor = false
                },
                enabled = true,
                placeholder = if (mentionAdvisor) "向军师提问…" else "说点什么…",
                prefixLabel = "@军师",
                prefixSelected = mentionAdvisor,
                onPrefixClick = { mentionAdvisor = !mentionAdvisor },
                modifier = Modifier.imePadding(),
            )
        } else if (uiState.state != null) {
            Text(
                text = "调解已进入结算流程，房间不再收新消息",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showSupplementDialog) {
        SupplementDialog(
            onDismiss = { showSupplementDialog = false },
            onConfirm = { text ->
                showSupplementDialog = false
                viewModel.supplement(text)
            },
        )
    }
}

/** 回到底部悬浮钮：淡入淡出 + 轻缩放，避免与外层 ColumnScope 的 AnimatedVisibility 歧义。 */
@Composable
private fun ScrollToBottomFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(AppMotion.fast)) + scaleIn(initialScale = 0.6f),
        exit = fadeOut(tween(AppMotion.fast)),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AppSurface)
                .border(0.5.dp, AppBorderLight, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↓",
                style = MaterialTheme.typography.titleMedium,
                color = AppTextSecondary,
            )
        }
    }
}

// --------------------------------------------------------------------------- //
// 行模型构建：时间分隔 + 连续消息合并
// --------------------------------------------------------------------------- //

private fun buildChatRows(
    messages: List<RoomMessage>,
    pendingSends: List<PendingSend>,
    myRole: String,
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var prevTime: ZonedDateTime? = null
    var prevSender: String? = null
    messages.forEach { msg ->
        val time = parseChatTime(msg.createdAt)
        val gap = if (prevTime != null && time != null) {
            Duration.between(prevTime, time).toMillis()
        } else {
            Long.MAX_VALUE
        }
        if (time != null && (prevTime == null || gap > TIME_DIVIDER_MS)) {
            rows += ChatRow.Divider(chatTimeLabel(time), key = "div_${msg.id}")
        }
        val showHeader = msg.senderType != prevSender || gap > HEADER_MERGE_MS
        rows += ChatRow.Bubble(
            msg = msg,
            mine = isMine(msg.senderType, myRole),
            advisor = msg.senderType == SENDER_ADVISOR,
            showHeader = showHeader,
            key = "msg_${msg.id}",
        )
        if (time != null) {
            prevTime = time
        }
        prevSender = msg.senderType
    }
    pendingSends.forEach { p ->
        rows += ChatRow.Pending(tempId = p.tempId, content = p.content, failed = p.failed)
    }
    return rows
}

// --------------------------------------------------------------------------- //
// 气泡体系（M-2 / M-3 / M-6）
// --------------------------------------------------------------------------- //

@Composable
private fun TimeDividerLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = AppTextTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
    )
}

/** 圆形头像（D1：首字占位；军师用暖色专属徽标）。 */
@Composable
private fun PartyAvatar(mine: Boolean, advisor: Boolean, char: String, modifier: Modifier = Modifier) {
    val bg = when {
        advisor -> AppWarm
        mine -> AppAccent
        else -> AppSurfaceMuted
    }
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (advisor) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = "军师",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        } else {
            Text(
                text = char,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (mine) Color.White else AppTextSecondary,
            )
        }
    }
}

/** 军师气泡内容容器：白卡 + 左侧暖色竖条（M-3：不再用纯橙大色块）。 */
@Composable
private fun AdvisorBubbleBox(content: @Composable () -> Unit) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(AppWarm),
        )
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: RoomMessage,
    mine: Boolean,
    advisor: Boolean,
    showHeader: Boolean,
    partnerName: String,
    partnerAvatarChar: String,
    myAvatarChar: String,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.78f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (!mine) {
            PartyAvatar(mine = false, advisor = advisor, char = partnerAvatarChar)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = maxBubbleWidth),
        ) {
            // 昵称只在「组头」显示（连续消息合并），军师常显暖色
            if (showHeader || advisor) {
                Text(
                    text = if (mine) "我" else senderLabel(msg.senderType, partnerName),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (advisor) AppWarm else AppTextTertiary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            if (advisor) {
                // 军师：白卡 + 左侧暖色竖条
                AdvisorBubbleBox {
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .background(
                            color = if (mine) AppAccent else AppSurfaceMuted,
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 16.dp,
                                bottomStart = if (mine) 16.dp else 4.dp,
                                bottomEnd = if (mine) 4.dp else 16.dp,
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mine) Color.White else AppTextPrimary,
                    )
                }
            }
        }
        if (mine) {
            Spacer(modifier = Modifier.width(6.dp))
            PartyAvatar(mine = true, advisor = false, char = myAvatarChar)
        }
    }
}

/** 乐观上屏气泡（M-7）：发送中半透明；失败红描边、点击重发、长按撤下。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingBubble(
    content: String,
    failed: Boolean,
    myAvatarChar: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (failed) "发送失败 · 点击重发" else "发送中…",
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) AppErrorRed else AppTextTertiary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Box(
                modifier = Modifier
                    .alpha(if (failed) 1f else 0.55f)
                    .background(
                        color = AppAccent.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    )
                    .combinedClickable(
                        onClick = onRetry,
                        onLongClick = onDismiss,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        PartyAvatar(mine = true, advisor = false, char = myAvatarChar)
    }
}

/** 军师流式气泡：白卡 + 暖色竖条 + 闪烁光标（M-3：不再用硬字符光标当唯一反馈）。 */
@Composable
private fun AdvisorStreamingBubble(thinking: String, content: String) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        PartyAvatar(mine = false, advisor = true, char = "军")
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.widthIn(max = screenWidth * 0.78f)) {
            Text(
                text = "军师",
                style = MaterialTheme.typography.labelSmall,
                color = AppWarm,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
            AdvisorBubbleBox {
                Column {
                    if (thinking.isNotBlank() && content.isBlank()) {
                        TypingDots(tint = AppWarm)
                    } else {
                        Row {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextPrimary,
                            )
                            Text(
                                text = "▍",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppWarm.copy(alpha = blink),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 军师打字指示器（M-3）：三点交错呼吸，替代纯文字。 */
@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.padding(start = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingDots(tint = AppTextTertiary)
    }
}

@Composable
private fun TypingDots(tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val transition = rememberInfiniteTransition(label = "dot$index")
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(550),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 180),
                ),
                label = "dotAlpha$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}

// --------------------------------------------------------------------------- //
// 状态区（应答条 / 结束投票横幅 / 结算卡），进出带动画（M-4）
// --------------------------------------------------------------------------- //

@Composable
private fun StateFooter(
    state: RoomState?,
    viewModel: MediationRoomChatViewModel,
    haptics: com.couple.translator.core.ui.components.AppHaptics,
    onSupplement: () -> Unit,
) {
    if (state == null) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 结算 / 结算中
        if (state.status == "settling") {
            AnimatedFooterCard {
                SettlementProgressCard(state = state, onRetry = viewModel::retrySettlement)
            }
        } else if (state.status == "settlement_ready" || state.status == "settled") {
            AnimatedFooterCard {
                SettlementCard(state = state, onConfirm = {
                    haptics.confirm()
                    viewModel.confirmSettlement()
                })
            }
        } else if (state.status == "active") {
            // 结束投票横幅（D-CLOSING：可反悔；一方提议后对方确认才凑齐）
            if (state.endVotePartner && !state.endVoteMe) {
                AnimatedFooterCard {
                    BannerCard(
                        text = "TA 提议结束调解，双方都同意后军师会生成调解书",
                        confirmText = "同意结束",
                        onConfirm = {
                            haptics.confirm()
                            viewModel.voteEnd(true)
                        },
                        dismissText = "暂不",
                        onDismiss = { viewModel.voteEnd(false) },
                    )
                }
            } else if (state.endVoteMe && !state.endVotePartner) {
                AnimatedFooterCard {
                    HintCard(text = "你已提议结束调解，等对方确认后军师会生成调解书")
                }
            }
            // 应答条（§五：双方应答互斥；未应答方只提醒、永不代答）
            if (state.waitingReply) {
                if (!state.agreeMe) {
                    AnimatedFooterCard {
                        BannerCard(
                            text = "军师给出了建议，请双方表态",
                            confirmText = "同意",
                            onConfirm = {
                                haptics.confirm()
                                viewModel.agree()
                            },
                            dismissText = "补充",
                            onDismiss = onSupplement,
                        )
                    }
                } else if (!state.agreePartner) {
                    AnimatedFooterCard {
                        HintCard(text = "你已同意，等待对方表态（期间可以继续自由聊）")
                    }
                }
            }
        }
    }
}

/** 状态卡进场：轻微上滑 + 淡入（M-4），spring 口径与全 App 一致。 */
@Composable
private fun AnimatedFooterCard(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(dampingRatio = AppMotion.SpringDamping, stiffness = Spring.StiffnessLow),
        ) + fadeIn(tween(AppMotion.normal)),
        exit = fadeOut(tween(AppMotion.fast)),
    ) {
        content()
    }
}

@Composable
private fun HintCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppSurfaceMuted),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun BannerCard(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppAccentFaint),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) { Text(confirmText, color = AppAccent) }
                if (dismissText != null && onDismiss != null) {
                    TextButton(onClick = onDismiss) { Text(dismissText, color = AppTextSecondary) }
                }
            }
        }
    }
}

@Composable
private fun SettlementProgressCard(state: RoomState, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("双方已同意结束，军师正在生成调解书…", style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary)
            if (state.settleError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("生成失败：服务端会自动重试；也可手动重试", style = MaterialTheme.typography.bodySmall, color = AppErrorRed)
                TextButton(onClick = onRetry) { Text("手动重试", color = AppAccent) }
            }
        }
    }
}

@Composable
private fun SettlementCard(state: RoomState, onConfirm: () -> Unit) {
    val s = state.settlement ?: return
    // D7：称呼来自后端 party_labels（按资料性别实时计算），前端不再硬编码女方/男方
    val labelA = s.partyLabels?.userA?.takeIf { it.isNotBlank() } ?: "当事人A"
    val labelB = s.partyLabels?.userB?.takeIf { it.isNotBlank() } ?: "当事人B"
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "调解书 · ${s.resultLabel ?: ""}",
                style = MaterialTheme.typography.titleSmall,
                color = AppTextPrimary,
            )
            Text(s.summaryText, style = MaterialTheme.typography.bodySmall, color = AppTextPrimary)
            if (s.agreements.isNotEmpty()) {
                Text("共同约定", style = MaterialTheme.typography.labelMedium, color = AppTextSecondary)
                s.agreements.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = AppTextPrimary) }
            }
            s.responsibilities?.let { r ->
                Text("各自责任", style = MaterialTheme.typography.labelMedium, color = AppTextSecondary)
                if (r.userA.isNotBlank()) Text("· $labelA：${r.userA}", style = MaterialTheme.typography.bodySmall, color = AppTextPrimary)
                if (r.userB.isNotBlank()) Text("· $labelB：${r.userB}", style = MaterialTheme.typography.bodySmall, color = AppTextPrimary)
            }
            if (state.status == "settlement_ready") {
                if (state.confirmMe != true) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AppPrimaryButton(text = "确认调解书", onClick = onConfirm, modifier = Modifier.fillMaxWidth())
                } else if (state.confirmPartner != true) {
                    Text("你已确认，等待对方确认", style = MaterialTheme.typography.bodySmall, color = AppTextTertiary)
                }
            } else {
                Text("已结算", style = MaterialTheme.typography.labelMedium, color = AppTextTertiary)
            }
        }
    }
}

@Composable
private fun SupplementDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补充说明") },
        text = {
            Column {
                Text("补充后军师会重新回应，本轮已按的同意将作废", style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("想补充什么（可不填）") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("发给军师", color = AppAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTextSecondary) }
        },
    )
}
