package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.text.displayTitle
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
 * 信件行右侧带预览按钮：点预览在弹层内直接看内容（列表已带 content，不发请求），
 * 点行本身仍是引用——两个动作互不干扰。
 *
 * 空态与加载失败都必须给人话（不许空白弹层 / 静默失败）。
 */

/** 信件预览目标：信件 + 来源（决定预览头文案与「引用」时的提示词来源）。 */
private data class LetterPreviewTarget(
    val letter: LetterDto.LetterResponse,
    val fromPartner: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotePickerSheet(
    type: QuotePickerType,
    messages: List<AiDto.MessageResponse>,
    letters: List<LetterDto.LetterResponse>,
    sentLetters: List<LetterDto.LetterResponse> = emptyList(),
    anniversaries: List<AnniversaryDto.AnniversaryResponse>,
    loading: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onPickMessage: (AiDto.MessageResponse) -> Unit,
    /** P-C4：第二个参数 fromPartner——TA 寄来的 true / 我寄出的 false，来源决定提示词 */
    onPickLetter: (LetterDto.LetterResponse, Boolean) -> Unit,
    onPickAnniversary: (AnniversaryDto.AnniversaryResponse) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // 非 null = 正在预览某封信（弹层内切换视图，不跳页、不丢列表状态）
    var previewTarget by remember { mutableStateOf<LetterPreviewTarget?>(null) }
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
        QuotePickerType.LETTER -> "还没有可引用的信"
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

            val target = previewTarget
            if (target != null) {
                LetterPreviewView(
                    target = target,
                    onBack = { previewTarget = null },
                    onQuote = {
                        onPickLetter(target.letter, target.fromPartner)
                    },
                )
            } else when {
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
                            // P-C4：两个来源分区展示——作用不同（理解 TA / 改进我的表达），
                            // 选择时就知道在引谁的信，提示词随来源区分
                            if (letters.isEmpty() && sentLetters.isEmpty()) {
                                EmptyRow(emptyIcon, emptyText)
                            } else {
                                LetterSection(
                                    header = "TA 写给你的信",
                                    letters = letters,
                                    onPick = { onPickLetter(it, true) },
                                    onPreview = { previewTarget = LetterPreviewTarget(it, true) },
                                )
                                if (letters.isNotEmpty() && sentLetters.isNotEmpty()) {
                                    HorizontalDivider(color = AppBorderLight)
                                }
                                LetterSection(
                                    header = "你写给 TA 的信",
                                    letters = sentLetters,
                                    onPick = { onPickLetter(it, false) },
                                    onPreview = { previewTarget = LetterPreviewTarget(it, false) },
                                )
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

/** P-C4：信件来源分区块——标题 + 该来源的信列表；空来源整块不渲染。 */
@Composable
private fun LetterSection(
    header: String,
    letters: List<LetterDto.LetterResponse>,
    onPick: (LetterDto.LetterResponse) -> Unit,
    onPreview: (LetterDto.LetterResponse) -> Unit,
) {
    if (letters.isEmpty()) return
    Text(
        text = header,
        style = MaterialTheme.typography.labelMedium,
        color = AppAccent,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
    letters.forEachIndexed { index, letter ->
        val date = (letter.sendTime ?: letter.createdAt)?.take(10).orEmpty()
        val label = buildString {
            append(letter.title.displayTitle())
            if (date.isNotEmpty()) {
                append("  ")
                append(date)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 点行 = 引用（原行为不变）
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = { onPick(letter) })
                    .padding(vertical = 14.dp),
            )
            // 右侧预览按钮 = 只看内容，不引用；独立可点区，不触发行点击
            IconButton(
                onClick = { onPreview(letter) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = "预览信件",
                    tint = AppTextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (index < letters.lastIndex) {
            HorizontalDivider(color = AppBorderLight)
        }
    }
}

/** 信件预览视图：弹层内换页，标题 + 来源 + 日期 + 可滚动正文 + 「引用这封信」。 */
@Composable
private fun LetterPreviewView(
    target: LetterPreviewTarget,
    onBack: () -> Unit,
    onQuote: () -> Unit,
) {
    val letter = target.letter
    val date = (letter.sendTime ?: letter.createdAt)?.take(10).orEmpty()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回信件列表",
                tint = AppTextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "信件预览",
            style = MaterialTheme.typography.labelMedium,
            color = AppTextSecondary,
        )
    }

    Text(
        text = letter.title.displayTitle(),
        style = MaterialTheme.typography.titleMedium,
        color = AppTextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = buildString {
            if (target.fromPartner) append("TA 写给你的信") else append("你写给 TA 的信")
            if (date.isNotEmpty()) {
                append("  ")
                append(date)
            }
        },
        style = MaterialTheme.typography.labelMedium,
        color = AppTextTertiary,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    )

    HorizontalDivider(color = AppBorderLight)
    // 长信限高滚动，避免弹层被正文撑满屏
    Text(
        text = letter.content?.takeIf { it.isNotBlank() } ?: "（这封信还没有内容）",
        style = MaterialTheme.typography.bodyMedium,
        color = AppTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    )
    AppAccentButton(
        text = "引用这封信",
        onClick = onQuote,
        modifier = Modifier.fillMaxWidth(),
    )
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
