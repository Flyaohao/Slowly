package com.couple.translator.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppOnAccent
import com.couple.translator.core.ui.theme.AppPrimaryGradient
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppShadow
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary

/** 主按钮：墨黑填充 + 全宽 + 胶囊。全 App 唯一的主操作样式。 */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val container = AppTextPrimary
    val content = AppSurface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "primaryButtonScale",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(AppSize.button)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (enabled) {
                    Modifier.shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(AppRadius.pill),
                        ambientColor = AppShadow,
                        spotColor = AppShadow,
                    )
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(AppRadius.pill),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.30f),
            disabledContentColor = content.copy(alpha = 0.75f),
        ),
        contentPadding = PaddingValues(horizontal = AppSpacing.screenH),
    ) {
        ButtonLabel(text = text, icon = icon)
    }
}

/** 品牌色主按钮：S-E 拍板改品牌渐变填充 + 柔光阴影 + 按压缩放。 */
@Composable
fun AppAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(AppRadius.pill)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "accentButtonScale",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(AppSize.button)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (enabled) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = shape,
                        ambientColor = AppShadow,
                        spotColor = AppShadow,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                // M3 Button 的 containerColor 只吃 Color，渐变垫在透明容器底下；
                // disabled 态回退半透明品牌色，不用渐变
                if (enabled) {
                    Modifier.background(AppPrimaryGradient, shape)
                } else {
                    Modifier
                },
            ),
        shape = shape,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) Color.Transparent else AppAccent.copy(alpha = 0.35f),
            contentColor = AppOnAccent,
            disabledContainerColor = AppAccent.copy(alpha = 0.35f),
            disabledContentColor = AppOnAccent.copy(alpha = 0.75f),
        ),
        contentPadding = PaddingValues(horizontal = AppSpacing.screenH),
    ) {
        ButtonLabel(text = text, icon = icon)
    }
}

/** 次级按钮：白底 + 描边。和主按钮成对出现时用它，避免两个黑按钮打架。 */
@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val border = AppBorderLight
    val container = AppSurface
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(AppSize.button),
        shape = RoundedCornerShape(AppRadius.pill),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = AppTextPrimary,
            disabledContainerColor = container,
            disabledContentColor = AppTextSecondary.copy(alpha = 0.5f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        contentPadding = PaddingValues(horizontal = AppSpacing.screenH),
    ) {
        ButtonLabel(text = text, icon = icon)
    }
}

@Composable
private fun RowScope.ButtonLabel(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(AppSpacing.sm))
    }
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

/** 顶栏右侧的圆形图标动作。触摸热区 40dp，视觉 34dp。 */
@Composable
fun AppTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AppTextPrimary,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 品牌色的纯文字动作（如区块标题右侧的「查看全部」）。 */
@Composable
fun AppLinkText(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AppAccent,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .pressFeedback(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 过滤 / 快捷语胶囊。
 *
 * 选中态用淡粉底 + 品牌字色，未选中态用描边 —— 大量胶囊并排时，只有选中那个是"实"的，扫视成本低。
 */
@Composable
fun AppFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // S-E：选中态用 accentContainer 实底替代 10% 透明度——大量胶囊并排时「实」的那个更醒目
    val container = if (selected) AppAccentLight else AppBackground
    val content = if (selected) AppAccent else AppTextSecondary
    val border = if (selected) AppAccent.copy(alpha = 0.30f) else AppBorderLight

    Box(
        modifier = modifier
            .pressFeedback(pressedScale = 0.95f, onClick = onClick)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(container)
            .border(0.5.dp, border, RoundedCornerShape(AppRadius.pill))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = content,
        )
    }
}
