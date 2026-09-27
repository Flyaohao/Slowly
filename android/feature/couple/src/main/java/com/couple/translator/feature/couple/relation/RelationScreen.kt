package com.couple.translator.feature.couple.relation

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing

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
) {
    val uiState by viewModel.uiState.collectAsState()
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
            AppPageHeader(
                title = uiState.loveDays?.let { "在一起 $it 天" } ?: "我们的关系",
                subtitle = uiState.bindTime?.take(10)?.let { "绑定于 $it" },
            )

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
