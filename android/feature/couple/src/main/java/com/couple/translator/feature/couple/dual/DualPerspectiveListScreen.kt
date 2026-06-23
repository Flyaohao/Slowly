package com.couple.translator.feature.couple.dual

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.model.DualPerspectiveDto
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPerspectiveListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToCreate: () -> Unit,
    viewModel: DualPerspectiveListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("双视角记录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreate,
                containerColor = Accent,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建事件", tint = Surface)
            }
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

        if (uiState.events.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextTertiary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击右下角创建第一个事件",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
            return@PullToRefreshLayout
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(uiState.events) { event ->
                DualEventListItem(
                    event = event,
                    onClick = { onNavigateToDetail(event.id) },
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
        }
    }
}

@Composable
private fun DualEventListItem(
    event: DualPerspectiveDto.DualEventResponse,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = eventStatusText(event.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                    Text(
                        text = event.eventTime?.take(10) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }
            Text(
                text = eventStatusText(event.status),
                style = MaterialTheme.typography.labelSmall,
                color = if (event.status == "completed") Accent else TextSecondary,
            )
        }
    }
}

private fun eventStatusText(status: String): String = when (status) {
    "one_side" -> "等待对方"
    "both_sides" -> "待公开"
    "completed" -> "已公开"
    else -> status
}
