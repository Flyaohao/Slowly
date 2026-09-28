package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppShadow
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * 标准容器卡：surface 底 + 0.5dp 描边 + 圆角。
 *
 * 全 App 的"一块内容"都用它 —— 之前各页面自己写 `Card` / `Surface` / `Box+border` 三套，
 * 圆角从 8dp 到 16dp 都有，所以看起来不像一个 App。
 *
 * @param onClick 传了才有按压反馈（0.97 缩放），不传就是纯展示容器
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = AppSurface,
    borderColor: Color = AppBorderLight,
    shape: Shape = RoundedCornerShape(AppRadius.lg),
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // pressFeedback 必须在 modifier 链最前：graphicsLayer 只对链中它之后的节点生效
    val interaction = if (onClick != null) Modifier.pressFeedback(onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .then(interaction)
            // S-C 柔光阴影：8dp + 低透明度黑，只做「能感知的层级」不做重投影
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = AppShadow,
                spotColor = AppShadow,
            )
            .clip(shape)
            .background(containerColor)
            .border(0.5.dp, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 卡片式列表：一个卡片里装多行，行间自动插内缩分隔线。
 *
 * 取代之前"每行一条全宽 HorizontalDivider + 无容器"的裸列表 —— 那种排法在长列表上会散掉，
 * 用户也分不清"这是列表还是正文"。
 */
@Composable
fun <T> AppListCard(
    items: List<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    item: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    AppCard(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        items.forEachIndexed { index, value ->
            key?.let { it(value) }
            item(value)
            if (index != items.lastIndex) AppListItemDivider()
        }
    }
}

/**
 * 统一列表行。
 *
 * 左侧图块用**统一容器色**（静默灰）而不是每个页面自己配粉色 ——
 * 粉色图块满屏铺开会让品牌色失去焦点，它应该只留给真正的强调项。
 *
 * @param leadingEmoji 传了 emoji 就用它，优先于 [leadingIcon]
 * @param leading 完全自定义左侧内容（如头像），优先于上面两者
 * @param trailing 行尾自定义内容（如一个「重新发起」小动作）。
 *   注意它会嵌在整行的点击区里，内部控件需要自己消费点击——
 *   否则点「重新发起」会连带触发整行的 [onClick]。
 */
@Composable
fun AppListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingEmoji: String? = null,
    leading: (@Composable () -> Unit)? = null,
    tileColor: Color = AppSurfaceMuted,
    tileContentColor: Color = AppTextSecondary,
    trailingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.pressFeedback(onClick = onClick) else it }
        .padding(horizontal = 14.dp, vertical = 12.dp)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(AppSpacing.md))
        } else if (leadingIcon != null || leadingEmoji != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AppRadius.md))
                    .background(tileColor),
                contentAlignment = Alignment.Center,
            ) {
                if (leadingEmoji != null) {
                    Text(text = leadingEmoji, style = MaterialTheme.typography.titleMedium)
                } else if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = tileContentColor,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = AppTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            trailing()
        }
        if (trailingText != null) {
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
            )
        }
        if (showChevron) {
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = AppTextTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 左侧带图标的"查看更多"入口。
 *
 * 取代之前居中的纯文字链接（`全部信件 >`）—— 居中小字飘在页面底部，看起来像没排完版；
 * 靠在左边、有容器、右端一个箭头，才像能点的东西。
 */
@Composable
fun AppLinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingEmoji: String? = null,
    trailingLabel: String? = null,
) {
    AppListItem(
        title = label,
        modifier = modifier,
        leadingIcon = leadingIcon,
        leadingEmoji = leadingEmoji,
        tileColor = AppAccentLight,
        tileContentColor = AppAccent,
        trailingText = trailingLabel,
        showChevron = true,
        onClick = onClick,
    )
}

/**
 * 数据卡片：一个数字 + 一行说明。首页状态区、统计区共用。
 */
@Composable
fun AppStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
) {
    AppCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppTextTertiary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = AppTextPrimary,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}
