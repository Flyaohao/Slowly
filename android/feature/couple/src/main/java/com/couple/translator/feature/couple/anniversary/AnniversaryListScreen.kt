package com.couple.translator.feature.couple.anniversary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.HistoryEdu
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonPlainListPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.feature.couple.data.model.AnniversaryDto

@Composable
fun AnniversaryListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAdd: () -> Unit,
    onNavigateToMemoryCard: (targetType: String, targetId: Long, itemTitle: String) -> Unit = { _, _, _ -> },
    viewModel: AnniversaryListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    if (uiState.isLoading) {
        SkeletonPlainListPage(cardRows = 4)
        return
    }

    Scaffold(
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "纪念日")
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = AppAccent,
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新增纪念日", tint = AppSurface)
            }
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            if (uiState.anniversaries.isEmpty()) {
                AppEmptyState(
                    icon = Icons.Outlined.Event,
                    title = "还没有纪念日",
                    subtitle = "记录你们重要的日子",
                    modifier = Modifier.padding(top = AppSpacing.section),
                )
                return@PullToRefreshLayout
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.screenH),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                items(uiState.anniversaries) { anniversary ->
                    AnniversaryListItem(
                        anniversary = anniversary,
                        onMemoryCard = {
                            onNavigateToMemoryCard("anniversary", anniversary.id, anniversary.title)
                        },
                        onDelete = { viewModel.deleteAnniversary(anniversary.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun AnniversaryListItem(
    anniversary: AnniversaryDto.AnniversaryResponse,
    onMemoryCard: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anniversary.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 整改 §8.8：禁止出现「还有 55 天 · 2025-11-20」这种年份冲突。
                // 一律先说**语义**（每年 X 月 X 日 / 一次性），再说下次是哪天，
                // 天数一律用服务端算好的那个（客户端不再自己推日期）。
                Text(
                    text = anniversaryDateText(anniversary),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppAccent,
                )
                val days = anniversary.daysUntil
                if (anniversary.repeatAnnually && days != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            days <= 0 -> "就是今天"
                            else -> "还有 $days 天"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                    )
                }
                if (anniversary.description != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = anniversary.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                        maxLines = 1,
                    )
                }
            }
            // [W1 隐藏] AI 回忆卡图标（memory-card 端点冻结 10006；回调参数保留）
            // IconButton(onClick = onMemoryCard) {
            //     Icon(
            //         Icons.Outlined.HistoryEdu,
            //         contentDescription = "回忆卡片",
            //         tint = AppAccent,
            //     )
            // }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = AppErrorRed)
            }
        }
    }
}

/**
 * 纪念日的日期说明（整改 §8.8 的文案要求，抽出来是为了能被单测钉住）。
 *
 * 契约给的正例是「每年 11 月 20 日 / 下次 2026-11-20」——把**语义**和**下一次**
 * 分开说，用户不会看到「还有 55 天」配着一个明显属于过去的年份。
 *
 * - 每年重复：`每年 11 月 20 日 · 下次 2026-11-20`
 * - 一次性且还没到：`2027-05-01（一次性）`
 * - 一次性且已过去：`2025-11-20（一次性，已过去）`——**不编造**「下一次」
 */
internal fun anniversaryDateText(item: AnniversaryDto.AnniversaryResponse): String {
    val raw = item.anniversaryDate
    if (!item.repeatAnnually) {
        val suffix = if (item.nextOccurrenceDate == null) "一次性，已过去" else "一次性"
        return "$raw（$suffix）"
    }
    val monthDay = raw.split("-").takeIf { it.size == 3 }
        ?.let { "${it[1].trimStart('0')} 月 ${it[2].trimStart('0')} 日" }
        ?: raw
    val next = item.nextOccurrenceDate
    return if (next.isNullOrBlank()) "每年 $monthDay" else "每年 $monthDay · 下次 $next"
}
