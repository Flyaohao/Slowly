package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonListCard
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import java.time.LocalDateTime

// 场景名不再本地硬编码：此前这里只有 3 个，比后端少一半，
// 陌生场景的会话就退化成显示原始 scene_key。改由 AiSceneCatalog 统一提供
// （页面上额外包一层 collectAsState，是为了远端目录拉到后能自动重组）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSessionListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (sessionId: Long, sceneKey: String, title: String?, archived: Boolean) -> Unit,
    viewModel: AiSessionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // 订阅场景目录：远端拉到后自动重组，会话标题才会用上新场景名
    val scenes by AiSceneCatalog.scenes.collectAsState()
    val sceneLabel: (String) -> String = { key ->
        scenes.firstOrNull { it.key == key }?.label ?: key
    }
    // P-A §3.2 本地过滤（纯客户端，不打接口）
    var query by remember { mutableStateOf("") }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.clearError() },
        )
    }

    // 过滤 + 分组（🟢J：按最后活跃时间，而非 createdAt）
    val now = remember { LocalDateTime.now() }
    val filtered = uiState.sessions.filter { s ->
        query.isBlank() ||
            (s.title ?: "").contains(query, ignoreCase = true) ||
            sceneLabel(s.sceneKey).contains(query, ignoreCase = true)
    }
    val grouped: List<Pair<String, List<AiDto.SessionResponse>>> = remember(filtered, now) {
        val buckets = LinkedHashMap<SessionBucket, MutableList<AiDto.SessionResponse>>()
        for (s in filtered) {
            val activeIso = s.lastMessageAt ?: s.createdAt
            val b = sessionBucketOf(activeIso, now)
            buckets.getOrPut(b) { mutableListOf() }.add(s)
        }
        SessionBucket.entries
            .mapNotNull { b -> buckets[b]?.let { b.label to it } }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            // P-A §3.1 D5：顶栏「＋」→ 回军师页开新对话（不在此页 close）
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "历史会话",
                trailing = {
                    IconButton(
                        onClick = {
                            PendingSessionHolder.setNewChat()
                            onNavigateBack()
                        }
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "新建会话",
                            tint = com.couple.translator.core.ui.theme.AppTextSecondary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        SkeletonListCard(rows = 4)
                    }
                    uiState.sessions.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppEmptyState(
                                icon = Icons.Outlined.Psychology,
                                title = "还没有会话记录",
                                // P0-10B：空态说清价值——会话会被记住
                                subtitle = "军师会记住你们的每一段对话，聊过的内容随时可以回来看。",
                            )
                        }
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // P-A §3.2：本地搜索框
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AppSpacing.screenH, vertical = 8.dp),
                                placeholder = { Text("搜索标题或场景", color = AppTextTertiary) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppAccent,
                                    unfocusedBorderColor = com.couple.translator.core.ui.theme.AppBorderLight,
                                    cursorColor = com.couple.translator.core.ui.theme.AppTextPrimary,
                                ),
                            )

                            if (grouped.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "没有匹配「$query」的会话",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AppTextTertiary,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    // 会话卡片与搜索框同宽：左右留屏边距，不再贴边
                                    contentPadding = PaddingValues(horizontal = AppSpacing.screenH),
                                ) {
                                    grouped.forEach { (label, list) ->
                                        item(key = "header_$label") {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = AppTextSecondary,
                                                modifier = Modifier.padding(
                                                    start = 4.dp,
                                                    top = 12.dp,
                                                    bottom = 4.dp,
                                                ),
                                            )
                                        }
                                        items(list, key = { it.id }) { session ->
                                            SessionItem(
                                                session = session,
                                                sceneLabel = sceneLabel,
                                                now = now,
                                                onClick = {
                                                    onNavigateToSession(
                                                        session.id,
                                                        session.sceneKey,
                                                        session.title,
                                                        session.status == "archived",
                                                    )
                                                },
                                                onDelete = { viewModel.deleteSession(session.id) },
                                            )
                                        }
                                    }
                                    item { Spacer(modifier = Modifier.height(8.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: AiDto.SessionResponse,
    sceneLabel: (String) -> String,
    // P-A §0.4：时间桶的 now 单源自屏幕顶部——每项各自取时会在跨分钟时
    // 出现「分组标题与行内时间用的不是同一个 now」的错位。
    now: LocalDateTime,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // P-A §3.2 🟢K：裸删除图标 → 溢出菜单 + 二次确认
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这段对话？") },
            // P-A §0.1：文案如实化——删除只影响聊天记录，长期记忆不连坐
            text = { Text("删除后聊天记录不可恢复。军师从对话里记住的长期记忆不会一起删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text("删除", color = AppErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消")
                }
            },
        )
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.title ?: sceneLabel(session.sceneKey),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // P0-10B：分段/结束在列表里可感知
                    if (session.status == "archived") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "已结束",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextTertiary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AppSurfaceMuted)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row {
                    Text(
                        text = sceneLabel(session.sceneKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppAccent,
                    )
                    // 🟢J：显示时间用最后活跃，组内今天/昨天 HH:mm、更早 MM-dd
                    val activeIso = session.lastMessageAt ?: session.createdAt
                    val bucket = sessionBucketOf(activeIso, now)
                    val timeLabel = bucketTimeLabel(activeIso, bucket)
                    if (timeLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                    // 副标题补 messageCount（后端已返回、DTO 已接）
                    if (session.messageCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${session.messageCount} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                }
            }

            // 溢出菜单（替代裸删除，防误触）
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = AppTextTertiary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("删除", color = AppErrorRed) },
                        onClick = {
                            menuOpen = false
                            confirmDelete = true
                        },
                    )
                }
            }
        }
    }
}
