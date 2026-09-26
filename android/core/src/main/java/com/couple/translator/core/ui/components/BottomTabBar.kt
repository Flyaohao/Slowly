package com.couple.translator.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * 底部导航栏：白胶囊底 + 墨黑滑块指示器。
 *
 * 相比旧版的三点改动：
 * 1. 未选中项也显示文字 —— 只给图标等于让用户猜，尤其"军师"这种非标准语义的 tab。
 * 2. 选中胶囊由一个独立的滑块承担，切换时用 spring 滑过去，而不是瞬间换色。
 * 3. 按下有 0.93 的缩放反馈（旧版 indication = null，按下毫无回应）。
 */
@Composable
fun BottomTabBar(
    currentRoute: String?,
    tabs: List<BottomTab> = BottomTab.entries,
    labelOverrides: Map<BottomTab, String> = emptyMap(),
    onTabSelected: (BottomTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBackground)
            .padding(horizontal = AppSpacing.screenH, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppSurface)
                .border(0.5.dp, AppBorderLight, RoundedCornerShape(AppRadius.pill))
                .padding(5.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val itemWidth = maxWidth / tabs.size.coerceAtLeast(1)
                val activeIndex = tabs.indexOfFirst { it.route == currentRoute }

                // 指示器位移用 spring：比 tween 更像"跟手滑过去"，而不是"表演一次动画"
                val targetOffset by animateDpAsState(
                    targetValue = itemWidth * activeIndex.coerceAtLeast(0),
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "tabIndicatorOffset",
                )
                // 当前路由不属于任何 tab 时（如全屏二级页）不显示滑块
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (activeIndex >= 0) 1f else 0f,
                    animationSpec = tween(durationMillis = 180),
                    label = "tabIndicatorAlpha",
                )

                Box(
                    modifier = Modifier
                        .offset(x = targetOffset)
                        .width(itemWidth)
                        .height(AppSize.tabItem)
                        .alpha(indicatorAlpha)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppTextPrimary),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppSize.tabItem),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        TabItem(
                            tab = tab,
                            label = labelOverrides[tab] ?: tab.label,
                            isActive = tab.route == currentRoute,
                            onClick = { onTabSelected(tab) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: BottomTab,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (tab) {
        BottomTab.Home -> Icons.Outlined.Home
        BottomTab.Mailbox -> Icons.Outlined.MailOutline
        BottomTab.AiChat -> Icons.Outlined.ChatBubbleOutline
        BottomTab.Relation -> Icons.Outlined.FavoriteBorder
        BottomTab.SingleHome -> Icons.Outlined.Person
        BottomTab.Diary -> Icons.Outlined.Book
    }

    val surface = AppSurface
    val contentColor by animateColorAsState(
        targetValue = if (isActive) surface else AppTextTertiary,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tabContentColor",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "tabPressScale",
    )

    Row(
        modifier = modifier
            .height(AppSize.tabItem)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(AppRadius.pill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}
