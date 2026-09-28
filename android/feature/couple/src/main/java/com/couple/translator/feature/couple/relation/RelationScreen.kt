package com.couple.translator.feature.couple.relation

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.SectionTitle
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
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 关系 tab（契约 §3.5 MVP，S2 两 tab 壳的新主页之一）。
 *
 * 2026-09-27 关系页改版（用户裁决）：
 * - 首屏第一眼 = 「在一起 N 天」大字页头；
 * - 待处理三项（调解邀请 / 双视角 / 解绑确认）是低频通知，全部迁往
 *   侧边栏「待办」条目（[com.couple.translator.feature.couple.relation.TodoListScreen]），
 *   本页不再渲染，也不再把空态占半屏。
 *
 * 结构：页头（在一起 N 天）+ 关系背景（纪念日、绑定信息、关系画像摘要、
 * 深度表达入口、调解回看）。
 *
 * 整改 §8.4：原先「更多」区块里的当前议题 / 共同约定 / 关系模式 / 关系脉络
 * 四个占位行已删除——它们全是「阶段四开放」的假功能，点了没有任何反应。
 * 未建完整议题模型前**不伪造**入口（§8.7 同款要求）。
 *
 * 所有跳转走根路由（[onNavigateToRoute]），子页压在壳之上、可返回。
 */
@Composable
fun RelationScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToRoute: (String) -> Unit,
    identity: TopBarIdentity = TopBarIdentity(),
    viewModel: RelationViewModel = hiltViewModel(),
    // Activity 作用域：与 CoupleShell 的角标共用同一个 ObservationViewModel
    // （见 ObservationViewModel 类注释——默认 hiltViewModel() 会按
    // NavBackStackEntry 各建一份，ack 之后角标无法同步清零）。
    observationViewModel: ObservationViewModel =
        hiltViewModel(LocalContext.current as ComponentActivity),
) {
    val uiState by viewModel.uiState.collectAsState()
    val observationState by observationViewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 整改 §8.4：返回本页必须重新拉取。
    //
    // 这里用 LaunchedEffect(Unit) 而不是 lifecycle ON_RESUME：本页在壳的内层
    // NavHost 里，去写信 / 复盘 / 双视角等根级页面时本 composable 会离开组合，
    // 回来时重新进入组合 → 这个 effect 会再跑一次；ViewModel 却按 back stack
    // entry 存活，所以正好是「数据保留、状态刷新」。
    // 首帧不会重复请求：init 里的 load() 已把 isLoading 置位，刷新分支会跳过。
    LaunchedEffect(Unit) {
        viewModel.load(isRefresh = true)
    }

    // 观察卡每次进页重拉：ack 之后 has_new=false，三态自然回落安静态。
    // ackIfNew=true：只有真正打开本页才算「已读」（决策⑥）。
    LaunchedEffect(Unit) {
        observationViewModel.load(ackIfNew = true)
    }

    // 观察详情弹窗：点卡片展开全文（用户裁决「弹窗或二级页都行」→ 弹窗，
    // 影响面最小：不加路由、不加接口）。V2 正文变长后再升二级页。
    var showObservationDetail by remember { mutableStateOf(false) }

    // 刷新失败（页面已有内容、不整页报错）只弹一次性提示，不动已渲染的数据。
    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppTopBar(onOpenDrawer = onOpenDrawer, identity = identity)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 2026-09-27 改版：页头第一眼 = 「在一起 N 天」（③A 页头大字 + 绑定日期副行）。
            // loveDays 还没读到时降级为「我们的关系」，不闪空标题。
            // R 系列：数字用 animateIntAsState 从 0 滚动入场（读到位才开始滚，不闪跳）。
            // 去AI味 P-3b：页头升级为渐变 hero 卡（S5 白名单①「关系页页头底色」），
            // 与两个首页同一套语言；天数居中放大做首屏唯一重心。
            val targetDays = uiState.loveDays
            val animatedDays by animateIntAsState(
                targetValue = targetDays ?: 0,
                animationSpec = tween(
                    durationMillis = AppMotion.slow,
                    easing = AppMotion.EaseOut,
                ),
                label = "loveDays",
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH)
                    .padding(top = AppSpacing.md)
                    .clip(RoundedCornerShape(AppRadius.xl))
                    .background(AppPrimaryGradient)
                    .border(0.5.dp, AppOnAccent.copy(alpha = 0.25f), RoundedCornerShape(AppRadius.xl))
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.section),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (targetDays != null) {
                    Text(
                        text = "在一起",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppOnAccent.copy(alpha = 0.85f),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = animatedDays.toString(),
                            style = MaterialTheme.typography.displaySmall,
                            color = AppOnAccent,
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "天",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppOnAccent.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                } else {
                    Text(
                        text = "我们的关系",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppOnAccent,
                    )
                }
                uiState.bindTime?.take(10)?.let { bindDate ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "绑定于 $bindDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppOnAccent.copy(alpha = 0.8f),
                    )
                }
            }

            // ---------- 军师的观察（第一内容位，V1 聚合版） ----------
            // 《军师主动观察》设计文档 §二：push 位——用户不开口，军师也告诉
            // 你们「它最近看到了什么」。三态（§八）：高亮 / 安静 / 冷启动。
            // loaded=false（首拉失败）时整块不渲染：不把「读不到」装成冷启动。
            if (observationState.loaded) {
                SectionTitle("军师的观察")
                ObservationCard(
                    state = observationState,
                    onClick = if (observationState.content != null) {
                        { showObservationDetail = true }
                    } else {
                        null // 冷启动引导语不可点
                    },
                )
                Spacer(modifier = Modifier.height(AppSpacing.block))
            }

            if (uiState.loadError) {
                // 整页失败：关键源（home + couples/me）都没回来，页面无从渲染——
                // 给错误 + 重试，不能静默渲染成「没事发生」。
                //
                // 注意不能写 return@Column：Column 是 inline composable，
                // qualified return 会触发 Compose 编译器 group 错位 bug
                // （同 MuseumScreen.kt / RelationshipEventScreen.kt 记录的
                // compose-jb#2230 类闪退），必须用 if/else 分支结构。
                AppEmptyState(
                    icon = Icons.Outlined.Info,
                    title = "关系页加载失败",
                    subtitle = "网络或服务异常，重试一次试试",
                    action = {
                        AppPrimaryButton(
                            text = "重试",
                            onClick = { viewModel.load() },
                        )
                    },
                )
                Spacer(modifier = Modifier.height(AppSpacing.block))
            } else {
                // ---------- 关系背景 ----------
                SectionTitle("关系背景")
                BackgroundSection(
                    uiState = uiState,
                    onNavigateToRoute = onNavigateToRoute,
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.block))
        }
    }

    if (showObservationDetail && observationState.content != null) {
        ObservationDetailDialog(
            state = observationState,
            onOpened = { observationViewModel.markCardViewed() },
            onDismiss = { showObservationDetail = false },
        )
    }
}

@Composable
private fun BackgroundSection(
    uiState: RelationUiState,
    onNavigateToRoute: (String) -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
    ) {
        var isFirst = true

        // 纪念日（GET /anniversaries，按年周期滚动计算下一次）
        val anniversary = uiState.upcomingAnniversary
        if (anniversary != null) {
            val days = uiState.upcomingDaysUntil
            val daysText = when {
                days == null -> null
                days <= 0 -> "就在今天"
                else -> "还有 $days 天"
            }
            // 整改 §8.8：日期说明与列表页共用同一套规则（每年 X 月 X 日 · 下次 …），
            // 不再直接把原始日期贴在「还有 N 天」旁边——那正是契约点名的年份冲突。
            val dateText = anniversary.nextOccurrenceDate?.takeIf { it.isNotBlank() }
                ?.let { "下次 $it" }
                ?: anniversary.anniversaryDate.takeIf { it.isNotBlank() }
            AppListItem(
                title = anniversary.title,
                subtitle = listOfNotNull(daysText, dateText).joinToString(" · "),
                leadingIcon = Icons.Outlined.Event,
                showChevron = true,
                onClick = { onNavigateToRoute(Screen.AnniversaryList.route) },
            )
            isFirst = false
        }

        // 绑定信息（couples/me：love_days / bind_time / 空间）
        if (uiState.loveDays != null || uiState.partnerNickname != null) {
            if (!isFirst) AppListItemDivider()
            isFirst = false
            val partner = uiState.partnerNickname
            AppListItem(
                title = if (partner != null) "和 $partner 在一起" else "我们的关系",
                subtitle = buildString {
                    uiState.loveDays?.let { append("恋爱第 $it 天") }
                    uiState.bindTime?.let {
                        if (isNotEmpty()) append(" · ")
                        append("绑定于 ${it.take(10)}")
                    }
                    if (isEmpty()) append("查看绑定与空间设置")
                },
                leadingIcon = Icons.Outlined.Favorite,
                showChevron = true,
                onClick = { onNavigateToRoute(Screen.CoupleInfo.route) },
            )
        }

        // 关系画像摘要（GET /profiles/couple；详情入口 → 三合一页）
        if (!isFirst) AppListItemDivider()
        AppListItem(
            title = "关系画像",
            subtitle = uiState.coupleSummary?.takeIf { it.isNotBlank() }?.let {
                if (it.length > 48) it.take(48) + "…" else it
            } ?: "军师对你们的理解都记在这里",
            leadingIcon = Icons.Outlined.ViewSidebar,
            showChevron = true,
            onClick = { onNavigateToRoute(Screen.Understanding.route) },
        )

        // 深度表达（原「信件收件箱」）：走根导航的「深度表达」二级页（Screen.Mailbox，
        // 2026-09-28 用户裁决：压栈全屏、返回箭头顶栏，不再是内层信箱 tab 一级页）。
        // 未读计数是真实数据（inboxCount），照旧展示。
        AppListItemDivider()
        AppListItem(
            title = "深度表达",
            subtitle = if (uiState.inboxCount > 0) "${uiState.inboxCount} 封信在等你" else "写下来，比说出来容易",
            leadingIcon = Icons.Outlined.MailOutline,
            showChevron = true,
            onClick = { onNavigateToRoute(Screen.Mailbox.route) },
        )

        // §8.5-6「能回看」+ 2026-09-28 D-LEGACY：旧调解链路改名「各自的看法」
        // （定位=共同调解室的前置准备：各自私下向军师陈述立场）。数据与路由全保留，
        // 只改展示文案；新「共同调解室」入口见军师 tab 顶部 / 抽屉 / 待办。
        val pastMediations = uiState.completedMediations
        if (pastMediations.isNotEmpty()) {
            AppListItemDivider()
            AppListItem(
                title = "各自的看法",
                subtitle = "已完成的沟通记录（${pastMediations.size} 次）",
                leadingIcon = Icons.Outlined.History,
                showChevron = true,
                onClick = { onNavigateToRoute(Screen.MediationHistory.route) },
            )
        }
    }
}

// --------------------------------------------------------------------------- //
// 军师的观察卡（V1 聚合版，《军师主动观察》设计文档 §八三态）
// --------------------------------------------------------------------------- //

/**
 * 观察卡三态：高亮（有新）/ 安静（无新）/ 冷启动（无素材）。
 *
 * - 高亮 = 浅强调底 + 强调描边 + NEW 角标（isNewForCard 只在本次进页有效，
 *   ack 后保留——见 [ObservationViewModel] 注释）；
 * - 点击卡片 → 详情弹窗展开全文（[onClick]，仅正文态可点）；
 * - 正文卡内截断 4 行（服务端 ≤120 字约束下长文必然触达），弹窗里看全文；
 * - 引用素材按 F-3 拍板只展示来源文字（「引用：调解书《xx》」），不跳转；
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
            // R 系列：pressFeedback 必须在链最前；先 padding 后点击区=可见卡片
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
                        .clip(RoundedCornerShape(50))
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
                    // R 系列：NEW 角标缩放入场（0→1 spring），出现不是「啪一下」
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
                            .background(
                                AppErrorRed,
                                RoundedCornerShape(50),
                            ),
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
                overflow = TextOverflow.Ellipsis,
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
 *
 * - [onOpened] 在弹窗组合时回调一次：清卡片高亮/NEW（本地态）——
 *   用户点开看了全文，NEW 再挂到下次进页只剩干扰；
 * - 服务端 ack 不在这里做（决策⑥：进页即已读），纯展示。
 */
@Composable
private fun ObservationDetailDialog(
    state: ObservationUiState,
    onOpened: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 调用方已守卫 content != null，这里再收窄一次给编译器；
    // 本函数非 inline composable，顶层 return 不触发 group 错位问题。
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

/** 服务端时间 → 相对时间（「2 小时前」「昨天 21:04」）。解析失败返回 null，不显示。 */
internal fun observationRelativeTime(iso: String): String? {
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
