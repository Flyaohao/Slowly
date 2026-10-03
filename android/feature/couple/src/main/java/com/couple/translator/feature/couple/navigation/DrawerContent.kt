package com.couple.translator.feature.couple.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.feature.couple.data.repository.AppMode
import com.couple.translator.feature.couple.data.repository.CoupleState
import com.couple.translator.feature.couple.data.repository.CoupleStateManager

@Composable
fun DrawerContent(
    onNavigateToRoute: (String) -> Unit,
    onLogout: () -> Unit = {},
    coupleStateManager: CoupleStateManager? = null,
    /** 待办数（调解邀请 + 未提交双视角 + 解绑确认），> 0 时「待办」条目显示红点角标。 */
    pendingCount: Int = 0,
    /** 当前壳内 tab 路由：对得上的条目高亮（R 系列抽屉项「当前项高亮态」）。 */
    currentRoute: String? = null,
    // 2026-10-03：原 onNavigateToHome（「回到我们的空间」）随抽屉里的
    // 「我们的空间」条目一起移除 —— 空间页是底栏一级页，抽屉不再做它的入口，
    // 这条回调链（CoupleShell → DrawerPage → DrawerContent）随之变成死参数。
) {
    val defaultState = androidx.compose.runtime.remember { CoupleState() }
    val coupleState = coupleStateManager?.state?.collectAsState()?.value ?: defaultState

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(52.dp))

        DrawerIdentitySection(
            onClick = { onNavigateToRoute(Screen.Profile.route) },
            coupleState = coupleState,
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = AppBorderLight)
        Spacer(modifier = Modifier.height(8.dp))

        // 2026-10-03：移除抽屉置顶的「我们的空间」入口。
        // 空间页是**壳内 pager 的一级页**（底部导航第 2 格），已经在主导航层，
        // 抽屉是二级导航，再放一个入口属于重复导向。
        // 关系设置页（CoupleInfoScreen）里的「进入我们的空间」是关系维度的一部分，
        // 不在本次移除范围。
        //
        // 2026-09-28 共同调解室（设计 §一）：抽屉入口（三处入口之一）
        DrawerNavItem(
            icon = Icons.Outlined.Forum,
            label = "共同调解室",
            onClick = { onNavigateToRoute(Screen.MediationRoomList.route) },
            route = Screen.MediationRoomList.route,
            currentRoute = currentRoute,
        )
        DrawerNavItem(
            icon = Icons.Outlined.Person,
            label = "人格画像",
            onClick = { onNavigateToRoute(Screen.Understanding.route) },
            route = Screen.Understanding.route,
            currentRoute = currentRoute,
        )
        DrawerNavItem(
            icon = Icons.Outlined.MailOutline,
            label = "信箱",
            // 2026-09-29 用户裁决：全 App 统一叫「信箱」（原「深度表达」）。
            // 走根导航的「信箱」二级页（Screen.Mailbox，压栈全屏、返回箭头顶栏）。
            onClick = { onNavigateToRoute(Screen.Mailbox.route) },
            route = Screen.Mailbox.route,
            currentRoute = currentRoute,
        )
        DrawerNavItem(
            icon = Icons.Outlined.EventNote,
            label = "纪念事件",
            onClick = { onNavigateToRoute(Screen.RelationshipEvent.route) },
            route = Screen.RelationshipEvent.route,
            currentRoute = currentRoute,
        )
        DrawerNavItem(
            icon = Icons.Outlined.StarOutline,
            label = "愿望",
            onClick = { onNavigateToRoute(Screen.Wishlist.route) },
            route = Screen.Wishlist.route,
            currentRoute = currentRoute,
        )
        // 2026-09-27 用户裁决：抽屉补齐关系内容入口。
        // 观点 = 日记（同一份数据、同一批页面）。用户主动写下的看法比 AI 推断更可信，
        // 所以它既是内容入口，也是画像里「价值取向」那一维的证据来源。
        DrawerNavItem(
            icon = Icons.Outlined.Lightbulb,
            label = "观点",
            onClick = { onNavigateToRoute(Screen.DiaryList.route) },
            route = Screen.DiaryList.route,
            currentRoute = currentRoute,
        )
        // 2026-09-27 关系页改版（用户裁决 ①A）：调解邀请 / 双视角 / 解绑确认
        // 三类低频通知从关系页迁出，收敛为抽屉「待办」条目 + 红点角标；
        // 无待办时条目仍显示、不显示角标（②A，入口稳定）。
        DrawerNavItem(
            icon = Icons.Outlined.FavoriteBorder,
            label = "待办",
            onClick = { onNavigateToRoute(Screen.TodoList.route) },
            route = Screen.TodoList.route,
            currentRoute = currentRoute,
            badgeCount = pendingCount,
        )
        // 2026-09-29 重构：原关系页的「各自的看法」（已完成调解回看）随关系页删除，
        // 入口迁到抽屉——否则这条保留能力就断在第一步（路由测试 REQUIRED_ENTRIES 看守）。
        DrawerNavItem(
            icon = Icons.Outlined.History,
            label = "各自的看法",
            onClick = { onNavigateToRoute(Screen.MediationHistory.route) },
            route = Screen.MediationHistory.route,
            currentRoute = currentRoute,
        )

        // 解绑冷静期提示
        if (coupleState.mode == AppMode.UNBINDING) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "解绑冷静期中",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = AppBorderLight)
        Spacer(modifier = Modifier.height(8.dp))

        // 2026-09-29：单身模式已删除，抽屉不再需要「绑定情侣」入口——
        // 未绑定用户会落在强制绑定页，根本进不到这个抽屉。
        DrawerNavItem(
            icon = Icons.Outlined.SwitchAccount,
            label = "关系管理",
            onClick = { onNavigateToRoute(Screen.CoupleInfo.route) },
            route = Screen.CoupleInfo.route,
            currentRoute = currentRoute,
        )

        DrawerNavItem(
            icon = Icons.Outlined.Book,
            label = "使用指南",
            onClick = { onNavigateToRoute(Screen.Guide.route) },
                route = Screen.Guide.route,
                currentRoute = currentRoute,
        )

        // 整改 §8.8：抽屉底部这里是账号与 App 自身的设置
        // （个人信息、主题、通知、关于、退出登录）。
        // 「军师对话设置」已于 2026-09-28 迁入设置页的军师模块，抽屉不再单列。
        HorizontalDivider(color = AppBorderLight)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "账号与设置",
            style = MaterialTheme.typography.labelMedium,
            color = AppTextTertiary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        DrawerNavItem(
            icon = Icons.Outlined.Settings,
            label = "设置",
            onClick = { onNavigateToRoute("settings") },
            route = "settings",
            currentRoute = currentRoute,
        )

        Spacer(modifier = Modifier.height(34.dp))
    }
}

@Composable
private fun DrawerIdentitySection(
    onClick: () -> Unit,
    coupleState: CoupleState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AppAccentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = coupleState.userNickname ?: coupleState.coupleInfo?.space?.name ?: "我的空间",
                style = MaterialTheme.typography.titleMedium,
                color = AppTextPrimary,
            )
            Text(
                // 2026-09-29：单身模式删除后恒为情侣用户，不再有「单身模式」兜底文案。
                text = "个人资料",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
    }
}

@Composable
private fun DrawerNavItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
    badgeCount: Int = 0,
    /** 条目对应的目标路由；与 currentRoute 相同时高亮为「当前所在」。 */
    route: String? = null,
    currentRoute: String? = null,
) {
    val selected = route != null && route == currentRoute
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // R 系列：抽屉项按压反馈（缩放）替代默认涟漪，必须写在 clip 之前
            .pressFeedback(onClick = onClick)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AppAccentFaint else Color.Transparent)
            .padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlight || selected) AppAccent else AppTextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlight || selected) AppAccent else AppTextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else null,
        )
        if (badgeCount > 0) {
            Spacer(modifier = Modifier.weight(1f))
            // 红点角标：数字超过 99 按 99+ 截断（角标是提醒，不是统计报表）
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(AppErrorRed)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppSurface,
                )
            }
        }
    }
}
