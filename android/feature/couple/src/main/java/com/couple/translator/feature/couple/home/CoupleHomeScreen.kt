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
import androidx.compose.material3.TextButton
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
import com.couple.translator.feature.couple.data.model.PresenceDto
import com.couple.translator.feature.couple.presence.MeetCountdown
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

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
        Box(modifier = Modifier.fillMaxSize().background(AppBackground)) {
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
            .background(AppBackground)
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

            // 在场感：对方最新动态卡片
            uiState.partnerMoment?.let { moment ->
                Spacer(modifier = Modifier.height(16.dp))
                PartnerMomentCard(
                    moment = moment,
                    companionSent = uiState.companionSent,
                    onSendCompanion = viewModel::sendCompanion,
                )
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

/** 在场感卡片：对方最新一条动态（此刻状态 / 陪伴请求）+ 发陪伴请求按钮。 */
@Composable
private fun PartnerMomentCard(
    moment: PresenceDto.MomentResponse,
    companionSent: Boolean,
    onSendCompanion: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = AppAccentLight),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (moment.momentType == "companion_request") "TA 需要你的陪伴"
                    else "TA 此刻",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = moment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextPrimary,
                )
                if (moment.momentType != "companion_request") {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onSendCompanion, enabled = !companionSent) {
                        Text(
                            text = if (companionSent) "陪伴请求已发出" else "TA 需要我 · 发陪伴请求",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppAccent,
                        )
                    }
                }
            }
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
                    .background(AppAccentLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = "打开侧边栏",
                    tint = AppAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Text(
            text = spaceName ?: if (isCoupleMode) "我们的空间" else "我的空间",
            style = MaterialTheme.typography.titleMedium,
            color = AppTextPrimary,
        )

        if (isCoupleMode) {
            IconButton(onClick = { }) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = "通知",
                    tint = AppTextSecondary,
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
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (daysCount > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "第 ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextSecondary,
                )
                Text(
                    text = daysCount.toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = AppTextPrimary,
                )
                Text(
                    text = " 天",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = heroText,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextTertiary,
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
            containerColor = AppTextPrimary,
            contentColor = AppSurface,
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
            color = AppTextTertiary,
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
                        .background(AppBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = AppTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = item.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        maxLines = 1,
                    )
                }
                Text(
                    text = item.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
            if (index < items.lastIndex) {
                HorizontalDivider(color = AppBorderLight)
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
        HorizontalDivider(color = AppBorderLight)
        Spacer(modifier = Modifier.height(13.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "需要整理表达时，可以找",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Text(
                text = "翻译官",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = AppTextTertiary,
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
            color = AppTextPrimary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "绑定情侣后解锁完整功能",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextSecondary,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNavigateToBind,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppAccent,
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
            color = AppTextTertiary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNavigateToComposeLetter,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppSurface,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "写给自己",
                style = MaterialTheme.typography.titleMedium,
                color = AppTextPrimary,
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
            .background(AppSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
        )
    }
}
