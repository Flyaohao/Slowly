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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.theme.AppAccent
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
) {
    val defaultState = androidx.compose.runtime.remember { CoupleState() }
    val coupleState = coupleStateManager?.state?.collectAsState()?.value ?: defaultState
    val isCoupleMode = coupleState.mode != AppMode.SINGLE

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
            isCoupleMode = isCoupleMode,
            coupleState = coupleState,
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = AppBorderLight)
        Spacer(modifier = Modifier.height(8.dp))

        // [W4.3 合并] 我的画像 + 了解自己 + 关系画像 三入口 → 单一页。
        // 2026-09-27 用户裁决：名称由「军师如何理解我们」改为「人格画像」。
        // 旧路由 ProfileResult / QuestionnaireIntro / CoupleProfile 全部保留（隐藏 ≠ 删除）。
        DrawerNavItem(
            icon = Icons.Outlined.Person,
            label = "人格画像",
            onClick = { onNavigateToRoute(Screen.Understanding.route) },
        )
        // 整改 §8.8：正式「记忆与隐私」入口。
        // 记忆此前只能从「人格画像 → 我要纠正军师」绕进去，用户找不到；
        // 而契约把「AI 记忆必须可查看、可删除」定为隐私硬规则，入口就不该是暗门。
        DrawerNavItem(
            icon = Icons.Outlined.Lock,
            label = "记忆与隐私",
            onClick = { onNavigateToRoute(Screen.Memory.route) },
        )
        // [W4.3 合并] 了解自己入口并入上方「人格画像」
        // DrawerNavItem(
        //     icon = Icons.Outlined.Analytics,
        //     label = "了解自己",
        //     onClick = { onNavigateToRoute(Screen.QuestionnaireIntro.route) },
        // )

        // 以下功能仅情侣模式可用
        if (isCoupleMode) {
            // 2026-09-27 关系页改版（用户裁决 ①A）：调解邀请 / 双视角 / 解绑确认
            // 三类低频通知从关系页迁出，收敛为抽屉「待办」条目 + 红点角标；
            // 无待办时条目仍显示、不显示角标（②A，入口稳定）。
            DrawerNavItem(
                icon = Icons.Outlined.FavoriteBorder,
                label = "待办",
                onClick = { onNavigateToRoute(Screen.TodoList.route) },
                badgeCount = pendingCount,
            )
            // [W4.3 合并] 关系画像入口并入「人格画像」
            // DrawerNavItem(
            //     icon = Icons.Outlined.ViewSidebar,
            //     label = "关系画像",
            //     onClick = { onNavigateToRoute(Screen.CoupleProfile.route) },
            // )
            // [W1 隐藏] 纪念馆入口（收敛期冻结 10006，隐藏 ≠ 删除：路由与页面保留）
            // DrawerNavItem(
            //     icon = Icons.Outlined.Archive,
            //     label = "纪念馆",
            //     onClick = { onNavigateToRoute(Screen.Museum.route) },
            // )
            // 整改 §8.8：抽屉一级入口移除——纪念日改由「关系页 → 关系背景」进入，
            // 并作为 AI 可引用的数据（军师聊天里「附上一个纪念日」仍在）。
            // 「愿望与纪念日」这个命名同时作废：愿望清单已冻结 10006，不得再宣传。
            // DrawerNavItem(
            //     icon = Icons.Outlined.StarOutline,
            //     label = "愿望与纪念日",
            //     onClick = { onNavigateToRoute(Screen.AnniversaryList.route) },
            // )
            // [W1 隐藏] 双视角记录入口（机制保留，由军师推荐触发）
            // DrawerNavItem(
            //     icon = Icons.Outlined.FavoriteBorder,
            //     label = "双视角记录",
            //     onClick = { onNavigateToRoute(Screen.DualPerspectiveList.route) },
            // )
            // [W4.4] AI 形象入口保留，但指向页改造为「军师设置」（见 AdvisorSettingsScreen）
            DrawerNavItem(
                icon = Icons.Outlined.AutoAwesome,
                label = "军师设置",
                onClick = { onNavigateToRoute(Screen.AdvisorSettings.route) },
            )
            // 2026-09-27 用户裁决：抽屉补齐关系内容入口。
            // 观点 = 日记（同一份数据、同一批页面）。用户主动写下的看法比 AI 推断更可信，
            // 所以它既是内容入口，也是画像里「价值取向」那一维的证据来源。
            DrawerNavItem(
                icon = Icons.Outlined.Lightbulb,
                label = "观点",
                onClick = { onNavigateToRoute(Screen.DiaryList.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.MailOutline,
                label = "深度表达",
                onClick = { onNavigateToRoute(Screen.LetterList.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.EventNote,
                label = "纪念事件",
                onClick = { onNavigateToRoute(Screen.RelationshipEvent.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.StarOutline,
                label = "愿望",
                onClick = { onNavigateToRoute(Screen.Wishlist.route) },
            )
        }

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

        // 单身模式下突出显示绑定入口
        if (!isCoupleMode) {
            DrawerNavItem(
                icon = Icons.Outlined.SwitchAccount,
                label = "绑定情侣",
                onClick = { onNavigateToRoute(Screen.CoupleBind.route) },
                highlight = true,
            )
        } else {
            DrawerNavItem(
                icon = Icons.Outlined.SwitchAccount,
                label = "关系管理",
                onClick = { onNavigateToRoute(Screen.CoupleInfo.route) },
            )
        }

        DrawerNavItem(
            icon = Icons.Outlined.Book,
            label = "使用指南",
            onClick = { onNavigateToRoute(Screen.Guide.route) },
        )

        // 整改 §8.8：抽屉底部的两个「设置」必须一眼分得清——
        // 「军师设置」在上方功能区（军师怎么说话、怎么称呼、给不给看依据），
        // 这里是账号与 App 自身（个人信息、主题、通知、关于、退出登录）。
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
        )

        Spacer(modifier = Modifier.height(34.dp))
    }
}

@Composable
private fun DrawerIdentitySection(
    onClick: () -> Unit,
    isCoupleMode: Boolean,
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
                text = if (isCoupleMode) "情侣空间" else "单身模式",
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlight) AppAccent else AppTextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlight) AppAccent else AppTextPrimary,
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
