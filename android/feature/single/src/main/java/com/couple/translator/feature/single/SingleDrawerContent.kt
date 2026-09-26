package com.couple.translator.feature.single

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.navigation.Screen

@Composable
fun SingleDrawerContent(
    nickname: String? = null,
    onNavigateToRoute: (String) -> Unit,
    onNavigateToBind: () -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBackground)
            .padding(horizontal = 20.dp, vertical = 40.dp),
    ) {
        // 身份区域 — 可点击跳转个人信息
        DrawerIdentitySection(
            nickname = nickname,
            onClick = onNavigateToProfile,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 菜单项
        // [W4.3 合并] 我的画像 + 了解自己 → 单一「军师如何理解我们」页（隐藏 ≠ 删除，
        // ProfileResult / QuestionnaireIntro 路由保留，页内有问卷与结果深链）
        DrawerNavItem(
            icon = Icons.Outlined.Person,
            label = "军师如何理解我们",
            onClick = { onNavigateToRoute(Screen.Understanding.route) },
        )

        // [W4.3 合并] 了解自己入口并入上方「军师如何理解我们」
        // DrawerNavItem(
        //     icon = Icons.Outlined.Quiz,
        //     label = "了解自己",
        //     onClick = { onNavigateToRoute(Screen.QuestionnaireIntro.route) },
        // )

        // [W1 隐藏] 自我练习入口（收敛期冻结 10006，隐藏 ≠ 删除：路由与页面保留）
        // DrawerNavItem(
        //     icon = Icons.Outlined.SelfImprovement,
        //     label = "自我练习",
        //     onClick = { onNavigateToRoute(Screen.SelfPracticeList.route) },
        // )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = AppBorderLight)
        Spacer(modifier = Modifier.height(16.dp))

        // 绑定情侣（高亮）
        DrawerNavItem(
            icon = Icons.Outlined.Link,
            label = "绑定情侣",
            onClick = onNavigateToBind,
            highlight = true,
        )

        Spacer(modifier = Modifier.weight(1f))

        HorizontalDivider(color = AppBorderLight)
        Spacer(modifier = Modifier.height(16.dp))

        DrawerNavItem(
            icon = Icons.Outlined.Book,
            label = "使用指南",
            onClick = { onNavigateToRoute(Screen.Guide.route) },
        )

        DrawerNavItem(
            icon = Icons.Outlined.Settings,
            label = "设置",
            onClick = { onNavigateToRoute("settings") },
        )
    }
}

@Composable
private fun DrawerIdentitySection(
    nickname: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AppAccentLight)
                .padding(12.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = nickname ?: "朋友",
                style = MaterialTheme.typography.titleMedium,
                color = AppTextPrimary,
            )
            Text(
                text = "单身模式",
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
    }
}
