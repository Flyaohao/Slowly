package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppTextSecondary

/**
 * 复盘历史列表（整改 §8.7：「复盘结果追加保存、可回看历史」）。
 *
 * 独立成一页而不是塞进复盘页的一个模式，是因为它有自己的返回语义：
 * 从军师行动行「记录为关系复盘」进来时，用户要的是先看历史再决定写新的，
 * 返回键应该回到上一页而不是回到输入表单。
 *
 * 列表项点击 → `关系复盘?reviewId=N`（含详情与「以上次为底重写一次」）。
 */
@Composable
fun ReviewHistoryScreen(
    onBack: () -> Unit,
    onOpenReview: (Long) -> Unit,
    onCreateReview: () -> Unit,
    viewModel: ReviewHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onBack,
                title = "历史复盘",
                subtitle = "每一次都留着，不会只存最新那条",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            AppPrimaryButton(
                text = "写一次新的复盘",
                onClick = onCreateReview,
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.error.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = uiState.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppErrorRed,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                uiState.isLoading -> Text(
                    text = "正在加载…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                )

                uiState.history.isEmpty() -> AppEmptyState(
                    icon = Icons.Outlined.Info,
                    title = "还没有复盘记录",
                    subtitle = "写一次吧，下次吵架时你会庆幸自己记过",
                    action = { AppPrimaryButton(text = "写一次新的复盘", onClick = onCreateReview) },
                )

                else -> AppCard(modifier = Modifier.fillMaxWidth()) {
                    uiState.history.forEachIndexed { index, item ->
                        if (index > 0) AppListItemDivider()
                        AppListItem(
                            title = item.summary.ifBlank { "（这条没有留下小结）" },
                            subtitle = historySubtitle(item.eventTime, item.outcome),
                            showChevron = true,
                            onClick = { onOpenReview(item.reviewId) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 列表副标题：发生日期 + 结果状态。
 *
 * 日期只取前 10 位（后端给 ISO8601），不在这里做本地化格式化——
 * 页面上「哪天」比「几点几分」重要，且时区换算不该散落在列表项里。
 */
private fun historySubtitle(eventTime: String?, outcome: String?): String = buildString {
    eventTime?.take(10)?.takeIf { it.isNotBlank() }?.let { append(it) }
    if (isNotEmpty()) append(" · ")
    append(if (outcome.isNullOrBlank()) "还没有结果" else "已记录结果")
}
