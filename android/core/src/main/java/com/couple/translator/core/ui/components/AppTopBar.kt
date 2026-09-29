package com.couple.translator.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.AppWarm
import com.couple.translator.core.ui.theme.AppWarmLight

/**
 * 顶栏要展示的身份信息。
 *
 * 抽成一个数据类是为了让所有一级页面**共用同一份来源** ——
 * 首页显示真头像、其他 tab 显示"我"/"TA"两个字，是最容易被看出来的不一致。
 */
@Immutable
data class TopBarIdentity(
    val userAvatarUrl: String? = null,
    val nickname: String? = null,
    val partnerAvatarUrl: String? = null,
    val partnerNickname: String? = null,
)

/**
 * 一级页面（tab 内）顶栏。
 *
 * 只承担一件事：**叠头像 → 打开侧边抽屉**。页面标题不放在这里，
 * 而是由正文里的 [AppPageHeader] 承担 —— 顶栏和正文各写一遍同一个词，是之前首页最明显的重复。
 *
 * @param trailing 右侧动作区（如「历史会话」图标）。没有动作就不占位，标题自然呼吸。
 */
@Composable
fun AppTopBar(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    identity: TopBarIdentity = TopBarIdentity(),
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val background = AppBackground
    // 必须写死高度，不能用 heightIn(min = ...)：
    // 在「有界高度」的父节点里（例如 AI 对话页那种带 weight 的 Column），
    // heightIn 只设下限、不设上限，而这个 Row 的最大高度等于父节点剩余的全部空间；
    // 内部的叠头像 Row 又用了 fillMaxHeight()，于是顶栏会被撑成整屏高，
    // 头像落到屏幕正中，后面的兄弟节点全被挤出可视区。
    // 首页/信箱页因为外层是 verticalScroll（高度无界），fillMaxHeight 退化成空操作，所以看不出问题。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSize.topBar)
            .padding(horizontal = AppSpacing.screenH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = AppSize.touch)
                .clip(RoundedCornerShape(AppRadius.pill))
                .clickable(onClick = onOpenDrawer)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((-9).dp),
        ) {
            AvatarBubble(
                url = identity.userAvatarUrl,
                nickname = identity.nickname,
                size = 30.dp,
                containerColor = AppAccentLight,
                contentColor = AppAccent,
                fallbackText = "我",
                ringColor = background,
                ringWidth = 1.5.dp,
            )
            // 2026-09-29：单身模式已删除，本栏只会出现在情侣模式外壳里，
            // 叠头像恒为「我 + TA」两个（原先按 isCoupleMode 二选一）。
            AvatarBubble(
                url = identity.partnerAvatarUrl,
                nickname = identity.partnerNickname,
                size = 30.dp,
                containerColor = AppWarmLight,
                contentColor = AppWarm,
                fallbackText = "TA",
                ringColor = background,
                ringWidth = 1.5.dp,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        trailing?.invoke(this)
    }
}

/**
 * 二级页面顶栏：返回箭头 + 可选标题 + 右侧动作。
 *
 * 标题放在这里是对的 —— 二级页是「从某处进来的」，标题起的是"我在哪"的作用，
 * 不需要像一级页那样在正文里再放大一次。
 *
 * ⚠️ 会自己吃掉状态栏高度（[applyStatusBarInset]）。原因：这些页面挂在根 NavHost 上，
 * 顶上没有外壳 Scaffold 帮忙让位，而 Material3 的 `Scaffold(topBar = ...)`
 * **不会**给 topBar 槽位补 inset（只有 TopAppBar 自己会）。不补的话顶栏会和状态栏叠在一起。
 * 旧的 TopAppBar 自带 inset，所以换成自绘顶栏后必须显式补上。
 *
 * 如果调用点本身已经处在"外壳 Scaffold 的内容区"里（例如 tab 页内的选择态顶栏），
 * 传 `applyStatusBarInset = false`，否则会被垫高两次。
 *
 * @param applyStatusBarInset 是否补状态栏顶部间距，默认 true
 * @param trailing 右侧动作区
 */
@Composable
fun AppBackTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    applyStatusBarInset: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (applyStatusBarInset) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                }
            )
            .heightIn(min = AppSize.topBar)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(AppSize.touch)) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = AppTextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        if (title != null) {
            Column(modifier = Modifier.padding(start = 0.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        trailing?.invoke(this)
    }
}
