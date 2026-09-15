package com.couple.translator.feature.couple.wishlist

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import com.couple.translator.feature.couple.data.model.WishlistDto
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSuccessGreen
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAdd: () -> Unit,
    viewModel: WishlistViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("愿望清单", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = AppAccent,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "许愿", tint = AppSurface)
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

        if (uiState.items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有愿望",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextTertiary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "许下你们想一起做的事",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }
            return@PullToRefreshLayout
        }

        val pending = uiState.items.filter { it.status == "pending" }
        val completed = uiState.items.filter { it.status == "completed" }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pending.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "想做的事",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppAccent,
                    )
                }
                items(pending) { item ->
                    WishlistItemCard(
                        item = item,
                        onComplete = { viewModel.completeWishlist(item.id) },
                        onDelete = { viewModel.deleteWishlist(item.id) },
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "已完成",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppSuccessGreen,
                    )
                }
                items(completed) { item ->
                    WishlistItemCard(
                        item = item,
                        onComplete = {},
                        onDelete = { viewModel.deleteWishlist(item.id) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
        }
    }
}

@Composable
private fun WishlistItemCard(
    item: WishlistDto.WishlistResponse,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCompleted = item.status == "completed"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCompleted) {
                AppSurface.copy(alpha = 0.7f)
            } else {
                AppSurface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (item.description != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                        maxLines = 2,
                    )
                }
                if (isCompleted && item.completedAt != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "完成于 ${item.completedAt.take(10)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppSuccessGreen,
                    )
                }
            }
            if (!isCompleted) {
                IconButton(onClick = onComplete) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "标记完成",
                        tint = AppSuccessGreen,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = AppErrorRed)
            }
        }
    }
}
