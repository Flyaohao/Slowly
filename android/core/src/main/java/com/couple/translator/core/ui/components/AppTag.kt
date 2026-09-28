package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSuccessGreen
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppWarm
import com.couple.translator.core.ui.theme.AppWarmLight

/** 标签色调。容器色/文字色成对出现，页面不允许自己配色。 */
enum class AppTagTone { Accent, Neutral, Warm, Success }

/**
 * tone → (容器色, 文字色)。取色是 @Composable getter，不能放进枚举构造，
 * 所以拆成独立解析函数；页面仍不允许绕过它自己配色。
 */
@Composable
private fun AppTagTone.resolve(): Pair<Color, Color> = when (this) {
    AppTagTone.Accent -> AppAccentFaint to AppAccent
    AppTagTone.Neutral -> AppSurfaceMuted to AppTextSecondary
    AppTagTone.Warm -> AppWarmLight to AppWarm
    AppTagTone.Success -> AppSurfaceMuted to AppSuccessGreen
}

/**
 * 纯展示小标签（不可选中）。
 *
 * 取代之前「labelSmall 灰字 + · 拼接」表达类型/状态的方式 —— 那种排法里
 * 类型、说明、元信息全是同一种字，扫一眼分不出主次。
 *
 * 边界：可点选的筛选项继续用 AppFilterChip，不要用 Tag 冒充。
 */
@Composable
fun AppTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: AppTagTone = AppTagTone.Accent,
    leadingIcon: ImageVector? = null,
) {
    val (container, content) = tone.resolve()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.size(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}

/**
 * 页面级说明文字容器。
 *
 * 取代各页「一段 bodySmall 灰字裸飘在页头/卡内」的写法 —— 说明文字没有容器，
 * 看起来就像忘了排版的占位。用 Banner 给它一个安静的底，页面立刻「完成」了。
 * 不承载可点击动作；需要动作时用 AppLinkRow。
 */
@Composable
fun AppInfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: AppTagTone = AppTagTone.Neutral,
    icon: ImageVector? = null,
    title: String? = null,
) {
    val (container, content) = tone.resolve()
    val textColor = if (tone == AppTagTone.Neutral) AppTextSecondary else content
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp).padding(top = 1.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        androidx.compose.foundation.layout.Column {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = content,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
        }
    }
}
