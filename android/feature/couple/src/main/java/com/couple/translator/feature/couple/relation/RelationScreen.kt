package com.couple.translator.feature.couple.relation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * 结构：
 * 1. 待处理 —— 调解邀请、双视角「我未提交」、解绑确认状态
 * 2. 关系背景 —— 纪念日、绑定信息（love_days）、关系画像摘要、信件入口（原「空间/信箱」tab 的有用内容迁入）
 * 3. 阶段四占位 —— 当前议题 / 共同约定 / 关系模式 / 关系脉络（收敛期不建端点，仅占位）
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
            AppPageHeader(
                title = "关系",
                subtitle = "需要你处理的在最上面，其余是你们的共同信息",
            )

            if (uiState.loadError) {
                // M3：整页数据全挂时给错误 + 重试，不能静默渲染成「暂无待处理事项」
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
                return@Column
            }

            // ---------- 1. 待处理 ----------
            SectionTitle(
                text = "待处理",
                count = uiState.pendingCount.takeIf { it > 0 },
            )
            PendingSection(
                uiState = uiState,
                onNavigateToRoute = onNavigateToRoute,
            )

            // ---------- 2. 关系背景 ----------
            SectionTitle("关系背景")
            BackgroundSection(
                uiState = uiState,
                onNavigateToRoute = onNavigateToRoute,
            )

            // ---------- 3. 阶段四占位 ----------
            SectionTitle("更多")
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                AppListItem(
                    title = "当前议题",
                    subtitle = "你们最近反复出现的冲突主题（阶段四开放）",
                    leadingIcon = Icons.Outlined.Psychology,
                )
                AppListItemDivider()
                AppListItem(
                    title = "共同约定",
                    subtitle = "两个人谈妥并确认的相处规则（阶段四开放）",
                    leadingIcon = Icons.Outlined.StarOutline,
                )
                AppListItemDivider()
                AppListItem(
                    title = "关系模式",
                    subtitle = "依恋与互动模式的长期画像（阶段四开放）",
                    leadingIcon = Icons.Outlined.Analytics,
                )
                AppListItemDivider()
                AppListItem(
                    title = "关系脉络",
                    subtitle = "重要节点串成的关系时间线（阶段四开放）",
                    leadingIcon = Icons.Outlined.History,
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.block))
        }
    }
}

@Composable
private fun PendingSection(
    uiState: RelationUiState,
    onNavigateToRoute: (String) -> Unit,
) {
    if (uiState.isLoading) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.block),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = AppAccent)
        }
        return
    }

    if (uiState.pendingCount == 0) {
        AppEmptyState(
            icon = Icons.Outlined.Favorite,
            title = "暂无待处理事项",
            subtitle = "调解邀请、双视角与解绑确认会出现在这里",
        )
        return
    }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
    ) {
        var isFirst = true

        uiState.mediationInvites.forEach { invite ->
            if (!isFirst) AppListItemDivider()
            isFirst = false
            AppListItem(
                title = invite.title ?: "双人调解邀请",
                subtitle = "对方发起了调解，等你回应",
                leadingIcon = Icons.Outlined.People,
                showChevron = true,
                onClick = {
                    // isInviter=false：邀请列表的语义就是「伴侣发起、我来应答」；
                    // 进页后仍会以 GET {id} 的 my_role 为真源校正。
                    onNavigateToRoute(
                        "${Screen.MediationInvite.route}?sessionId=${invite.sessionId}&isInviter=false"
                    )
                },
            )
        }

        uiState.myUnsubmittedDuals.forEach { dual ->
            if (!isFirst) AppListItemDivider()
            isFirst = false
            AppListItem(
                title = dual.title,
                subtitle = "对方已提交，等你写下自己的视角",
                leadingIcon = Icons.Outlined.Visibility,
                showChevron = true,
                onClick = {
                    onNavigateToRoute("${Screen.DualPerspectiveDetail.route}/${dual.id}")
                },
            )
        }

        uiState.unbindStatus?.let { unbind ->
            if (!isFirst) AppListItemDivider()
            val subtitle = when (unbind.isInitiator) {
                true -> "你已发起解绑，可前往取消"
                false -> "对方发起了结绑申请，可前往查看与确认"
                null -> "有一笔解绑申请待处理"
            }
            AppListItem(
                title = "解除绑定待确认",
                subtitle = subtitle,
                leadingIcon = Icons.Outlined.Info,
                showChevron = true,
                onClick = { onNavigateToRoute(Screen.CoupleInfo.route) },
            )
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
            AppListItem(
                title = anniversary.title,
                subtitle = listOfNotNull(daysText, anniversary.anniversaryDate.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
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

        // 信件收件箱（深度表达入口）
        AppListItemDivider()
        AppListItem(
            title = "信件收件箱",
            subtitle = if (uiState.inboxCount > 0) "${uiState.inboxCount} 封信在等你" else "写下来，比说出来容易",
            leadingIcon = Icons.Outlined.MailOutline,
            showChevron = true,
            onClick = { onNavigateToRoute(Screen.LetterList.route) },
        )
    }
}
