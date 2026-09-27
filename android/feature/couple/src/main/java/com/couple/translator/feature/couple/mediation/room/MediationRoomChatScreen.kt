package com.couple.translator.feature.couple.mediation.room

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.AppWarm
import com.couple.translator.feature.couple.data.model.MediationRoomDto.RoomMessage
import com.couple.translator.feature.couple.data.model.MediationRoomDto.RoomState

private const val ROLE_USER_A = "user_a"
private const val ROLE_USER_B = "user_b"
private const val SENDER_ADVISOR = "advisor"

private fun senderLabel(senderType: String, myRole: String): String = when (senderType) {
    ROLE_USER_A -> if (myRole == ROLE_USER_A) "我" else "对方"
    ROLE_USER_B -> if (myRole == ROLE_USER_B) "我" else "对方"
    SENDER_ADVISOR -> "军师"
    else -> ""
}

private fun isMine(senderType: String, myRole: String): Boolean = senderType == myRole

/**
 * 共同调解室聊天页（设计 §一：压栈全屏二级页，UI=聊天窗口，三角色气泡）。
 *
 * 注意 Compose 红线：inline composable（Column/Row/Box/LazyColumn item）里
 * 禁止提前 return@，全部用 if/else 分支表达。
 */
@Composable
fun MediationRoomChatScreen(
    roomId: Long,
    onNavigateBack: () -> Unit,
    viewModel: MediationRoomChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 输入草稿：跨导航返回不丢（rememberSaveable，NavHost dispose 红线）
    var draft by rememberSaveable { mutableStateOf("") }
    var mentionAdvisor by rememberSaveable { mutableStateOf(false) }
    var showSupplementDialog by remember { mutableStateOf(false) }

    LaunchedEffect(roomId) { viewModel.bind(roomId) }

    LaunchedEffect(uiState.toast) {
        if (uiState.toast.isNotBlank()) {
            Toast.makeText(context, uiState.toast, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    LaunchedEffect(uiState.messages.size, uiState.advisorStreamingContent, uiState.advisorThinking) {
        val extra = if (uiState.advisorStreaming) 1 else 0
        val last = uiState.messages.size + extra - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppBackTopBar(
            onBack = onNavigateBack,
            title = uiState.roomName.ifBlank { "共同调解室" },
            subtitle = uiState.styleLabel.ifBlank { null },
            trailing = {
                val st = uiState.state
                if (st != null && st.status == "active") {
                    TextButton(onClick = { viewModel.voteEnd(!st.endVoteMe) }) {
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.messages, key = { it.id }) { msg ->
                    MessageBubble(msg = msg, myRole = uiState.state?.myRole ?: ROLE_USER_A)
                }
                if (uiState.advisorStreaming) {
                    item(key = "streaming") {
                        AdvisorStreamingBubble(
                            thinking = uiState.advisorThinking,
                            content = uiState.advisorStreamingContent,
                        )
                    }
                } else if (uiState.advisorInputPending) {
                    item(key = "pending") {
                        Text(
                            text = "军师正在输入…",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                item(key = "footer") {
                    StateFooter(
                        state = uiState.state,
                        viewModel = viewModel,
                        onSupplement = { showSupplementDialog = true },
                    )
                }
            }
        }

        // 输入栏（状态收口：只有 active 才能发言）
        InputBar(
            enabled = uiState.state?.status == "active",
            draft = draft,
            onDraftChange = { draft = it },
            mention = mentionAdvisor,
            onMentionChange = { mentionAdvisor = it },
            onSend = {
                viewModel.sendMessage(draft, mentionAdvisor)
                draft = ""
                mentionAdvisor = false
            },
        )
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

@Composable
private fun MessageBubble(msg: RoomMessage, myRole: String) {
    val mine = isMine(msg.senderType, myRole)
    val isAdvisor = msg.senderType == SENDER_ADVISOR
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Text(
                text = senderLabel(msg.senderType, myRole),
                style = MaterialTheme.typography.labelSmall,
                color = if (isAdvisor) AppWarm else AppTextTertiary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(
                        color = when {
                            isAdvisor -> AppWarm
                            mine -> AppAccent
                            else -> AppSurfaceMuted
                        },
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
                    color = if (mine || isAdvisor) androidx.compose.ui.graphics.Color.White
                    else AppTextPrimary,
                )
            }
        }
    }
}

@Composable
private fun AdvisorStreamingBubble(thinking: String, content: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "军师",
            style = MaterialTheme.typography.labelSmall,
            color = AppWarm,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(AppWarm, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                if (thinking.isNotBlank() && content.isBlank()) {
                    Text(
                        text = "（正在思考…）",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f),
                    )
                }
                Text(
                    text = content + "▍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
    }
}

/** 状态区（应答条 / 结束投票横幅 / 结算卡），非 inline 容器，分支用 if/else。 */
@Composable
private fun StateFooter(
    state: RoomState?,
    viewModel: MediationRoomChatViewModel,
    onSupplement: () -> Unit,
) {
    if (state == null) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 结算 / 结算中
        if (state.status == "settling") {
            SettlementProgressCard(state = state, onRetry = viewModel::retrySettlement)
        } else if (state.status == "settlement_ready" || state.status == "settled") {
            SettlementCard(state = state, onConfirm = viewModel::confirmSettlement)
        } else if (state.status == "active") {
            // 结束投票横幅（D-CLOSING：可反悔；一方提议后对方确认才凑齐）
            if (state.endVotePartner && !state.endVoteMe) {
                BannerCard(
                    text = "TA 提议结束调解，双方都同意后军师会生成调解书",
                    confirmText = "同意结束",
                    onConfirm = { viewModel.voteEnd(true) },
                    dismissText = "暂不",
                    onDismiss = { viewModel.voteEnd(false) },
                )
            } else if (state.endVoteMe && !state.endVotePartner) {
                HintCard(text = "你已提议结束调解，等对方确认后军师会生成调解书")
            }
            // 应答条（§五：双方应答互斥；未应答方只提醒、永不代答）
            if (state.waitingReply) {
                if (!state.agreeMe) {
                    BannerCard(
                        text = "军师给出了建议，请双方表态",
                        confirmText = "同意",
                        onConfirm = viewModel::agree,
                        dismissText = "补充",
                        onDismiss = onSupplement,
                    )
                } else if (!state.agreePartner) {
                    HintCard(text = "你已同意，等待对方表态（期间可以继续自由聊）")
                }
            }
        }
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
                if (r.userA.isNotBlank()) Text("· 女方：${r.userA}", style = MaterialTheme.typography.bodySmall, color = AppTextPrimary)
                if (r.userB.isNotBlank()) Text("· 男方：${r.userB}", style = MaterialTheme.typography.bodySmall, color = AppTextPrimary)
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
private fun InputBar(
    enabled: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    mention: Boolean,
    onMentionChange: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    if (!enabled) {
        Text(
            text = "调解已进入结算流程，房间不再收新消息",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = mention,
                onClick = { onMentionChange(!mention) },
                label = { Text("@军师") },
                leadingIcon = {
                    Icon(Icons.Filled.AlternateEmail, contentDescription = null)
                },
            )
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (mention) "向军师提问…" else "说点什么…") },
                maxLines = 4,
            )
            IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (draft.isNotBlank()) AppAccent else AppTextTertiary,
                )
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
