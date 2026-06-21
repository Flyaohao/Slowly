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
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
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
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary
import com.couple.translator.feature.couple.data.repository.AppMode
import com.couple.translator.feature.couple.data.repository.CoupleState
import com.couple.translator.feature.couple.data.repository.CoupleStateManager

@Composable
fun DrawerContent(
    onNavigateToRoute: (String) -> Unit,
    onLogout: () -> Unit = {},
    coupleStateManager: CoupleStateManager? = null,
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
        HorizontalDivider(color = BorderLight)
        Spacer(modifier = Modifier.height(8.dp))

        // 我的画像 - 单身模式也可用
        DrawerNavItem(
            icon = Icons.Outlined.Person,
            label = "我的画像",
            onClick = { onNavigateToRoute(Screen.ProfileResult.route) },
        )

        // 了解自己（问卷） - 单身模式也可用
        DrawerNavItem(
            icon = Icons.Outlined.Analytics,
            label = "了解自己",
            onClick = { onNavigateToRoute(Screen.QuestionnaireIntro.route) },
        )

        // 以下功能仅情侣模式可用
        if (isCoupleMode) {
            DrawerNavItem(
                icon = Icons.Outlined.ViewSidebar,
                label = "关系画像",
                onClick = { onNavigateToRoute(Screen.CoupleProfile.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.Archive,
                label = "纪念馆",
                onClick = { onNavigateToRoute(Screen.Museum.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.StarOutline,
                label = "愿望与纪念日",
                onClick = { onNavigateToRoute(Screen.AnniversaryList.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.FavoriteBorder,
                label = "双视角记录",
                onClick = { onNavigateToRoute(Screen.DualPerspectiveList.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.People,
                label = "关系练习",
                onClick = { onNavigateToRoute(Screen.PracticeList.route) },
            )
            DrawerNavItem(
                icon = Icons.Outlined.AutoAwesome,
                label = "AI 形象",
                onClick = { onNavigateToRoute(Screen.AvatarCustomize.route) },
            )
        }

        // 解绑冷静期提示
        if (coupleState.mode == AppMode.UNBINDING) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "解绑冷静期中",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = BorderLight)
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
                label = "情侣绑定",
                onClick = { onNavigateToRoute(Screen.CoupleBind.route) },
            )
        }

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
                .background(AccentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = coupleState.coupleInfo?.space?.name ?: "我的空间",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Text(
                text = if (isCoupleMode) "情侣空间" else "点击绑定情侣",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
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
            tint = if (highlight) Accent else TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlight) Accent else TextPrimary,
        )
    }
}
