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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FormatQuote
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
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * P0-8「＋」一级菜单。
 *
 * 样式抄 [ModeDrawerSheet]：AppBackground 底、labelMedium 标题、行间 AppBorderLight 分隔。
 * 只做导航/回调，不持业务状态——数据加载在 AiChatViewModel。
 *
 * 「改写这句话」可见性与改写前逐字等价：expression_rewrite 场景且输入非空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPlusSheet(
    showRewriteItem: Boolean,
    onDismiss: () -> Unit,
    onSelectMode: () -> Unit,
    onPickQuote: (QuotePickerType) -> Unit,
    onRewrite: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

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
                text = "更多操作",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            PlusItem(
                icon = Icons.Outlined.AutoAwesome,
                title = "选择模式",
                onClick = onSelectMode,
            )
            HorizontalDivider(color = AppBorderLight)

            PlusItem(
                icon = Icons.Outlined.FormatQuote,
                title = "引用一条消息",
                onClick = { onPickQuote(QuotePickerType.MESSAGE) },
            )
            HorizontalDivider(color = AppBorderLight)

            PlusItem(
                icon = Icons.Outlined.MailOutline,
                title = "引用一封信",
                onClick = { onPickQuote(QuotePickerType.LETTER) },
            )
            HorizontalDivider(color = AppBorderLight)

            PlusItem(
                icon = Icons.Outlined.CalendarMonth,
                title = "附上一个纪念日",
                onClick = { onPickQuote(QuotePickerType.ANNIVERSARY) },
            )

            if (showRewriteItem) {
                HorizontalDivider(color = AppBorderLight)
                PlusItem(
                    icon = Icons.Outlined.Edit,
                    title = "改写这句话",
                    onClick = onRewrite,
                )
            }
        }
    }
}

@Composable
private fun PlusItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextPrimary,
        )
    }
}
