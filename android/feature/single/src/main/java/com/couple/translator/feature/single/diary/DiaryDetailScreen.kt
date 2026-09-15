package com.couple.translator.feature.single.diary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

// ==================== TOC 数据模型 ====================

/** 目录条目 */
private data class TocEntry(
    val level: Int,       // 1 = #, 2 = ##, 3 = ###, etc.
    val title: String,    // heading text (without #)
    val lineIndex: Int,   // 在原始文本中的行号
)

/**
 * 从 Markdown 文本中解析标题目录
 */
private fun parseHeadings(markdown: String): List<TocEntry> {
    val entries = mutableListOf<TocEntry>()
    markdown.lines().forEachIndexed { index, line ->
        val trimmed = line.trimStart()
        val match = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (match != null) {
            val level = match.groupValues[1].length
            val title = match.groupValues[2].trim()
            entries.add(TocEntry(level = level, title = title, lineIndex = index))
        }
    }
    return entries
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryDetailScreen(
    diaryId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: DiaryDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // TOC 相关状态
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(diaryId) {
        viewModel.loadDiary(diaryId)
    }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) {
            onNavigateBack()
        }
    }

    val diary = uiState.diary
    val tocEntries = remember(diary?.content) {
        diary?.content?.let { parseHeadings(it) } ?: emptyList()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // TOC 侧边栏（从右侧打开）
            TocDrawerContent(
                entries = tocEntries,
                onEntryClick = {
                    scope.launch { drawerState.close() }
                },
            )
        },
        gesturesEnabled = drawerState.isOpen,
    ) {
        Scaffold(
            containerColor = AppBackground,
            topBar = {
                AppBackTopBar(
                    onBack = onNavigateBack,
                    title = "日记详情",
                    trailing = {
                        if (diary != null) {
                            // TOC 按钮（仅当有标题时显示）
                            if (tocEntries.isNotEmpty()) {
                                IconButton(onClick = {
                                    scope.launch {
                                        if (drawerState.isClosed) drawerState.open()
                                        else drawerState.close()
                                    }
                                }) {
                                    Icon(Icons.Outlined.List, contentDescription = "目录")
                                }
                            }
                            IconButton(onClick = { viewModel.toggleFavorite() }) {
                                Icon(
                                    imageVector = if (diary.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (diary.isFavorite) "取消收藏" else "收藏",
                                    tint = if (diary.isFavorite) AppAccent else AppTextSecondary,
                                )
                            }
                            IconButton(onClick = { onNavigateToEdit(diaryId) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除")
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            PullToRefreshLayout(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.padding(innerPadding),
            ) {
                when {
                    uiState.isLoading -> {
                        SkeletonDetailPage()
                    }
                    diary != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                        ) {
                            // 标题
                            Text(
                                text = diary.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = AppTextPrimary,
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 时间和心情
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = diary.createdAt?.take(16) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTextTertiary,
                                )
                                if (diary.mood != null) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = AppSurface,
                                    ) {
                                        Text(
                                            text = diary.mood,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppTextSecondary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }

                            // 显示更新时间（如果与创建时间不同）
                            if (diary.updatedAt != null && diary.updatedAt != diary.createdAt) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "最后编辑：${diary.updatedAt.take(16)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTextTertiary,
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // 内容 — 使用 Markwon 渲染 Markdown
                            MarkdownContent(content = diary.content)

                            Spacer(modifier = Modifier.height(40.dp))
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除日记") },
            text = { Text("确定要删除这篇日记吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteDiary()
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

// ==================== Markdown 内容渲染 ====================

/**
 * 使用 Markwon 渲染 Markdown 内容。
 * 复用 ProfileResultScreen 中已有的 AndroidView + Markwon 模式。
 */
@Composable
private fun MarkdownContent(content: String) {
    val textColor = AppTextPrimary
    AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                this.setTextColor(textColor.toArgb())
                this.textSize = 16f
                this.setLineSpacing(0f, 1.5f)
            }
        },
        update = { textView ->
            val markwon = Markwon.create(textView.context)
            markwon.setMarkdown(textView, content)
            textView.setTextColor(textColor.toArgb())
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ==================== TOC 侧边栏 ====================

@Composable
private fun TocDrawerContent(
    entries: List<TocEntry>,
    onEntryClick: (TocEntry) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = AppBackground,
        modifier = Modifier.width(280.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = "目录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (entries.isEmpty()) {
                Text(
                    text = "暂无目录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextTertiary,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { entry ->
                        TocEntryItem(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TocEntryItem(
    entry: TocEntry,
    onClick: () -> Unit,
) {
    // 根据标题级别缩进
    val indent = ((entry.level - 1) * 16).dp
    val textStyle = when (entry.level) {
        1 -> MaterialTheme.typography.titleSmall
        2 -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodySmall
    }
    val textColor = when (entry.level) {
        1 -> AppTextPrimary
        2 -> AppTextSecondary
        else -> AppTextTertiary
    }

    Text(
        text = entry.title,
        style = textStyle,
        color = textColor,
        fontWeight = if (entry.level <= 2) FontWeight.Medium else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .fillMaxWidth()
            .padding(start = indent, top = 6.dp, bottom = 6.dp),
    )
}
