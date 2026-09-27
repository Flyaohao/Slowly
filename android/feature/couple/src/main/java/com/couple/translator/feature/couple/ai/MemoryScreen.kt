package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.model.MemoryDto
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppFilterChip
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonListCard
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "记忆与隐私",
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 整改 §8.8：入口改名为「记忆与隐私」后，页面自己要把隐私规则说清楚
                // ——「军师记住了什么」是用户最该一眼看到的事，不能只靠空态文案暗示。
                Text(
                    text = "这里列出军师记住的关于你们的内容。标记为「仅自己」的，伴侣看不到；" +
                        "每条都可以改可见范围或直接删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                    modifier = Modifier.padding(
                        start = AppSpacing.screenH,
                        end = AppSpacing.screenH,
                        top = AppSpacing.md,
                    ),
                )
                // P-C3 §4.3：来源 / 时间 / 重要度 筛选（改任一项即重拉，服务端过滤）
                MemoryFilterSection(
                    sourceFilter = uiState.sourceFilter,
                    daysFilter = uiState.daysFilter,
                    importanceFilter = uiState.importanceFilter,
                    onSourceChange = viewModel::setSourceFilter,
                    onDaysChange = viewModel::setDaysFilter,
                    onImportanceChange = viewModel::setImportanceFilter,
                )

                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    when {
                        uiState.isLoading -> {
                            SkeletonListCard(rows = 4)
                        }
                        uiState.memories.isEmpty() -> {
                            val filtered = uiState.sourceFilter != null ||
                                uiState.daysFilter != null ||
                                uiState.importanceFilter != null
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                AppEmptyState(
                                    icon = Icons.Outlined.Psychology,
                                    title = if (filtered) "没有符合条件的记忆" else "暂无 AI 记忆",
                                    subtitle = if (filtered) {
                                        "试试放宽筛选条件。"
                                    } else {
                                        "AI 会记住你们的重要信息与偏好。"
                                    },
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
                                items(uiState.memories, key = { it.id }) { memory ->
                                    MemoryItemCard(
                                        memory = memory,
                                        onDelete = { viewModel.deleteMemory(memory.id) },
                                        onVisibilityChange = { viewModel.updateVisibility(memory.id, it) },
                                        onToggleStar = { viewModel.toggleImportance(memory.id) },
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
}

// ------------------------------------------------------------------ #
// P-C3 §4.3：记忆筛选（来源 / 时间 / 重要度）
// ------------------------------------------------------------------ #

/** 与后端 `ai_memory.source` 取值一一对应；null = 全部 */
private val SOURCE_FILTERS = listOf(
    null to "全部",
    "letter" to "信件",
    "diary" to "观点",
    "dual" to "双视角",
    "anniversary" to "纪念日",
    "questionnaire" to "量表",
    "chat_summary" to "会话摘要",
)

private val DAYS_FILTERS = listOf(
    null to "全部",
    7 to "近 7 天",
    30 to "近 30 天",
)

private val IMPORTANCE_FILTERS = listOf(
    null to "全部",
    2 to "标星",
)

@Composable
private fun MemoryFilterSection(
    sourceFilter: String?,
    daysFilter: Int?,
    importanceFilter: Int?,
    onSourceChange: (String?) -> Unit,
    onDaysChange: (Int?) -> Unit,
    onImportanceChange: (Int?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterRow(label = "来源") {
            SOURCE_FILTERS.forEach { (value, text) ->
                AppFilterChip(
                    text = text,
                    selected = sourceFilter == value,
                    onClick = { onSourceChange(value) },
                )
            }
        }
        FilterRow(label = "时间") {
            DAYS_FILTERS.forEach { (value, text) ->
                AppFilterChip(
                    text = text,
                    selected = daysFilter == value,
                    onClick = { onDaysChange(value) },
                )
            }
        }
        FilterRow(label = "重要") {
            IMPORTANCE_FILTERS.forEach { (value, text) ->
                AppFilterChip(
                    text = text,
                    selected = importanceFilter == value,
                    onClick = { onImportanceChange(value) },
                )
            }
        }
    }
}

/** 左侧固定标签 + 右侧横向滚动的 chip 行（12 个 chip 一排放不下） */
@Composable
private fun FilterRow(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppTextTertiary,
            modifier = Modifier.width(34.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun MemoryItemCard(
    memory: MemoryDto.MemoryItem,
    onDelete: () -> Unit,
    onVisibilityChange: (String) -> Unit,
    onToggleStar: () -> Unit,
) {
    var showVisibilityMenu by remember { mutableStateOf(false) }
    val isStarred = memory.importance == 2

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppFilterChip(
                    text = if (memory.visibility == "private") "仅自己" else "情侣共享",
                    selected = false,
                    onClick = { showVisibilityMenu = true },
                )
                DropdownMenu(
                    expanded = showVisibilityMenu,
                    onDismissRequest = { showVisibilityMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("仅自己") },
                        onClick = {
                            onVisibilityChange("private")
                            showVisibilityMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("情侣共享") },
                        onClick = {
                            onVisibilityChange("couple")
                            showVisibilityMenu = false
                        },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // P-C3 §4.2：★/☆ 标星（importance 2/0，调新端点）
                    IconButton(onClick = onToggleStar) {
                        Text(
                            text = if (isStarred) "★" else "☆",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isStarred) AppAccent else AppTextTertiary,
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = AppErrorRed,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = memory.memoryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = memory.memoryType,
                style = MaterialTheme.typography.labelSmall,
                color = AppAccent,
            )
            // P-C3 §4.3：「来源 · 发生时间」（occurred_at 为空回退 created_at）
            val meta = listOfNotNull(
                sourceLabel(memory.source),
                formatMemoryTime(memory),
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
        }
    }
}

/** 后端 source 原始值 → 展示名；null/空不展示，未知值原样兜底 */
private fun sourceLabel(source: String?): String? = when (source) {
    null, "" -> null
    "letter" -> "信件"
    "diary" -> "观点"
    "dual" -> "双视角"
    "anniversary" -> "纪念日"
    "questionnaire" -> "量表"
    "chat_summary" -> "会话摘要"
    "museum" -> "纪念册"
    else -> source
}

/**
 * 发生时间展示：`occurred_at` 优先、空回退 `created_at`（与后端排序口径一致）。
 * java.time 原生解析 ISO 字符串（minSdk=26 可用，不引新依赖）；
 * 解析失败回退原文前 16 位，不因格式意外把时间行整段丢掉。
 */
private fun formatMemoryTime(memory: MemoryDto.MemoryItem): String? {
    val raw = memory.occurredAt ?: memory.createdAt ?: return null
    return try {
        java.time.LocalDateTime.parse(raw)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (e: Exception) {
        raw.take(16).replace('T', ' ')
    }
}
