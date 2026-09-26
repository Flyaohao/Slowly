package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextSecondary

/**
 * 整改 §8.2：一条 AI 回复下方的**上下文行动行**。
 *
 * 存在理由：结构化输出此前从不出现在 UI 里（卡片组件在、零调用），用户拿到一段
 * 建议后**没有任何下一步**。「建议表达」这类内容必须能复制/分享，能顺手变成一封
 * 信，能把伴侣拉进来（双视角），能在事后回填结果。
 *
 * 按钮集合由 [actionsFor] 按场景裁决，这里只负责渲染——不要在 UI 里写死按钮：
 * 调解按钮受 [FeatureGate.MEDIATION] 门控，未过 §8.5 验收前不得出现。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiActionRow(
    actions: List<AiAction>,
    onAction: (AiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "接着可以",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextSecondary,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            actions.forEach { action ->
                ActionChip(action = action, onClick = { onAction(action) })
            }
        }
    }
}

/** 行动 chip：这里是「动作」不是「筛选」，用浅强调底色与筛选 chip 区分。 */
@Composable
private fun ActionChip(action: AiAction, onClick: () -> Unit) {
    Surface(
        color = AppAccentLight,
        shape = RoundedCornerShape(AppRadius.pill),
        modifier = Modifier.pressFeedback(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = action.icon(),
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.padding(horizontal = 3.dp))
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
            )
        }
    }
}

private fun AiAction.icon(): ImageVector = when (this) {
    AiAction.COPY_REPLY -> Icons.Outlined.ContentCopy
    AiAction.SHARE_REPLY -> Icons.Outlined.Share
    AiAction.MAKE_LETTER -> Icons.Outlined.MailOutline
    AiAction.INVITE_DUAL -> Icons.Outlined.People
    AiAction.START_MEDIATION -> Icons.Outlined.People
    AiAction.SAVE_REVIEW -> Icons.Outlined.Edit
    AiAction.FEEDBACK -> Icons.Outlined.StarOutline
}

/**
 * 整改 §8.3：反馈行。三种形态由 [feedbackRowModeOf] 决定，**不做成永久四按钮**：
 * - 未表态 → 「有帮助 / 没帮助」（一次点击同时表达评分与采用意图）
 * - 已采纳待结果 → 提醒 + 「填写实际结果」
 * - 已填结果 → 回看内容（可再改）
 */
@Composable
fun AiFeedbackRow(
    feedback: AiDto.FeedbackOut?,
    onRate: (adopted: Boolean, rating: Int) -> Unit,
    onFillOutcome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (feedbackRowModeOf(feedback)) {
            FeedbackRowMode.ASK -> {
                Text(
                    text = "这条建议对你有用吗？",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    WeightedButton("有帮助") { onRate(true, RATING_HELPFUL) }
                    WeightedButton("没帮助") { onRate(false, RATING_NOT_HELPFUL) }
                }
            }

            FeedbackRowMode.OUTCOME_DUE -> {
                Text(
                    text = "你采用了这条建议——后来怎么样了？",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                AppSecondaryButton(text = "填写实际结果", onClick = onFillOutcome)
            }

            FeedbackRowMode.DONE -> {
                Text(
                    text = "已记录结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Surface(
                    color = AppBorderLight,
                    shape = RoundedCornerShape(AppRadius.md),
                ) {
                    Text(
                        text = feedback?.outcome.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(AppSpacing.md),
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                AppSecondaryButton(text = "修改结果", onClick = onFillOutcome)
            }
        }
    }
}

/** 一行两枚等宽按钮（RowScope 里才能拿 weight）。 */
@Composable
private fun RowScope.WeightedButton(text: String, onClick: () -> Unit) {
    AppSecondaryButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.weight(1f),
    )
}

/** 「有帮助」= 满分 5；「没帮助」= 最低 1。评分与采用是同一次点击表达的两层意图。 */
const val RATING_HELPFUL = 5
const val RATING_NOT_HELPFUL = 1
