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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    /**
     * 页面标题。全 App 统一叫「观点」——2026-09-27 用户裁决：观点就是日记，
     * 不再区分单身侧叫法。
     */
    title: String = "观点",
    /** 非空表示这是一个二级页（从抽屉进入），顶栏显示返回而不是抽屉图标。 */
    onNavigateBack: (() -> Unit)? = null,
    viewModel: DiaryListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 保存后回到列表看不到刚写的观点：导航栈复用同一个 ViewModel（popBackStack
    // 不重建它），而 init 里那次 loadDiaries 早已跑完。监听 ON_RESUME 补一次静默刷新。
    // ⚠️ 不能用 remember 标志位跳过「首次」：从写作页返回时本 composable 会被重建
    // （NavHost 会 dispose 离屏页面），remember 状态不保留，标志位必然失效——真机
    // 已复现。多刷一次无害，漏刷一次用户就看不到刚写的观点，所以每次都刷。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                        title = "已选择 ${uiState.selectedIds.size} 条",
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
                } else if (onNavigateBack != null) {
                    // 从情侣抽屉进来时这是二级页：给返回按钮，而不是一个点不动的抽屉图标。
                    // ⚠️ 必须让顶栏自己垫状态栏高度：这条入口走根 NavGraph，外面没有
                    // 外壳 Scaffold 帮它让位——之前写成 false，顶栏和状态栏叠在一起。
                    AppBackTopBar(
                        onBack = onNavigateBack,
                        title = title,
                        applyStatusBarInset = true,
                        trailing = { ComposeEntryButton(onClick = onNavigateToCompose) },
                    )
                } else {
                    AppTopBar(
                        onOpenDrawer = onOpenDrawer,
                        isCoupleMode = false,
                        identity = identity,
                        trailing = { ComposeEntryButton(onClick = onNavigateToCompose) },
                    )
                }

                // 二级页的顶栏已经写了标题，不再叠一个大标题
                if (onNavigateBack == null) {
                    AppPageHeader(
                        title = title,
                        // [W4.5 收缩] 入口语义收成「给军师的私密记录」
                        subtitle = when {
                            uiState.isSelectionMode -> "长按可多选，删除不可恢复。"
                            uiState.diaries.isEmpty() -> "给军师的私密记录，只有你和它能看到。"
                            else -> "已经写下 ${uiState.diaries.size} 条私密记录。"
                        },
                        modifier = Modifier.padding(top = AppSpacing.sm),
                    )
                }

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
                        title = "还没有$title",
                        subtitle = "记录你的生活和心情。",
                        action = {
                            AppPrimaryButton(
                                text = "写第一条$title",
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
                            bottom = 24.dp,
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
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("批量删除") },
            text = { Text("确定要删除选中的 ${uiState.selectedIds.size} 条观点吗？删除后无法恢复。") },
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
            // 2026-09-27：心情/天气已从观点移除，这里改展示正文摘要——
            // 副标题位置空着会让卡片看起来像缺了信息。
            if (diary.content.isNotBlank()) {
                Text(
                    text = diary.content.replace('\n', ' ').take(24),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 顶栏右上角的「写观点」入口：取代原底部的全宽大按钮——
 * 与顶栏标题同行、占位小，把列表页的纵向空间还给内容。
 */
@Composable
private fun ComposeEntryButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = "写观点",
            style = MaterialTheme.typography.labelLarge,
            color = AppAccent,
        )
    }
}
