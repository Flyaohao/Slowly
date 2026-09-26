package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppTextSecondary
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 整改 §8.3：待反馈结果页。
 *
 * 首页任务卡说「上次建议采用了么？」——此前那张卡点不动（`routeForTaskCard`
 * 对 feedback_outcome 返回 null），用户无处回答。本页把「能发现 → 能发起 →
 * 能完成 → 能返回 → 能回看 → 能反馈结果」闭环补齐：
 *
 * - 列表 = 服务端 `GET /ai/feedback/pending`（近 7 天、已去重、已排除明确未采用）；
 * - 点一条 → 填「后来怎么样」→ 提交后该条消失（服务端 upsert 到同一行）；
 * - 从某条 AI 回复进来时带 `messageId`，直接落到该条的编辑态。
 */
@Composable
fun FeedbackOutcomeScreen(
    onNavigateBack: () -> Unit,
    /** 从任务卡带进来的会话 id（0 = 未指定） */
    sessionId: Long = 0L,
    /** 从某条 AI 回复进来补填的消息 id（0 = 未指定） */
    messageId: Long = 0L,
    viewModel: FeedbackOutcomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId, messageId) {
        viewModel.load(sessionId.takeIf { it > 0 }, messageId.takeIf { it > 0 })
    }

    if (uiState.error.isNotBlank()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "上次建议后来怎么样了",
                subtitle = "填一句结果，军师就知道这条建议到底有没有用",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val editing = uiState.editing
            if (editing != null) {
                EditOutcomeCard(
                    item = editing,
                    text = uiState.outcomeText,
                    isSubmitting = uiState.isSubmitting,
                    onTextChange = viewModel::onOutcomeChange,
                    onSubmit = viewModel::submit,
                    onCancel = viewModel::cancelEditing,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            when {
                uiState.isLoading -> Text(
                    text = "正在看有哪些建议还没回访…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                )

                uiState.items.isEmpty() && editing == null -> AppEmptyState(
                    icon = Icons.Outlined.StarOutline,
                    title = "没有待回访的建议",
                    subtitle = "在军师对话里标记「有帮助」后，这里会提醒你回填结果",
                )

                uiState.items.isNotEmpty() -> AppCard(modifier = Modifier.fillMaxWidth()) {
                    uiState.items.forEachIndexed { index, item ->
                        if (index > 0) AppListItemDivider()
                        AppListItem(
                            title = sceneLabelOf(item.sceneKey).ifBlank { "军师建议" },
                            subtitle = subtitleOf(item),
                            leadingIcon = Icons.Outlined.StarOutline,
                            showChevron = true,
                            onClick = { viewModel.startEditing(item) },
                        )
                    }
                }
            }
        }
    }
}

/** 编辑态：把「后来怎么样」写下来。 */
@Composable
private fun EditOutcomeCard(
    item: PendingFeedback,
    text: String,
    isSubmitting: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = sceneLabelOf(item.sceneKey).ifBlank { "军师建议" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AlignCaption(subtitleOf(item))
        Spacer(modifier = Modifier.height(12.dp))
        TextInputField(
            value = text,
            onValueChange = onTextChange,
            label = "后来怎么样了",
            placeholder = "例如：我按建议说了，她愣了一下，然后我们就聊开了",
            singleLine = false,
            minLines = 4,
            enabled = !isSubmitting,
        )
        Spacer(modifier = Modifier.height(12.dp))
        AppAccentButton(
            text = if (isSubmitting) "提交中…" else "提交结果",
            onClick = onSubmit,
            enabled = !isSubmitting,
        )
        Spacer(modifier = Modifier.height(8.dp))
        AppSecondaryButton(text = "返回列表", onClick = onCancel)
    }
}

@Composable
private fun AlignCaption(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelSmall,
        color = AppTextSecondary,
    )
}

private fun sceneLabelOf(sceneKey: String): String = when (sceneKey) {
    "private_advisor" -> "日常军师"
    "partner_translate" -> "听懂 TA"
    "expression_rewrite" -> "帮我表达"
    "cold_war" -> "冷静一下"
    "letter_understand" -> "信件解读"
    "relationship_review" -> "关系复盘"
    else -> sceneKey
}

/** 副标题：采用状态 + 相对时间（「已采用 · 3 天前」），信息密度够用不啰嗦。 */
private fun subtitleOf(item: PendingFeedback): String {
    val adopted = when (item.adopted) {
        true -> "已采用"
        false -> "未采用"
        null -> "还没表态"
    }
    return "$adopted · ${relativeDay(item.createdAt)}"
}

private fun relativeDay(iso: String?): String {
    if (iso.isNullOrBlank()) return "时间未知"
    return try {
        val then = LocalDateTime.parse(iso.take(19))
        val days = ChronoUnit.DAYS.between(then, LocalDateTime.now())
        when {
            days <= 0 -> "今天"
            days == 1L -> "昨天"
            days < 30 -> "$days 天前"
            else -> then.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    } catch (_: Exception) {
        "时间未知"
    }
}
