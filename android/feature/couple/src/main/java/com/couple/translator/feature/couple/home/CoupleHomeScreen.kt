package com.couple.translator.feature.couple.home

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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.feature.couple.presence.MeetCountdown
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary

@Composable
fun NewHomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToComposeLetter: () -> Unit,
    onNavigateToMailbox: () -> Unit,
    onNavigateToAiChat: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
    onNavigateToBind: () -> Unit = {},
    isCoupleMode: Boolean = true,
    viewModel: NewHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Background)) {
            LoadingIndicator()
        }
        return
    }

    PullToRefreshLayout(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState()),
    ) {
        HomeTopBar(
            onOpenDrawer = onOpenDrawer,
            isCoupleMode = isCoupleMode,
        )

        if (isCoupleMode) {
            // 情侣模式：显示完整内容
            HomeIdentitySection(
                spaceName = uiState.spaceName,
                daysCount = uiState.daysCount,
                heroText = uiState.heroText,
            )

            Spacer(modifier = Modifier.height(28.dp))

            HomePrimaryButton(
                text = uiState.primaryButtonText,
                onClick = {
                    when (uiState.primaryAction) {
                        HomePrimaryAction.ReadLetter,
                        HomePrimaryAction.ContinueDraft,
                        -> onNavigateToMailbox()
                        HomePrimaryAction.InvitePartner -> {}
                        HomePrimaryAction.ContinueMediation -> {}
                        HomePrimaryAction.ViewAnniversary -> {}
                        else -> onNavigateToComposeLetter()
                    }
                },
            )

            // 聚合信息卡片
            if (uiState.homeData != null) {
                Spacer(modifier = Modifier.height(24.dp))
                HomeQuickInfoCards(homeData = uiState.homeData!!)
            }

            if (uiState.recentItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                HomeRecentSection(
                    items = uiState.recentItems,
                    onItemClick = { item ->
                        when (item.type) {
                            "letter", "draft" -> onNavigateToLetterDetail(item.id)
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            HomeAiHint(onClick = onNavigateToAiChat)
        } else {
            // 单身模式：显示引导绑定
            HomeSingleModeSection(
                onNavigateToComposeLetter = onNavigateToComposeLetter,
                onNavigateToBind = onNavigateToBind,
            )
        }

        // 情侣模式显示倒计时
        if (isCoupleMode) {
            Spacer(modifier = Modifier.height(24.dp))
            MeetCountdown(
                targetDate = null,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
    }
}

@Composable
private fun HomeTopBar(
    onOpenDrawer: () -> Unit,
    isCoupleMode: Boolean = true,
    spaceName: String? = null,
) {
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
            text = spaceName ?: if (isCoupleMode) "我们的空间" else "我的空间",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )

        if (isCoupleMode) {
            IconButton(onClick = { }) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = "通知",
                    tint = TextSecondary,
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun HomeIdentitySection(
    spaceName: String,
    daysCount: Int,
    heroText: String,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Text(
            text = spaceName,
            style = MaterialTheme.typography.displayMedium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (daysCount > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "第 ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
                Text(
                    text = daysCount.toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                )
                Text(
                    text = " 天",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = heroText,
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
        )
    }
}

@Composable
private fun HomePrimaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .height(48.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = TextPrimary,
            contentColor = Surface,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun HomeRecentSection(
    items: List<RecentItem>,
    onItemClick: (RecentItem) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "最近",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick(item) }
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Background),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = item.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
                Text(
                    text = item.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
            if (index < items.lastIndex) {
                HorizontalDivider(color = BorderLight)
            }
        }
    }
}

@Composable
private fun HomeAiHint(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp)
            .padding(top = 13.dp),
    ) {
        HorizontalDivider(color = BorderLight)
        Spacer(modifier = Modifier.height(13.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "需要整理表达时，可以找",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Text(
                text = "翻译官",
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun HomeSingleModeSection(
    onNavigateToComposeLetter: () -> Unit,
    onNavigateToBind: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "这个空间还差一个人",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "绑定情侣后解锁完整功能",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNavigateToBind,
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "邀请 TA",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "或者",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNavigateToComposeLetter,
            colors = ButtonDefaults.buttonColors(
                containerColor = Surface,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "写给自己",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun HomeQuickInfoCards(homeData: HomeDto.HomeResponse) {
    val cards = mutableListOf<@Composable () -> Unit>()

    if (homeData.pendingLetterCount > 0) {
        cards.add {
            QuickInfoCard(
                icon = Icons.Outlined.MailOutline,
                label = "待回应",
                value = "${homeData.pendingLetterCount} 封信",
            )
        }
    }

    if (homeData.activeMediation != null) {
        cards.add {
            QuickInfoCard(
                icon = Icons.Outlined.People,
                label = "调解中",
                value = "进行中",
            )
        }
    }

    if (homeData.futureLetter != null) {
        cards.add {
            QuickInfoCard(
                icon = Icons.Outlined.Schedule,
                label = "未来信",
                value = homeData.futureLetter!!.unlockTime?.take(10) ?: "待解锁",
            )
        }
    }

    if (homeData.upcomingAnniversary != null) {
        cards.add {
            QuickInfoCard(
                icon = Icons.Outlined.StarOutline,
                label = homeData.upcomingAnniversary!!.title,
                value = "${homeData.upcomingAnniversary!!.daysUntil} 天后",
            )
        }
    }

    if (homeData.recentMuseumItems.isNotEmpty()) {
        cards.add {
            QuickInfoCard(
                icon = Icons.Outlined.Archive,
                label = "纪念馆",
                value = "${homeData.recentMuseumItems.size} 件新藏品",
            )
        }
    }

    if (cards.isEmpty()) return

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        cards.forEachIndexed { index, card ->
            card()
            if (index < cards.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun QuickInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}
