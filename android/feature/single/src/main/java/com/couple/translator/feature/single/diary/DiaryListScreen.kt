package com.couple.translator.feature.single.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.single.data.model.DiaryDto
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.Surface

@Composable
fun DiaryListScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToCompose: () -> Unit,
    viewModel: DiaryListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Top bar
                DiaryTopBar(
                    onOpenDrawer = onOpenDrawer,
                    isSelectionMode = uiState.isSelectionMode,
                    selectedCount = uiState.selectedIds.size,
                    onToggleSelectionMode = { viewModel.toggleSelectionMode() },
                    onSelectAll = { viewModel.selectAll() },
                    onDeleteSelected = { showDeleteDialog = true },
                )

                // Filter tabs
                if (!uiState.isSelectionMode) {
                    DiaryFilterTabs(
                        currentFilter = uiState.filterType,
                        onFilterChanged = { viewModel.loadDiaries(it) },
                    )
                }

                if (uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LoadingIndicator()
                    }
                } else if (uiState.diaries.isEmpty()) {
                    DiaryEmptyState(onCompose = onNavigateToCompose)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(uiState.diaries, key = { it.id }) { diary ->
                            DiaryItem(
                                diary = diary,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = diary.id in uiState.selectedIds,
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleSelect(diary.id)
                                    } else {
                                        onNavigateToDetail(diary.id)
                                    }
                                },
                                onLongClick = {
                                    if (!uiState.isSelectionMode) {
                                        viewModel.toggleSelectionMode()
                                        viewModel.toggleSelect(diary.id)
                                    }
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(diary.id) },
                            )
                        }
                    }
                }
            }
        }

        // FAB
        if (!uiState.isSelectionMode) {
            FloatingActionButton(
                onClick = onNavigateToCompose,
                containerColor = Accent,
                contentColor = Surface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "写日记")
            }
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("批量删除") },
            text = { Text("确定要删除选中的 ${uiState.selectedIds.size} 篇日记吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.batchDelete()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun DiaryTopBar(
    onOpenDrawer: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedCount: Int = 0,
    onToggleSelectionMode: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelectionMode) {
            IconButton(onClick = onToggleSelectionMode) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "取消选择",
                    tint = AppTextPrimary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "已选择 $selectedCount 篇",
                style = MaterialTheme.typography.titleMedium,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) {
                Text("全选", color = Accent)
            }
            IconButton(onClick = onDeleteSelected) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            IconButton(onClick = onOpenDrawer) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(AppAccentLight),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Book,
                        contentDescription = "打开侧边栏",
                        tint = Accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "日记",
                style = MaterialTheme.typography.titleMedium,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleSelectionMode) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "批量删除",
                    tint = AppTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun DiaryFilterTabs(
    currentFilter: String,
    onFilterChanged: (String) -> Unit,
) {
    val filters = listOf(
        "all" to "全部",
        "week" to "本周",
        "month" to "本月",
        "favorite" to "收藏",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { (key, label) ->
            val isSelected = currentFilter == key
            Surface(
                onClick = { onFilterChanged(key) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) AppTextPrimary else AppSurface,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) Surface else AppTextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiaryItem(
    diary: DiaryDto.DiaryResponse,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (isSelected) "已选择" else "未选择",
                    tint = if (isSelected) Accent else AppTextTertiary,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diary.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTextPrimary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = diary.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                    maxLines = 2,
                )
            }
            if (!isSelectionMode) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (diary.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (diary.isFavorite) "取消收藏" else "收藏",
                        tint = if (diary.isFavorite) Accent else AppTextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (diary.mood != null) {
                Text(
                    text = diary.mood,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
            Text(
                text = diary.createdAt?.take(10) ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
            )
        }
    }
    HorizontalDivider(color = AppBorderLight, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun DiaryEmptyState(onCompose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Book,
            contentDescription = null,
            tint = AppTextTertiary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "还没有日记",
            style = MaterialTheme.typography.titleMedium,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "记录你的生活和心情",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Surface(
            onClick = onCompose,
            shape = RoundedCornerShape(50),
            color = Accent,
        ) {
            Text(
                text = "写第一篇日记",
                style = MaterialTheme.typography.titleSmall,
                color = Surface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
