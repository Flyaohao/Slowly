package com.couple.translator.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * 共享聊天输入栏（全局 UI/UX 方案 M-5 / D4 拍板：core 层一份，军师页与调解室共用）。
 *
 * 结构：[胶囊输入框（可带前置徽标）] [圆形发送键]。
 * - 发送键由灰变主色**渐显**（对齐 iMessage 手感），禁用态点击无效；
 * - 前置徽标（如调解室的「@军师」）不占独立 chip 宽度，选中后常驻输入框内；
 * - 键盘 inset 由调用方处理（页面根部 imePadding），本组件不管。
 *
 * @param prefixLabel 前置徽标文案；null 不显示。选中态视觉由调用方传色。
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    placeholder: String = "说点什么…",
    prefixLabel: String? = null,
    prefixSelected: Boolean = false,
    onPrefixClick: (() -> Unit)? = null,
) {
    val canSend = enabled && !isLoading && value.isNotBlank()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(AppSurface)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prefixLabel != null && onPrefixClick != null) {
                PrefixBadge(
                    label = prefixLabel,
                    selected = prefixSelected,
                    onClick = onPrefixClick,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            // BasicTextField 而非 OutlinedTextField：去一层边框/默认 min-height，
            // 多行自动增高、无 M3 输入框的「表单感」——聊天输入栏不是表单
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                textStyle = TextStyle(
                    color = AppTextPrimary,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                ),
                cursorBrush = SolidColor(AppAccent),
                maxLines = 4,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppTextTertiary,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 发送键颜色随「可发送」渐显渐隐（AppMotion.normal 口径）
        val sendBg by animateColorAsState(
            targetValue = when {
                canSend -> AppAccent
                else -> AppBorderLight
            },
            label = "sendButtonColor",
        )
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(sendBg),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = AppSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PrefixBadge(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) AppAccent else AppSurfaceMuted,
        label = "prefixBadgeBg",
    )
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) AppSurface else AppTextTertiary,
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
