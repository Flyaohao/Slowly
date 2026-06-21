package com.couple.translator.feature.single

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.single.data.model.DiaryDto
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary

@Composable
fun SingleHomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToQuestionnaire: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToBind: () -> Unit = {},
    onNavigateToDiary: () -> Unit = {},
    onNavigateToPractice: () -> Unit = {},
    viewModel: SingleHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Background)) {
            LoadingIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        SingleHomeTopBar(onOpenDrawer = onOpenDrawer)

        Spacer(modifier = Modifier.height(20.dp))

        // 欢迎文案
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "你好，${uiState.nickname ?: "朋友"}",
                style = MaterialTheme.typography.displayMedium,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "记录生活，了解自己",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 快捷入口
        SingleHomeQuickActions(
            onNavigateToQuestionnaire = onNavigateToQuestionnaire,
            onNavigateToProfile = onNavigateToProfile,
            onNavigateToBind = onNavigateToBind,
            onNavigateToPractice = onNavigateToPractice,
        )

        // 最近日记
        if (uiState.recentDiaries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            SingleHomeRecentDiaries(
                diaries = uiState.recentDiaries,
                onNavigateToDiary = onNavigateToDiary,
            )
        }

        // 日记入口
        Spacer(modifier = Modifier.height(24.dp))
        SingleHomeDiaryEntry(onClick = onNavigateToDiary)

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun SingleHomeTopBar(onOpenDrawer: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(AccentLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = "打开侧边栏",
                    tint = Accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Text(
            text = "我的空间",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )

        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun SingleHomeQuickActions(
    onNavigateToQuestionnaire: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToBind: () -> Unit,
    onNavigateToPractice: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "快捷入口",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        QuickActionItem(
            icon = Icons.Outlined.Quiz,
            label = "了解自己",
            description = "填写问卷，生成个人画像",
            onClick = onNavigateToQuestionnaire,
        )

        QuickActionItem(
            icon = Icons.Outlined.Person,
            label = "我的画像",
            description = "查看个人维度分析",
            onClick = onNavigateToProfile,
        )

        QuickActionItem(
            icon = Icons.Outlined.SelfImprovement,
            label = "自我练习",
            description = "情绪管理、正念冥想等练习",
            onClick = onNavigateToPractice,
        )

        QuickActionItem(
            icon = Icons.Outlined.Edit,
            label = "绑定情侣",
            description = "邀请 TA，解锁完整功能",
            onClick = onNavigateToBind,
        )
    }
}

@Composable
private fun QuickActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SingleHomeRecentDiaries(
    diaries: List<DiaryDto.DiaryResponse>,
    onNavigateToDiary: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近日记",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            Text(
                text = "查看全部",
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.clickable(onClick = onNavigateToDiary),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        diaries.forEachIndexed { index, diary ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Book,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = diary.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                    )
                    if (diary.mood != null) {
                        Text(
                            text = diary.mood,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                    }
                }
                Text(
                    text = diary.createdAt?.take(10) ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
            if (index < diaries.lastIndex) {
                HorizontalDivider(color = BorderLight)
            }
        }
    }
}

@Composable
private fun SingleHomeDiaryEntry(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = TextPrimary,
            contentColor = Surface,
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "写日记",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
