package com.couple.translator.feature.couple.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.HomeDto
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.AvatarBubble
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.AppWarm
import com.couple.translator.core.ui.theme.AppWarmLight
import com.couple.translator.core.ui.theme.InterFontFamily
import com.couple.translator.feature.couple.data.model.PresenceDto
import com.couple.translator.feature.couple.presence.MeetCountdown

/**
 * 情侣模式首页。
 *
 * 这一版的排版原则：**先有一个视觉锚点，再谈信息**。
 * 之前的版本把「在一起第几天」当成一行正文排在标题下面，整页读起来像文档；
 * 现在这个数字被放大成 52sp 放进一张淡粉卡片里，成为首屏唯一的重心，
 * 其余信息（快捷入口 / 军师 / 最近）依次退到它下面。
 */
@Composable
fun NewHomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToComposeLetter: () -> Unit,
    onNavigateToMailbox: () -> Unit,
    onNavigateToAiChat: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
    onNavigateToBind: () -> Unit = {},
    isCoupleMode: Boolean = true,
    identity: TopBarIdentity = TopBarIdentity(),
    viewModel: NewHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 顶栏身份优先用全局共享的那份（与信箱/军师同源），拿不到时退回本页自己拉的
    val barIdentity = remember(identity, uiState) {
        if (identity.userAvatarUrl != null || identity.nickname != null) {
            identity
        } else {
            TopBarIdentity(
                userAvatarUrl = uiState.userAvatarUrl,
                nickname = uiState.nickname,
                partnerAvatarUrl = uiState.partnerAvatarUrl,
                partnerNickname = uiState.partnerNickname,
            )
        }
    }

    PullToRefreshLayout(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        if (uiState.isLoading) {
            HomeSkeleton()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .verticalScroll(rememberScrollState()),
            ) {
                AppTopBar(
                    onOpenDrawer = onOpenDrawer,
                    isCoupleMode = isCoupleMode,
                    identity = barIdentity,
                )

                if (isCoupleMode) {
                    StaggeredAppear(0) {
                        AppPageHeader(
                            title = uiState.spaceName,
                            subtitle = uiState.heroText,
                        )
                    }

                    StaggeredAppear(1) {
                        HomeHeroCard(
                            daysCount = uiState.daysCount,
                            userNickname = uiState.nickname,
                            partnerNickname = uiState.partnerNickname,
                        )
                    }

                    StaggeredAppear(2) {
                        // [W1 隐藏] 调解/纪念日两个主按钮目前是 no-op 空转，先隐藏入口
                        // （隐藏 ≠ 删除：HomePrimaryAction 分支与文案生成逻辑保留）
                        val primaryAction = uiState.primaryAction
                        val isNoOpAction = primaryAction == HomePrimaryAction.ContinueMediation ||
                            primaryAction == HomePrimaryAction.ViewAnniversary
                        if (!isNoOpAction) {
                            HomePrimaryButton(
                                text = uiState.primaryButtonText,
                                onClick = {
                                    when (uiState.primaryAction) {
                                        HomePrimaryAction.ReadLetter,
                                        HomePrimaryAction.ContinueDraft,
                                        -> onNavigateToMailbox()
                                        HomePrimaryAction.InvitePartner -> onNavigateToBind()
                                        HomePrimaryAction.ContinueMediation -> {}
                                        HomePrimaryAction.ViewAnniversary -> {}
                                        else -> onNavigateToComposeLetter()
                                    }
                                },
                            )
                        }
                    }

                    StaggeredAppear(3) {
                        Box(modifier = Modifier.padding(top = AppSpacing.lg)) {
                            HomeQuickEntryCards(
                                pendingLetterCount = uiState.pendingLetterCount,
                                onNavigateToMailbox = onNavigateToMailbox,
                                onNavigateToAiChat = onNavigateToAiChat,
                            )
                        }
                    }

                    uiState.homeData?.let { data ->
                        // [W1 隐藏] 未来信 / 纪念馆状态卡已隐藏，不再参与「有没有卡」判断
                        val hasAnything = data.activeMediation != null ||
                            data.upcomingAnniversary != null
                        if (hasAnything) {
                            StaggeredAppear(4) {
                                Box(modifier = Modifier.padding(top = AppSpacing.section)) {
                                    HomeStatusCards(homeData = data)
                                }
                            }
                        }
                    }

                    uiState.partnerMoment?.let { moment ->
                        StaggeredAppear(5) {
                            Box(modifier = Modifier.padding(top = AppSpacing.lg)) {
                                PartnerMomentCard(
                                    moment = moment,
                                    companionSent = uiState.companionSent,
                                    onSendCompanion = viewModel::sendCompanion,
                                )
                            }
                        }
                    }

                    if (uiState.recentItems.isNotEmpty()) {
                        StaggeredAppear(6) {
                            Box(modifier = Modifier.padding(top = AppSpacing.section)) {
                                HomeRecentSection(
                                    items = uiState.recentItems,
                                    onItemClick = { item ->
                                        when (item.type) {
                                            "letter", "draft" -> onNavigateToLetterDetail(item.id)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    StaggeredAppear(7) {
                        Box(modifier = Modifier.padding(top = AppSpacing.section)) {
                            HomeAiHint(onClick = onNavigateToAiChat)
                        }
                    }

                    Box(modifier = Modifier.padding(top = AppSpacing.section)) {
                        MeetCountdown(
                            targetDate = uiState.homeData?.space?.nextMeetDate
                                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                        )
                    }
                } else {
                    HomeSingleModeSection(
                        onNavigateToComposeLetter = onNavigateToComposeLetter,
                        onNavigateToBind = onNavigateToBind,
                    )
                }

                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

// ============ 入场动画 ============

/**
 * 让区块依次浮现，而不是整页同时出现。
 *
 * 用 graphicsLayer + offset 而不是 AnimatedVisibility ——
 * 后者会从 0 高度展开，导致 Column 的滚动位置在动画期间被反复重算、页面"抖"一下。
 */
@Composable
private fun StaggeredAppear(
    index: Int,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val delayMillis = index * 45
    val easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 300, delayMillis = delayMillis, easing = easing),
        label = "appearAlpha",
    )
    val offsetY by animateDpAsState(
        targetValue = if (shown) 0.dp else 18.dp,
        animationSpec = tween(durationMillis = 300, delayMillis = delayMillis, easing = easing),
        label = "appearOffset",
    )

    Box(
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .offset(y = offsetY),
    ) {
        content()
    }
}

// ============ 主视觉：在一起的天数 ============

/**
 * 首屏唯一的重心。
 *
 * 这一版去掉了卡内那两个白色圆头像：
 * 一是顶栏已经有一组（一屏四张脸没必要），二是白圈落在淡粉底上像"挖了个洞"。
 * 空出来的位置给了一个 eyebrow 小标「在一起」，卡片从"三行居中"变成
 * **小标 → 天文数字 → 昵称** 的三级结构，同时把上下留白收回一档，不再显得空。
 */
@Composable
private fun HomeHeroCard(
    daysCount: Int,
    userNickname: String?,
    partnerNickname: String?,
) {
    val accent = AppAccent

    val numberStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 52.sp,
        lineHeight = 56.sp,
        letterSpacing = (-2).sp,
    )

    val nicknameLine = remember(userNickname, partnerNickname) {
        listOfNotNull(
            userNickname?.takeIf { it.isNotBlank() },
            partnerNickname?.takeIf { it.isNotBlank() },
        ).joinToString(" 和 ")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .padding(top = AppSpacing.lg)
            .clip(RoundedCornerShape(AppRadius.xl))
            .background(AppAccentFaint)
            .border(0.5.dp, accent.copy(alpha = 0.14f), RoundedCornerShape(AppRadius.xl))
            .padding(vertical = AppSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "在一起",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (daysCount > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = daysCount.toString(),
                    style = numberStyle,
                    color = AppTextPrimary,
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "天",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        } else {
            Text(
                text = "刚刚开始",
                style = MaterialTheme.typography.headlineMedium,
                color = AppTextPrimary,
            )
        }

        if (nicknameLine.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = nicknameLine,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
    }
}

// ============ 主操作按钮 ============

@Composable
private fun HomePrimaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .padding(top = AppSpacing.lg)
            .height(AppSize.button),
        shape = RoundedCornerShape(AppRadius.pill),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppTextPrimary,
            contentColor = AppSurface,
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

// ============ 快捷入口 ============

@Composable
private fun HomeQuickEntryCards(
    pendingLetterCount: Int,
    onNavigateToMailbox: () -> Unit,
    onNavigateToAiChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickEntryCard(
            icon = Icons.Outlined.MailOutline,
            title = "信箱",
            subtitle = if (pendingLetterCount > 0) "$pendingLetterCount 封等你回应" else "还没有待读的信",
            onClick = onNavigateToMailbox,
            modifier = Modifier.weight(1f),
        )
        QuickEntryCard(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = "军师",
            subtitle = "随时帮你整理",
            onClick = onNavigateToAiChat,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = AppBorderLight
    Column(
        modifier = modifier
            .pressFeedback(onClick = onClick)
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppSurface)
            .border(0.5.dp, border, RoundedCornerShape(AppRadius.lg))
            .padding(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = AppTextTertiary,
            maxLines = 1,
        )
    }
}

// ============ 在场感 ============

/** 在场感卡片：对方最新一条动态（此刻状态 / 陪伴请求）+ 发陪伴请求按钮。 */
@Composable
private fun PartnerMomentCard(
    moment: PresenceDto.MomentResponse,
    companionSent: Boolean,
    onSendCompanion: () -> Unit,
) {
    val accent = AppAccent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppAccentFaint)
            .padding(14.dp),
    ) {
        Text(
            text = if (moment.momentType == "companion_request") "TA 需要你的陪伴" else "TA 此刻",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = moment.content,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextPrimary,
        )
        if (moment.momentType != "companion_request") {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onSendCompanion,
                enabled = !companionSent,
            ) {
                Text(
                    text = if (companionSent) "陪伴请求已发出" else "TA 需要我 · 发陪伴请求",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
        }
    }
}

// ============ 最近 ============

@Composable
private fun HomeRecentSection(
    items: List<RecentItem>,
    onItemClick: (RecentItem) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = AppSpacing.screenH)) {
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
                    .pressFeedback(onClick = { onItemClick(item) })
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(AppRadius.xs))
                        .background(AppSurfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MailOutline,
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

// ============ AI 入口 ============

@Composable
private fun HomeAiHint(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .pressFeedback(onClick = onClick)
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppSurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "没想好怎么说？让军师替你润色",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = AppTextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ============ 单身模式 ============

@Composable
private fun HomeSingleModeSection(
    onNavigateToComposeLetter: () -> Unit,
    onNavigateToBind: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
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
                contentColor = AppSurface,
            ),
            shape = RoundedCornerShape(AppRadius.pill),
            modifier = Modifier
                .fillMaxWidth()
                .height(AppSize.button),
        ) {
            Text(
                text = "邀请 TA",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        TextButton(onClick = onNavigateToComposeLetter) {
            Text(
                text = "或者，先写给自己",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
    }
}

// ============ 状态卡（有数据时才出现） ============

@Composable
private fun HomeStatusCards(homeData: HomeDto.HomeResponse) {
    val cards = mutableListOf<Pair<ImageVector, Pair<String, String>>>()

    homeData.activeMediation?.let {
        cards.add(Icons.Outlined.People to ("调解进行中" to "还有一场没说完的对话"))
    }
    // [W1 隐藏] 未来信状态卡（P0-5：future 类型冻结，卡片也不再展示）
    // homeData.futureLetter?.let {
    //     cards.add(Icons.Outlined.Schedule to ("未来信" to "解锁于 ${it.unlockTime?.take(10) ?: "待定"}"))
    // }
    homeData.upcomingAnniversary?.let {
        cards.add(Icons.Outlined.StarOutline to (it.title to "${it.daysUntil} 天后"))
    }
    // [W1 隐藏] 纪念馆状态卡（模块冻结 10006）
    // if (homeData.recentMuseumItems.isNotEmpty()) {
    //     cards.add(Icons.Outlined.Archive to ("纪念馆" to "${homeData.recentMuseumItems.size} 件新藏品"))
    // }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.forEach { (icon, texts) ->
            StatusCard(icon = icon, title = texts.first, value = texts.second)
        }
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = AppTextSecondary,
        )
    }
}

// ============ 骨架屏 ============

/** 首屏加载态：把真实排版先用灰块摆出来，数据到位时只是"填色"，不会整页跳一下。 */
@Composable
private fun HomeSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(horizontal = AppSpacing.screenH),
    ) {
        Spacer(modifier = Modifier.height(AppSize.topBar))

        SkeletonBlock(modifier = Modifier.width(150.dp).height(28.dp))
        Spacer(modifier = Modifier.height(10.dp))
        SkeletonBlock(modifier = Modifier.width(190.dp).height(13.dp))
        Spacer(modifier = Modifier.height(AppSpacing.lg))

        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(AppRadius.xl),
        )
        Spacer(modifier = Modifier.height(AppSpacing.lg))

        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppSize.button),
            shape = RoundedCornerShape(AppRadius.pill),
        )
        Spacer(modifier = Modifier.height(AppSpacing.lg))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(
                modifier = Modifier
                    .weight(1f)
                    .height(92.dp),
                shape = RoundedCornerShape(AppRadius.lg),
            )
            SkeletonBlock(
                modifier = Modifier
                    .weight(1f)
                    .height(92.dp),
                shape = RoundedCornerShape(AppRadius.lg),
            )
        }
    }
}
