package com.couple.translator.feature.single.diary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppFilterChip
import com.couple.translator.core.ui.components.AppLinkText
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.AppTopBarAction
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonListCard
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.feature.single.data.model.DiaryDto

@Composable
fun DiaryListScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToCompose: () -> Unit,
    identity: TopBarIdentity = TopBarIdentity(),
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
            Column(modifier = Modifier.fillMaxSize()) {
                if (uiState.isSelectionMode) {
                    // 选择态顶栏：返回 + 计数 + 全选 + 删除，复用二级页顶栏的形状。
                    // 本页是 tab 页、处在外壳 Scaffold 的内容区里，顶部 inset 已经由外壳让过位，
                    // 所以这里要关掉顶栏自带的状态栏间距，否则会被垫高两次。
                    AppBackTopBar(
                        onBack = { viewModel.toggleSelectionMode() },
                        title = "已选择 ${uiState.selectedIds.size} 篇",
                        applyStatusBarInset = false,
                        trailing = {
                            AppLinkText(label = "全选", onClick = { viewModel.selectAll() })
                            AppTopBarAction(
                                icon = Icons.Outlined.Delete,
                                contentDescription = "删除",
                                onClick = { showDeleteDialog = true },
                                tint = AppErrorRed,
                            )
                        },
                    )
                } else {
                    AppTopBar(
                        onOpenDrawer = onOpenDrawer,
                        isCoupleMode = false,
                        identity = identity,
                        // [W4.5 收缩] 批量管理入口隐藏（日记不再发展笔记软件能力，隐藏 ≠ 删除）
                        // trailing = {
                        //     AppTopBarAction(
                        //         icon = Icons.Outlined.Delete,
                        //         contentDescription = "批量删除",
                        //         onClick = { viewModel.toggleSelectionMode() },
                        //         tint = AppTextSecondary,
                        //     )
                        // },
                    )
                }

                AppPageHeader(
                    title = "日记",
                    // [W4.5 收缩] 入口语义收成「给军师的私密记录」
                    subtitle = when {
                        uiState.isSelectionMode -> "长按可多选，删除不可恢复。"
                        uiState.diaries.isEmpty() -> "给军师的私密记录，只有你和它能看到。"
                        else -> "已经写下 ${uiState.diaries.size} 条私密记录。"
                    },
                    modifier = Modifier.padding(top = AppSpacing.sm),
                )

                Spacer(modifier = Modifier.height(AppSpacing.lg))

                if (!uiState.isSelectionMode) {
                    DiaryFilterRow(
                        currentFilter = uiState.filterType,
                        onFilterChanged = { viewModel.loadDiaries(it) },
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                }

                when {
                    uiState.isLoading -> SkeletonListCard(rows = 4, withLeading = false)

                    uiState.diaries.isEmpty() -> AppEmptyState(
                        icon = Icons.Outlined.Book,
                        title = "还没有日记",
                        subtitle = "记录你的生活和心情。",
                        action = {
                            AppPrimaryButton(
                                text = "写第一篇日记",
                                icon = Icons.Outlined.Edit,
                                onClick = onNavigateToCompose,
                                modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                            )
                        },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = AppSpacing.screenH,
                            end = AppSpacing.screenH,
                            bottom = 108.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                    // [W4.5 收缩] 长按多选（批量删除）入口隐藏，隐藏 ≠ 删除
                                    // if (!uiState.isSelectionMode) {
                                    //     viewModel.toggleSelectionMode()
                                    //     viewModel.toggleSelect(diary.id)
                                    // }
                                },
                                // [W4.5 收缩] 收藏入口隐藏，隐藏 ≠ 删除
                                // onToggleFavorite = { viewModel.toggleFavorite(diary.id) },
                                onToggleFavorite = {},
                            )
                        }
                    }
                }
            }
        }

        // 主操作固定在底部：和首页/信箱的"全宽黑按钮"是同一个动作语义
        if (!uiState.isSelectionMode) {
            AppPrimaryButton(
                text = "写日记",
                icon = Icons.Outlined.Edit,
                onClick = onNavigateToCompose,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = AppSpacing.screenH)
                    .padding(bottom = AppSpacing.lg),
            )
        }
    }

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
                    Text("删除", color = AppErrorRed)
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
private fun DiaryFilterRow(
    currentFilter: String,
    onFilterChanged: (String) -> Unit,
) {
    val filters = listOf(
        "all" to "全部",
        "week" to "本周",
        "month" to "本月",
        // [W4.5 收缩] 收藏筛选随收藏能力一并隐藏（隐藏 ≠ 删除）
        // "favorite" to "收藏",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        filters.forEach { (key, label) ->
            AppFilterChip(
                text = label,
                selected = currentFilter == key,
                onClick = { onFilterChanged(key) },
            )
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
    AppCard(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (isSelected) "已选择" else "未选择",
                    tint = if (isSelected) AppAccent else AppTextTertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(AppSpacing.md))
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AppRadius.md))
                    .background(AppSurfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "📝", style = MaterialTheme.typography.titleSmall)
            }

            Spacer(modifier = Modifier.width(AppSpacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diary.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTextPrimary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = diary.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                    maxLines = 2,
                )
            }

            // [W4.5 收缩] 收藏按钮入口隐藏（隐藏 ≠ 删除）
            // if (!isSelectionMode) {
            //     Box(
            //         modifier = Modifier
            //             .size(32.dp)
            //             .clip(CircleShape)
            //             .pressFeedback(pressedScale = 0.9f, onClick = onToggleFavorite),
            //         contentAlignment = Alignment.Center,
            //     ) {
            //         Icon(
            //             imageVector = if (diary.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            //             contentDescription = if (diary.isFavorite) "取消收藏" else "收藏",
            //             tint = if (diary.isFavorite) AppAccent else AppTextTertiary,
            //             modifier = Modifier.size(17.dp),
            //         )
            //     }
            // }
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Text(
                text = diary.createdAt?.take(10) ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
            )
            if (!diary.mood.isNullOrBlank()) {
                Text(
                    text = diary.mood,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
        }
    }
}
