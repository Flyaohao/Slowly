package com.couple.translator.feature.couple.home

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppMotion
import com.couple.translator.core.ui.theme.AppOnAccent
import com.couple.translator.core.ui.theme.AppPrimaryGradient
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.InterFontFamily
import com.couple.translator.feature.couple.relation.ObservationUiState
import com.couple.translator.feature.couple.relation.ObservationViewModel
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 「我们的空间」——情侣模式一级页（2026-09-29 重构，顶替已删除的关系页）。
 *
 * **定位：回忆 / 生活向**。这一页只回答一个问题——「我们一起走到了哪、留下了什么」。
 * 与另两个页面的分工：
 * - 军师页 = 当下「这件事怎么办」；
 * - 空间页 = 过去→现在「我们经历了什么」；
 * - 信箱页 = 单次「有句话想认真说给你听」。
 *
 * 内容块（自上而下，用户拍板）：
 * ① 天数头图（在一起 N 天，全 App 唯一展示处）
 * ② 军师的观察（原关系页三态卡迁入）
 * ③ 共同记录入口宫格（信箱 / 观点 / 纪念日 / 纪念事件）
 * ④ 共同时间线（信件 + 观点 + 纪念日 + 关系事件，倒序混流）
 *
 * 与旧版的差异：删除了 HomePrimaryButton（W1 已是隐藏的 no-op）、
 * HomeQuickEntryCards（并入宫格）、HomeStatusCards（并入时间线）、
 * HomeRecentSection（被时间线取代）、HomeAiHint（被观察卡取代）。
 */
@Composable
fun NewHomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToRoute: (String) -> Unit,
    onNavigateToComposeLetter: () -> Unit,
    onNavigateToMailbox: () -> Unit,
    onNavigateToAiChat: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
    onNavigateToBind: () -> Unit = {},
    identity: TopBarIdentity = TopBarIdentity(),
    pageActive: Boolean = true,
    viewModel: NewHomeViewModel = hiltViewModel(),
    timelineViewModel: SpaceTimelineViewModel = hiltViewModel(),
    // Activity 作用域：与 CoupleShell 的空间 tab 角标共用同一个 ObservationViewModel
    // （见 ObservationViewModel 类注释——ack 之后角标要同步清零）。
    observationViewModel: ObservationViewModel =
        hiltViewModel(LocalContext.current as ComponentActivity),
) {
    val uiState by viewModel.uiState.collectAsState()
    val timelineState by timelineViewModel.uiState.collectAsState()
    val observationState by observationViewModel.uiState.collectAsState()

    // 观察详情弹窗开关（纯本地态，由本页持有）
    var showObservationDetail by remember { mutableStateOf(false) }

    // 每次滑到本页（pageActive false→true）重拉观察并 ack：
    // ackIfNew=true 只有真正打开本页才算「已读」（决策⑥）。
    LaunchedEffect(pageActive) {
        if (!pageActive) return@LaunchedEffect
        observationViewModel.load(ackIfNew = true)
    }

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
        isRefreshing = uiState.isRefreshing || timelineState.isRefreshing,
        onRefresh = {
            viewModel.refresh()
            timelineViewModel.refresh()
            observationViewModel.load()
        },
    ) {
        if (uiState.isLoading) {
            SpaceSkeleton()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .verticalScroll(rememberScrollState()),
            ) {
                AppTopBar(
                    onOpenDrawer = onOpenDrawer,
                    identity = barIdentity,
                )

                AppPageHeader(
                    title = uiState.spaceName,
                    subtitle = "我们走过的地方，都在这里。",
                )

                // ---------- ① 在一起的天数 ----------
                StaggeredAppear(0) {
                    DaysHeroCard(
                        daysCount = uiState.daysCount,
                        userNickname = uiState.nickname,
                        partnerNickname = uiState.partnerNickname,
                    )
                }

                // ---------- ② 军师的观察 ----------
                if (observationState.loaded) {
                    StaggeredAppear(1) {
                        Column {
                            SectionTitle("军师的观察")
                            ObservationCard(
                                state = observationState,
                                onClick = if (observationState.content != null) {
                                    { showObservationDetail = true }
                                } else {
                                    null // 冷启动引导语不可点
                                },
                            )
                        }
                    }
                }

                // ---------- ③ 共同记录入口 ----------
                StaggeredAppear(2) {
                    Column {
                        SectionTitle("共同记录")
                        RecordEntryGrid(
                            pendingLetterCount = uiState.pendingLetterCount,
                            onNavigateToRoute = onNavigateToRoute,
                            onNavigateToComposeLetter = onNavigateToComposeLetter,
                        )
                    }
                }

                // ---------- ④ 共同时间线 ----------
                StaggeredAppear(3) {
                    Column {
                        SectionTitle("共同时间线")
                        SharedTimeline(
                            state = timelineState,
                            onEntryClick = { entry ->
                                when (entry.kind) {
                                    // 信件走类型化的回调（与 CoupleShell 的接线一致）
                                    TimelineKind.Letter -> {
                                        val id = entry.key.removePrefix("letter-").toLongOrNull()
                                        if (id != null) onNavigateToLetterDetail(id)
                                    }
                                    // 观点/纪念日/关系事件统一走根导航路由字符串
                                    else -> entry.route?.let { onNavigateToRoute(it) }
                                }
                            },
                            onRetry = { timelineViewModel.load() },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }

    // 观察详情弹窗（三态卡的「看全文」），随空间页走
    if (showObservationDetail && observationState.content != null) {
        ObservationDetailDialog(
            state = observationState,
            onOpened = { observationViewModel.markCardViewed() },
            onDismiss = { showObservationDetail = false },
        )
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

// ============ ① 主视觉：在一起的天数 ============

/**
 * 首屏唯一的重心。全 App 唯一展示「在一起 N 天」的位置（关系页删除后无重复）。
 *
 * 结构：小标「在一起」→ 天文数字 → 双方昵称，数字用 animateIntAsState 从 0 滚动入场。
 */
@Composable
private fun DaysHeroCard(
    daysCount: Int,
    userNickname: String?,
    partnerNickname: String?,
) {
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

    val animatedDays by animateIntAsState(
        targetValue = daysCount,
        animationSpec = tween(durationMillis = AppMotion.slow, easing = AppMotion.EaseOut),
        label = "loveDays",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .padding(top = AppSpacing.lg)
            .clip(RoundedCornerShape(AppRadius.xl))
            .background(AppPrimaryGradient)
            .border(0.5.dp, AppOnAccent.copy(alpha = 0.25f), RoundedCornerShape(AppRadius.xl))
            .padding(vertical = AppSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "在一起",
            style = MaterialTheme.typography.labelMedium,
            color = AppOnAccent.copy(alpha = 0.85f),
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (daysCount > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = animatedDays.toString(),
                    style = numberStyle,
                    color = AppOnAccent,
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "天",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppOnAccent.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        } else {
            Text(
                text = "刚刚开始",
                style = MaterialTheme.typography.headlineMedium,
                color = AppOnAccent,
            )
        }

        if (nicknameLine.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = nicknameLine,
                style = MaterialTheme.typography.bodySmall,
                color = AppOnAccent.copy(alpha = 0.65f),
            )
        }
    }
}

// ============ ③ 共同记录入口宫格 ============

/**
 * 四宫格快捷入口：信箱 / 观点 / 纪念日 / 纪念事件。
 *
 * 与抽屉是**同路由双入口**（用户拍板 D6）：宫格负责「在空间里随手就能记一笔」，
 * 抽屉负责「关系管理类的稳定入口」。
 */
@Composable
private fun RecordEntryGrid(
    pendingLetterCount: Int,
    onNavigateToRoute: (String) -> Unit,
    onNavigateToComposeLetter: () -> Unit,
) {
    val items = listOf(
        RecordEntry(
            icon = Icons.Outlined.MailOutline,
            title = "信箱",
            subtitle = if (pendingLetterCount > 0) "$pendingLetterCount 封等你" else "写一封",
        ) { onNavigateToRoute(Screen.Mailbox.route) },
        RecordEntry(
            icon = Icons.Outlined.EditNote,
            title = "观点",
            subtitle = "各自的看法",
        ) { onNavigateToRoute(Screen.DiaryList.route) },
        RecordEntry(
            icon = Icons.Outlined.Event,
            title = "纪念日",
            subtitle = "重要的日子",
        ) { onNavigateToRoute(Screen.AnniversaryList.route) },
        RecordEntry(
            icon = Icons.Outlined.EventNote,
            title = "纪念事件",
            subtitle = "一起记下的",
        ) { onNavigateToRoute(Screen.RelationshipEvent.route) },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { entry ->
                    RecordEntryCard(entry = entry, modifier = Modifier.weight(1f))
                }
                // 奇数个时补一个空位，避免最后一行卡片被拉伸成半宽
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private data class RecordEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

@Composable
private fun RecordEntryCard(
    entry: RecordEntry,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pressFeedback(onClick = entry.onClick)
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppSurface)
            .border(0.5.dp, AppBorderLight, RoundedCornerShape(AppRadius.lg))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(AppRadius.xs))
                .background(AppAccentFaint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = AppTextPrimary,
                maxLines = 1,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
                maxLines = 1,
            )
        }
    }
}

// ============ ④ 共同时间线 ============

/**
 * 共同时间线（本页核心）。
 *
 * 左侧一条垂直细线贯穿，节点 = 圆点（普通内容）/ 图标（里程碑：纪念日/关系事件），
 * 右侧是内容卡。倒序（最近的在上）。空态给引导卡。
 */
@Composable
private fun SharedTimeline(
    state: SpaceTimelineUiState,
    onEntryClick: (TimelineEntry) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.isLoading && state.entries.isEmpty() -> {
            TimelineSkeleton()
        }

        state.loadError && state.entries.isEmpty() -> {
            AppEmptyState(
                icon = Icons.Outlined.CloudOff,
                title = "时间线没加载出来",
                subtitle = "网络或服务异常，重试一次试试",
                action = {
                    AppPrimaryButton(text = "重试", onClick = onRetry)
                },
            )
        }

        state.entries.isEmpty() -> {
            EmptyTimelineGuide()
        }

        else -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                state.entries.forEachIndexed { index, entry ->
                    TimelineRow(
                        entry = entry,
                        isFirst = index == 0,
                        isLast = index == state.entries.lastIndex,
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(
    entry: TimelineEntry,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val isMilestone = entry.kind == TimelineKind.Anniversary || entry.kind == TimelineKind.Event
    val nodeColor = if (isMilestone) AppAccentLight else AppBorderLight

    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧轨道：细线 + 节点
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(if (isFirst) 24.dp else 64.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 竖线（首条不画上半段，末条不画下半段——这里简化为始终画满，视觉连续）
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(if (isFirst) 12.dp else if (isLast) 12.dp else 64.dp)
                    .offset(y = if (isFirst) 24.dp else 0.dp)
                    .background(AppBorderLight),
            )
            if (isMilestone) {
                Box(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(nodeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (entry.kind == TimelineKind.Anniversary) {
                            Icons.Outlined.StarOutline
                        } else {
                            Icons.Outlined.EventNote
                        },
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(AppAccentLight),
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 14.dp)
                // pressFeedback 必须在 modifier 链最前：graphicsLayer 只对链中
                // 它之后的节点生效，放后面会导致按压缩放不作用在卡片背景上。
                .pressFeedback(onClick = onClick)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(AppSurface)
                .border(0.5.dp, AppBorderLight, RoundedCornerShape(AppRadius.md))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = timelineDateLabel(entry.occurredAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
            entry.excerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 时间线日期：显示完整年月日（YYYY-MM-DD → YYYY年M月d日）；不可解析时原文返回。 */
private fun timelineDateLabel(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val datePart = iso.take(10)
    val parts = datePart.split("-")
    if (parts.size != 3) return datePart
    val year = parts[0].trimStart('0').ifBlank { "0" }
    val month = parts[1].trimStart('0').ifBlank { "0" }
    val day = parts[2].trimStart('0').ifBlank { "0" }
    return "$year 年 $month 月 $day 日"
}

/**
 * 服务端时间 → 相对时间（「2 小时前」「昨天 21:04」）。解析失败返回 null，不显示。
 *
 * 2026-09-29：随观察卡从关系页迁到空间页（原定义在 RelationScreen.kt，
 * 该文件已随关系页删除）。
 */
private fun observationRelativeTime(iso: String): String? {
    val time = try {
        LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    } catch (_: Exception) {
        return null
    }
    val now = LocalDateTime.now()
    val minutes = Duration.between(time, now).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        time.toLocalDate() == now.toLocalDate().minusDays(1) ->
            "昨天 " + time.format(DateTimeFormatter.ofPattern("HH:mm"))
        else -> time.format(DateTimeFormatter.ofPattern("M月d日"))
    }
}

@Composable
private fun EmptyTimelineGuide() {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AppAccentFaint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Favorite,
                    contentDescription = null,
                    tint = AppAccent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "你们的故事，从第一条记录开始。",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "写一封信、记下一个观点，或者加一个纪念日，\n它们都会留在这条时间线上。",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
    }
}

@Composable
private fun TimelineSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp),
                shape = RoundedCornerShape(AppRadius.md),
            )
        }
    }
}

// ============ 军师的观察卡（自关系页迁入，2026-09-29） ============

/**
 * 观察卡三态：高亮（有新）/ 安静（无新）/ 冷启动（无素材）。
 *
 * - 高亮 = 浅强调底 + 强调描边 + NEW 角标（isNewForCard 只在本次进页有效）；
 * - 点击卡片 → 详情弹窗展开全文（[onClick]，仅正文态可点）；
 * - 正文卡内截断 4 行，弹窗里看全文；
 * - 颜色全部走 App* getter（深色模式自动切换），无 Canvas/remember lambda。
 */
@Composable
private fun ObservationCard(
    state: ObservationUiState,
    onClick: (() -> Unit)? = null,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .padding(horizontal = AppSpacing.screenH)
                        .pressFeedback(onClick = onClick)
                } else {
                    Modifier.padding(horizontal = AppSpacing.screenH)
                }
            ),
        containerColor = if (state.isNewForCard) AppAccentFaint else AppSurface,
        borderColor = if (state.isNewForCard) AppAccentLight else AppBorderLight,
    ) {
        if (state.content == null) {
            // 态③ 冷启动：没有任何可拼装素材（首观察前）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AppAccentFaint),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Spa,
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "随着你们使用，军师会在这里\n记下它对这段关系的观察。",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                )
            }
        } else {
            // 态① 高亮 / 态② 安静：同一结构，只有颜色与 NEW 角标不同
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "军师的观察",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isNewForCard) AppAccent else AppTextSecondary,
                )
                state.observedAt?.let { observationRelativeTime(it) }?.let { timeText ->
                    Text(
                        text = " · $timeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                    )
                }
                if (state.isNewForCard) {
                    Spacer(modifier = Modifier.width(6.dp))
                    // NEW 角标缩放入场（0→1 spring），出现不是「啪一下」
                    val badgeScale by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "newBadgeScale",
                    )
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = badgeScale
                                scaleY = badgeScale
                            }
                            .background(AppErrorRed, RoundedCornerShape(50)),
                    ) {
                        Text(
                            text = "NEW",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.content,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary,
                maxLines = 4,
            )
            state.citationTitle?.let { title ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "引用：调解书《$title》",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
        }
    }
}

/**
 * 观察详情弹窗：完整军师建议 = 观察正文全文 + 观察时间 + 引用来源。
 */
@Composable
private fun ObservationDetailDialog(
    state: ObservationUiState,
    onOpened: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 调用方已守卫 content != null，这里再收窄一次给编译器
    val body = state.content ?: return
    LaunchedEffect(Unit) { onOpened() }
    val timeText = state.observedAt?.let { observationRelativeTime(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("军师的观察") },
        text = {
            Column {
                if (timeText != null) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextPrimary,
                )
                state.citationTitle?.let { title ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "引用：调解书《$title》",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = AppAccent)
            }
        },
    )
}

// ============ 骨架屏 ============

/** 首屏加载态：把真实排版先用灰块摆出来，数据到位时只是"填色"，不会整页跳一下。 */
@Composable
private fun SpaceSkeleton() {
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

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(
                modifier = Modifier
                    .weight(1f)
                    .height(66.dp),
                shape = RoundedCornerShape(AppRadius.lg),
            )
            SkeletonBlock(
                modifier = Modifier
                    .weight(1f)
                    .height(66.dp),
                shape = RoundedCornerShape(AppRadius.lg),
            )
        }
    }
}
