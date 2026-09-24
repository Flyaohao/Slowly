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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

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

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.clearError() },
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "历史会话",
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
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = AppSpacing.screenH),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item { Spacer(modifier = Modifier.height(8.dp)) }

                            items(uiState.sessions) { session ->
                                SessionItem(
                                    session = session,
                                    sceneLabel = sceneLabel,
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

                            item { Spacer(modifier = Modifier.height(8.dp)) }
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
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
                    Spacer(modifier = Modifier.width(8.dp))
                    session.createdAt?.let {
                        Text(
                            text = it.take(10),
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

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = AppTextTertiary,
                )
            }
        }
    }
}
