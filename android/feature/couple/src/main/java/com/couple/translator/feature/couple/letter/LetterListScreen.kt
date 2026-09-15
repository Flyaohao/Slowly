package com.couple.translator.feature.couple.letter

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppLinkText
import com.couple.translator.core.ui.components.AppTopBarAction
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonListCard
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppOnAccent
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.feature.couple.data.model.LetterDto

private val coupleTabs = listOf("全部", "收到", "发出", "草稿", "未来", "冷静", "未说出口", "私密")
private val diaryTabs = listOf("全部", "本周", "本月", "收藏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetterListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
    isCoupleMode: Boolean = true,
    viewModel: LetterListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val tabs = if (isCoupleMode) coupleTabs else diaryTabs
    val safeTabIndex = uiState.selectedTab.coerceIn(0, tabs.size - 1)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.selectTab(0)
        viewModel.loadLetters(tabs[0])
    }

    // Delete confirmation
    if (showDeleteConfirm && uiState.selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${uiState.selectedIds.size} 封信件吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.batchDelete()
                }) {
                    Text("删除", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = {
                    if (uiState.isSelectionMode) viewModel.toggleSelectionMode() else onNavigateBack()
                },
                title = if (uiState.isSelectionMode) {
                    "已选 ${uiState.selectedIds.size} 项"
                } else {
                    if (isCoupleMode) "全部信件" else "全部日记"
                },
                trailing = {
                    if (uiState.isSelectionMode) {
                        AppLinkText(label = "全选", onClick = { viewModel.selectAll() })
                        AppTopBarAction(
                            icon = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = AppErrorRed,
                            onClick = { showDeleteConfirm = true },
                        )
                    } else {
                        AppTopBarAction(
                            icon = Icons.Default.CheckCircle,
                            contentDescription = "选择",
                            tint = AppTextSecondary,
                            onClick = { viewModel.toggleSelectionMode() },
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
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Tabs
                ScrollableTabRow(
                    selectedTabIndex = safeTabIndex,
                    tabs = tabs,
                    onTabClick = { index, title ->
                        viewModel.selectTab(index)
                        viewModel.loadLetters(title)
                    },
                )

                when {
                    uiState.isLoading -> {
                        SkeletonListCard(rows = 4)
                    }
                    uiState.letters.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppEmptyState(
                                icon = if (isCoupleMode) Icons.Outlined.MailOutline else Icons.Outlined.Edit,
                                title = if (isCoupleMode) "还没有信件" else "还没有日记",
                                subtitle = "点击右下角按钮写一封吧",
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = AppSpacing.screenH),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item { Spacer(modifier = Modifier.height(8.dp)) }
                            items(uiState.letters, key = { it.id }) { letter ->
                                LetterListItem(
                                    letter = letter,
                                    isCoupleMode = isCoupleMode,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = uiState.selectedIds.contains(letter.id),
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelect(letter.id)
                                        } else {
                                            onNavigateToLetterDetail(letter.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (!uiState.isSelectionMode) {
                                            viewModel.toggleSelectionMode()
                                            viewModel.toggleSelect(letter.id)
                                        }
                                    },
                                )
                            }
                            item { Spacer(modifier = Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollableTabRow(
    selectedTabIndex: Int,
    tabs: List<String>,
    onTabClick: (Int, String) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = AppBackground,
        contentColor = AppAccent,
        indicator = { tabPositions ->
            if (selectedTabIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = AppAccent,
                )
            }
        },
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = { onTabClick(index, title) },
                text = { Text(title, style = MaterialTheme.typography.labelMedium) },
                selectedContentColor = AppAccent,
                unselectedContentColor = AppTextTertiary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LetterListItem(
    letter: LetterDto.LetterResponse,
    isCoupleMode: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bgColor = if (isSelected) AppAccentLight else AppSurface
    val borderColor = if (isSelected) AppAccent else AppBorderLight

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        containerColor = bgColor,
        borderColor = borderColor,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection checkbox
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) AppAccent else AppBorderLight),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = AppOnAccent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Letter content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = letter.title?.ifBlank { "无标题" } ?: "无标题",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (letter.isFavorite) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = AppAccent, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = letter.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isCoupleMode) {
                            // Couple mode: show letter type
                            TypeBadge(letterTypeName(letter.letterType))
                        }
                        // Status badge (draft)
                        if (letter.status == "draft") {
                            TypeBadge("草稿", color = AppTextTertiary)
                        }
                    }
                    Text(
                        text = formatDate(letter.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(text: String, color: Color = AppAccent) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (color == AppAccent) AppAccentLight else color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun letterTypeName(type: String): String = when (type) {
    "normal" -> "普通信"
    "future" -> "未来信"
    "calm" -> "冷静信"
    "unsaid" -> "未说出口"
    "private" -> "私密信"
    "anniversary" -> "纪念信"
    "shared" -> "共同信"
    "reconcile" -> "和好信"
    else -> type
}

private fun formatDate(isoString: String?): String {
    if (isoString == null) return ""
    return try {
        val odt = java.time.OffsetDateTime.parse(isoString)
        odt.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    } catch (_: Exception) {
        try {
            val dt = java.time.LocalDateTime.parse(isoString)
            dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        } catch (_: Exception) {
            isoString.take(16)
        }
    }
}
