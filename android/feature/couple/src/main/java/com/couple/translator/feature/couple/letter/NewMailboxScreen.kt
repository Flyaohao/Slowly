package com.couple.translator.feature.couple.letter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppLinkRow
import com.couple.translator.core.ui.components.AppListCard
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppTopBar
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.SkeletonListPage
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.feature.couple.data.model.LetterDto

/**
 * 深度表达（情侣模式，原「信箱」）/ 我的观点（单身模式）。
 *
 * 两种形态共用同一份内容（2026-09-28 用户裁决）：
 * - **tab 形态**（默认）：[onNavigateBack] 为 null，顶栏是叠头像 + 抽屉入口，
 *   挂在 CoupleShell 内层 NavHost 的 tab_mailbox 上（底栏隐藏 ≠ 删除）；
 * - **二级页形态**：[onNavigateBack] 非空，顶栏换成返回箭头、无头像无 tab 栏，
 *   注册在根导航 Screen.Mailbox 上——使用指南 / 抽屉 / 关系页 / 收信通知兜底
 *   四个「深度表达」入口全部走这一形态（压栈，系统返回可退回来源页）。
 *
 * 排版原则和首页对齐：**顶栏只放叠头像入口，标题交给正文大标题**；
 * 列表不再是「裸行 + 全宽分隔线」，而是收进卡片里 —— 分组一看就清楚，
 * 屏底那个居中的纯文字链接也换成了有容器的行。
 */
@Composable
fun NewMailboxScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToCompose: () -> Unit,
    onNavigateToLetterList: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
    isCoupleMode: Boolean = true,
    identity: TopBarIdentity = TopBarIdentity(),
    onNavigateBack: (() -> Unit)? = null,
    viewModel: MailboxViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(isCoupleMode) {
        viewModel.loadMailbox(isCoupleMode)
    }

    if (uiState.isLoading) {
        SkeletonListPage(cardRows = 3)
        return
    }

    PullToRefreshLayout(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refresh(isCoupleMode) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            if (onNavigateBack != null) {
                // 二级页形态：返回箭头顶栏，没有抽屉头像（正文大标题由 AppPageHeader 提供）。
                AppBackTopBar(onBack = onNavigateBack)
            } else {
                AppTopBar(
                    onOpenDrawer = onOpenDrawer,
                    isCoupleMode = isCoupleMode,
                    identity = identity,
                )
            }

            if (isCoupleMode) {
                CoupleMailboxContent(
                    uiState = uiState,
                    onNavigateToCompose = onNavigateToCompose,
                    onNavigateToLetterList = onNavigateToLetterList,
                    onNavigateToLetterDetail = onNavigateToLetterDetail,
                )
            } else {
                SingleDiaryContent(
                    uiState = uiState,
                    onNavigateToCompose = onNavigateToCompose,
                    onNavigateToLetterList = onNavigateToLetterList,
                    onNavigateToLetterDetail = onNavigateToLetterDetail,
                )
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

// ==================== 情侣模式 ====================

@Composable
private fun CoupleMailboxContent(
    uiState: MailboxUiState,
    onNavigateToCompose: () -> Unit,
    onNavigateToLetterList: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
) {
    val pending = uiState.receivedLetters.size

    AppPageHeader(
        title = "深度表达",
        // 副标题保持数据驱动（未读数）与能力描述，不提「信箱」旧名
        subtitle = when {
            pending > 0 -> "有 $pending 封信在等你打开"
            else -> "认真写下的句子，会一直留在这里。"
        },
    )

    Spacer(modifier = Modifier.height(AppSpacing.section))

    AppPrimaryButton(
        text = "写一封信",
        icon = Icons.Outlined.Edit,
        onClick = onNavigateToCompose,
        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
    )

    if (uiState.receivedLetters.isNotEmpty()) {
        SectionTitle(text = "收到的信", count = uiState.receivedLetters.size)
        AppListCard(
            items = uiState.receivedLetters.take(3),
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        ) { letter ->
            LetterRow(
                letter = letter,
                icon = Icons.Outlined.Inbox,
                onClick = { onNavigateToLetterDetail(letter.id) },
            )
        }
    }

    if (uiState.sentLetters.isNotEmpty()) {
        SectionTitle(text = "已发出", count = uiState.sentLetters.size)
        AppListCard(
            items = uiState.sentLetters.take(3),
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        ) { letter ->
            LetterRow(
                letter = letter,
                icon = Icons.Outlined.Send,
                onClick = { onNavigateToLetterDetail(letter.id) },
            )
        }
    }

    if (uiState.favoriteLetters.isNotEmpty()) {
        SectionTitle(text = "收藏的信", count = uiState.favoriteLetters.size)
        AppListCard(
            items = uiState.favoriteLetters.take(3),
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        ) { letter ->
            LetterRow(
                letter = letter,
                icon = Icons.Outlined.StarOutline,
                onClick = { onNavigateToLetterDetail(letter.id) },
            )
        }
    }

    if (uiState.receivedLetters.isEmpty() &&
        uiState.sentLetters.isEmpty() &&
        uiState.favoriteLetters.isEmpty()
    ) {
        AppEmptyState(
            icon = Icons.Outlined.MailOutline,
            title = "还没有信件",
            subtitle = "从第一句认真话开始。",
            modifier = Modifier.padding(top = AppSpacing.section),
        )
    }

    SectionTitle(text = "更多")
    AppLinkRow(
        label = "全部信件",
        leadingIcon = Icons.Outlined.MailOutline,
        onClick = onNavigateToLetterList,
        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
    )
}

// ==================== 单身模式 ====================

@Composable
private fun SingleDiaryContent(
    uiState: MailboxUiState,
    onNavigateToCompose: () -> Unit,
    onNavigateToLetterList: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
) {
    val total = uiState.recentDiaries.size

    AppPageHeader(
        title = "我的观点",
        subtitle = if (total > 0) "已经写下 $total 条" else "写给自己，也算数。",
    )

    Spacer(modifier = Modifier.height(AppSpacing.section))

    AppPrimaryButton(
        text = "写一条观点",
        icon = Icons.Outlined.Edit,
        onClick = onNavigateToCompose,
        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
    )

    if (uiState.recentDiaries.isNotEmpty()) {
        SectionTitle(text = "最近观点", count = uiState.recentDiaries.size)
        AppListCard(
            items = uiState.recentDiaries,
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        ) { letter ->
            LetterRow(
                letter = letter,
                icon = Icons.Outlined.EditNote,
                onClick = { onNavigateToLetterDetail(letter.id) },
            )
        }
    }

    if (uiState.favoriteLetters.isNotEmpty()) {
        SectionTitle(text = "收藏", count = uiState.favoriteLetters.size)
        AppListCard(
            items = uiState.favoriteLetters.take(3),
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        ) { letter ->
            LetterRow(
                letter = letter,
                icon = Icons.Outlined.StarOutline,
                onClick = { onNavigateToLetterDetail(letter.id) },
            )
        }
    }

    if (uiState.recentDiaries.isEmpty() && uiState.favoriteLetters.isEmpty()) {
        AppEmptyState(
            icon = Icons.Outlined.Edit,
            title = "还没有观点",
            subtitle = "记录此刻的心情。",
            modifier = Modifier.padding(top = AppSpacing.section),
        )
    }

    SectionTitle(text = "更多")
    AppLinkRow(
        label = "全部观点",
        leadingIcon = Icons.Outlined.Edit,
        onClick = onNavigateToLetterList,
        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
    )
}

// ==================== 列表行 ====================

@Composable
private fun LetterRow(
    letter: LetterDto.LetterResponse,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    AppListItem(
        title = letter.title?.ifBlank { "无标题" } ?: "无标题",
        subtitle = letter.content?.take(60)?.replace('\n', ' ')?.ifBlank { null },
        leadingIcon = icon,
        trailingText = formatDateShort(letter.sendTime ?: letter.createdAt),
        onClick = onClick,
    )
}

private fun formatDateShort(isoString: String?): String {
    if (isoString == null) return ""
    return try {
        val odt = java.time.OffsetDateTime.parse(isoString)
        val dt = odt.toLocalDateTime()
        val now = java.time.LocalDateTime.now()
        when {
            dt.toLocalDate() == now.toLocalDate() -> dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            dt.year == now.year -> dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
            else -> dt.format(java.time.format.DateTimeFormatter.ofPattern("yy-MM-dd"))
        }
    } catch (_: Exception) {
        isoString.take(10)
    }
}
