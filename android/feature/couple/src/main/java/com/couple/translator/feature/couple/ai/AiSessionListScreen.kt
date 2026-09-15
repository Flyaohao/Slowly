package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

// 场景名不再本地硬编码：此前这里只有 3 个，比后端少一半，
// 陌生场景的会话就退化成显示原始 scene_key。改由 AiSceneCatalog 统一提供
// （页面上额外包一层 collectAsState，是为了远端目录拉到后能自动重组）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSessionListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (Long, String) -> Unit,
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
        topBar = {
            TopAppBar(
                title = { Text("历史会话") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                ),
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        if (uiState.isLoading) {
            LoadingIndicator()
            return@PullToRefreshLayout
        }

        if (uiState.sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有会话记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextSecondary,
                )
            }
            return@PullToRefreshLayout
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            items(uiState.sessions) { session ->
                SessionItem(
                    session = session,
                    sceneLabel = sceneLabel,
                    onClick = { onNavigateToSession(session.id, session.sceneKey) },
                    onDelete = { viewModel.deleteSession(session.id) },
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title ?: sceneLabel(session.sceneKey),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

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
