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
import androidx.compose.material3.Switch
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
import com.couple.translator.core.data.model.ProfileDimensionLabels
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppPrimaryButton
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

/**
 * 与 ViewModel / 服务端 `ENRICH_MIN_CONFIDENCE` 对齐的提示阈值。
 * 只影响文案（低于它就明说「把握偏低」），真正拦不拦仍在服务端。
 */
private const val ENRICH_HINT_CONFIDENCE = 0.6f

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
                    title = "观点详情",
                    trailing = {
                        if (diary != null) {
                            // [W4.5 收缩] 目录（Markdown 标题导航）入口隐藏，隐藏 ≠ 删除
                            // if (tocEntries.isNotEmpty()) {
                            //     IconButton(onClick = {
                            //         scope.launch {
                            //             if (drawerState.isClosed) drawerState.open()
                            //             else drawerState.close()
                            //         }
                            //     }) {
                            //         Icon(Icons.Outlined.List, contentDescription = "目录")
                            //     }
                            // }
                            // [W4.5 收缩] 收藏入口隐藏，隐藏 ≠ 删除
                            // IconButton(onClick = { viewModel.toggleFavorite() }) {
                            //     Icon(
                            //         imageVector = if (diary.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            //         contentDescription = if (diary.isFavorite) "取消收藏" else "收藏",
                            //         tint = if (diary.isFavorite) AppAccent else AppTextSecondary,
                            //     )
                            // }
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

                            // 时间（2026-09-27：观点不再记录心情/天气，只留时间）
                            Text(
                                text = diary.createdAt?.take(16) ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextTertiary,
                            )

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

                            // 深度解读 → 两个开关（计入画像 / 计入记忆）
                            // 2026-09-29：isCoupleMode 已从本页摘除（恒为情侣模式）。
                            ViewpointAnalysisSection(
                                uiState = uiState,
                                onAnalyze = { viewModel.analyzeViewpoint() },
                                onLinkProfile = { viewModel.linkProfile() },
                                onUnlinkProfile = { viewModel.unlinkProfile() },
                                onLinkMemory = { viewModel.linkMemory() },
                                onUnlinkMemory = { viewModel.unlinkMemory() },
                                onDismissResult = { viewModel.dismissToggleMessage() },
                            )

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
            title = { Text("删除观点") },
            text = { Text("确定要删除这条观点吗？删除后无法恢复。") },
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

// ==================== 让军师读一读 ====================

/**
 * 深度解读区 + 两个开关（计入个人画像 / 计入军师记忆）。
 *
 * 为什么解读与落地要分两步：解读只产出**建议**（这段说明了什么 / 值不值得写进画像 /
 * 值不值得长期记住），落地是用户自己的决定。让一次模型生成直接改画像或写记忆，
 * 等于把这个产品最核心的两份资产交给一次生成。
 */
@Composable
private fun ViewpointAnalysisSection(
    uiState: DiaryDetailUiState,
    onAnalyze: () -> Unit,
    onLinkProfile: () -> Unit,
    onUnlinkProfile: () -> Unit,
    onLinkMemory: () -> Unit,
    onUnlinkMemory: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val analysis = uiState.analysis

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(28.dp))

        if (analysis == null && !uiState.isAnalyzing) {
            AppCard {
                Text(
                    text = "深度解读",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "军师会读懂这段看法：它说明了什么、能不能成为「它对你的理解」的一部分、" +
                        "值不值得长期记住。要不要落地，由你决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                AppPrimaryButton(text = "让军师深度解读", onClick = onAnalyze)
            }
        }

        if (uiState.isAnalyzing) {
            AppCard {
                Text(
                    text = "军师正在深度解读…",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTextPrimary,
                )
                if (uiState.analysisThinking.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.analysisThinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (uiState.analysisContent.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.analysisContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                    )
                }
            }
        }

        if (analysis != null) {
            AppCard {
                Text(
                    text = "军师读到的",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = analysis.summary.ifBlank { uiState.analysisContent },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextPrimary,
                )

                if (analysis.values.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "你在意的是：" + analysis.values.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }

                if (analysis.basis.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "依据来自你写的：" + analysis.basis.joinToString("；"),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (analysis.dimensions.isNotEmpty()) {
                    Text(
                        text = "建议写进画像的方向",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppAccent,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    analysis.dimensions.forEach { d ->
                        Text(
                            text = "· ${ProfileDimensionLabels.of(d.dimensionKey)}" +
                                if (d.direction == "up") " ↑" else " ↓",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextPrimary,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // 把握偏低时把话说明白：这是**建议**，用户仍然可以坚持计入
                    Text(
                        text = if (analysis.confidence >= ENRICH_HINT_CONFIDENCE) {
                            "军师的把握 ${(analysis.confidence * 100).toInt()}%"
                        } else {
                            "军师的把握只有 ${(analysis.confidence * 100).toInt()}%，偏低——" +
                                "它建议先不写，要不要采纳由你决定"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                    )
                } else {
                    // 模型判"还不值得写"时的正常结果，不是错误——说清为什么，
                    // 否则用户会以为解读失败
                    Text(
                        text = "军师没有找到可以写进画像的维度：这段更像当下的感受，" +
                            "而不是稳定的看法。等你更确定时再写一次，它会重新读。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }

                if (analysis.memoryType.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "值得长期记住 · 军师建议归入「${analysis.memoryType}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }
            }
        }

        // ===== 两个开关：解读只给建议，落地由用户决定 =====
        Spacer(modifier = Modifier.height(20.dp))
        AppCard {
            Text(
                text = "这条观点怎么用",
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
            )
            Spacer(modifier = Modifier.height(12.dp))

            ToggleRow(
                title = "计入个人画像",
                desc = if (uiState.profileLinked) {
                    "已写进画像，并留下一版可回溯的记录。"
                } else {
                    "把军师这次的判断写进画像，随时可以再撤回。"
                },
                checked = uiState.profileLinked,
                busy = uiState.isTogglingProfile,
                onCheckedChange = { want -> if (want) onLinkProfile() else onUnlinkProfile() },
            )

            // 2026-09-29：单身模式删除后本页只对情侣用户可达（观点是情侣抽屉功能），
            // 原 isCoupleMode 门控恒为真，已摘除；「不计入记忆」的边界文案不再需要。
            Spacer(modifier = Modifier.height(16.dp))
            ToggleRow(
                title = "计入军师记忆",
                desc = if (uiState.memoryLinked) {
                    "军师已经记住了，仅你可见，可在「记忆」页删除。"
                } else {
                    "让军师以后在对话里记得这件事（仅你可见）。"
                },
                checked = uiState.memoryLinked,
                busy = uiState.isTogglingMemory,
                onCheckedChange = { want -> if (want) onLinkMemory() else onUnlinkMemory() },
            )
        }

        uiState.analysisError?.let { msg ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = AppErrorRed,
            )
        }

        uiState.toggleMessage?.let { msg ->
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AppSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextPrimary,
                    )
                    TextButton(onClick = onDismissResult) {
                        Text("知道了", color = AppAccent)
                    }
                }
            }
        }
    }
}

/** 一行开关：标题 + 说明 + Switch。忙碌时禁用，避免连点产生重复写入。 */
@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = !busy,
            onCheckedChange = onCheckedChange,
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
