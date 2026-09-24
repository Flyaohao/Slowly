package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.model.LetterDto

/**
 * P0-8 引用来源二级选择弹层。
 *
 * 三种来源共用一个壳，按 [type] 渲染：
 *  - 消息：调用方传入最近 20 条倒序列表（本地 state，无网络）
 *  - 信件 / 纪念日：调用方已通过 ViewModel 加载到 UiState
 *
 * 空态与加载失败都必须给人话（不许空白弹层 / 静默失败）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotePickerSheet(
    type: QuotePickerType,
    messages: List<AiDto.MessageResponse>,
    letters: List<LetterDto.LetterResponse>,
    anniversaries: List<AnniversaryDto.AnniversaryResponse>,
    loading: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onPickMessage: (AiDto.MessageResponse) -> Unit,
    onPickLetter: (LetterDto.LetterResponse) -> Unit,
    onPickAnniversary: (AnniversaryDto.AnniversaryResponse) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val title = when (type) {
        QuotePickerType.MESSAGE -> "引用一条消息"
        QuotePickerType.LETTER -> "引用一封信"
        QuotePickerType.ANNIVERSARY -> "附上一个纪念日"
    }
    val emptyIcon: ImageVector = when (type) {
        QuotePickerType.MESSAGE -> Icons.Outlined.Message
        QuotePickerType.LETTER -> Icons.Outlined.MailOutline
        QuotePickerType.ANNIVERSARY -> Icons.Outlined.CalendarMonth
    }
    val emptyText = when (type) {
        QuotePickerType.MESSAGE -> "还没有聊过"
        QuotePickerType.LETTER -> "还没有收到信"
        QuotePickerType.ANNIVERSARY -> "还没有纪念日"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            when {
                // 加载失败：一行错误，绝不静默（项目红线）
                error.isNotBlank() -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppErrorRed,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "加载中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextTertiary,
                        )
                    }
                }

                else -> {
                    when (type) {
                        QuotePickerType.MESSAGE -> {
                            // 最近 20 条，倒序（最新在上）
                            val recent = messages.takeLast(20).asReversed()
                            if (recent.isEmpty()) {
                                EmptyRow(emptyIcon, emptyText)
                            } else {
                                recent.forEachIndexed { index, msg ->
                                    PickerRow(
                                        title = msg.content.take(40),
                                        onClick = { onPickMessage(msg) },
                                    )
                                    if (index < recent.lastIndex) {
                                        HorizontalDivider(color = AppBorderLight)
                                    }
                                }
                            }
                        }

                        QuotePickerType.LETTER -> {
                            if (letters.isEmpty()) {
                                EmptyRow(emptyIcon, emptyText)
                            } else {
                                letters.forEachIndexed { index, letter ->
                                    val date = (letter.sendTime ?: letter.createdAt)
                                        ?.take(10).orEmpty()
                                    val label = buildString {
                                        append(letter.title ?: "无标题")
                                        if (date.isNotEmpty()) {
                                            append("  ")
                                            append(date)
                                        }
                                    }
                                    PickerRow(title = label, onClick = { onPickLetter(letter) })
                                    if (index < letters.lastIndex) {
                                        HorizontalDivider(color = AppBorderLight)
                                    }
                                }
                            }
                        }

                        QuotePickerType.ANNIVERSARY -> {
                            if (anniversaries.isEmpty()) {
                                EmptyRow(emptyIcon, emptyText)
                            } else {
                                anniversaries.forEachIndexed { index, ann ->
                                    val label = "${ann.title}  ${ann.anniversaryDate}"
                                    PickerRow(title = label, onClick = { onPickAnniversary(ann) })
                                    if (index < anniversaries.lastIndex) {
                                        HorizontalDivider(color = AppBorderLight)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTextTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextTertiary,
        )
    }
}

@Composable
private fun PickerRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
