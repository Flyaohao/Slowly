package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * 页面大标题块：**标题 + 一句说明**。
 *
 * 全 App 统一用这一个，别再让某个页面用 28sp 粗体、另一个用 24sp —— 那是最容易看出"不是一套"的地方。
 * 顶栏负责"我是谁"（头像入口），这里负责"这是什么页"。
 */
@Composable
fun AppPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalPadding: Dp = AppSpacing.screenH,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                ),
                color = AppTextPrimary,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/**
 * 区块小标题。用于「收到的信」「最近」这类分组。
 *
 * 统一 12sp 三级灰 —— 之前 10sp 和 14sp 混用，同一页里两个分组看起来层级都不一样。
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .padding(top = AppSpacing.section, bottom = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count != null) "$text · $count" else text,
            style = MaterialTheme.typography.labelMedium,
            color = AppTextTertiary,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(color = AppBorderLight, thickness = 0.5.dp, modifier = modifier)
}

/** 卡片内列表行之间的分隔线：左侧内缩到与文字对齐（14 内边距 + 36 图标 + 12 间距）。 */
@Composable
fun AppListItemDivider() {
    AppDivider(modifier = Modifier.padding(start = 62.dp, end = 14.dp))
}

/**
 * 空态。
 *
 * 之前各页面的空态是「一个灰图标 + 两行字」飘在页面中间，四周全是背景色，看着像没做完。
 * 这里给图标一个淡粉圆角底，把空态做成一个**有边界的小块**，视觉上就"完成"了。
 */
@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = AppAccent,
    containerColor: Color = AppAccentFaint,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.block),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppRadius.xl))
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTextPrimary,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(AppSpacing.lg))
            action()
        }
    }
}
