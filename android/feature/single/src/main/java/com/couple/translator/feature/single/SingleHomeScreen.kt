package com.couple.translator.feature.single

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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppLinkRow
import com.couple.translator.core.ui.components.AppLinkText
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
import com.couple.translator.feature.single.data.model.DiaryDto

/**
 * 单身模式首页。
 *
 * 和情侣模式首页保持同一套语言：顶栏只放头像入口、正文大标题 + 一句说明、
 * 快捷入口收进卡片、日记列表收进卡片、主操作是全宽按钮。
 */
@Composable
fun SingleHomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToQuestionnaire: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToBind: () -> Unit = {},
    onNavigateToDiary: () -> Unit = {},
    identity: TopBarIdentity = TopBarIdentity(),
    viewModel: SingleHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading) {
        SkeletonListPage(cardRows = 3, showButton = true)
        return
    }

    PullToRefreshLayout(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize().background(AppBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            AppTopBar(
                onOpenDrawer = onOpenDrawer,
                isCoupleMode = false,
                identity = TopBarIdentity(
                    userAvatarUrl = identity.userAvatarUrl,
                    nickname = identity.nickname ?: uiState.nickname,
                ),
            )

            AppPageHeader(
                title = "你好，${uiState.nickname ?: "朋友"}",
                subtitle = "记录生活，了解自己。",
            )

            SectionTitle(text = "快捷入口")
            AppListCard(
                items = listOf(
                    Triple(Icons.Outlined.Quiz, "了解自己", "填写问卷，生成个人画像"),
                    Triple(Icons.Outlined.Person, "我的画像", "查看个人维度分析"),
                    Triple(Icons.Outlined.Edit, "绑定情侣", "邀请 TA，解锁完整功能"),
                ),
                modifier = Modifier.padding(horizontal = AppSpacing.screenH),
            ) { (icon, label, description) ->
                AppListItem(
                    title = label,
                    subtitle = description,
                    leadingIcon = icon,
                    showChevron = true,
                    onClick = when (label) {
                        "了解自己" -> onNavigateToQuestionnaire
                        "我的画像" -> onNavigateToProfile
                        else -> onNavigateToBind
                    },
                )
            }

            if (uiState.recentDiaries.isNotEmpty()) {
                SectionTitle(
                    text = "最近日记",
                    count = uiState.recentDiaries.size,
                    trailing = {
                        AppLinkText(label = "查看全部", onClick = onNavigateToDiary)
                    },
                )
                AppListCard(
                    items = uiState.recentDiaries,
                    modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                ) { diary ->
                    DiaryRow(diary = diary, onClick = onNavigateToDiary)
                }
            } else {
                SectionTitle(text = "最近日记")
                AppLinkRow(
                    label = "还没有日记，从这里开始",
                    leadingEmoji = "📝",
                    onClick = onNavigateToDiary,
                    modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                )
            }

            AppPrimaryButton(
                text = "写日记",
                icon = Icons.Outlined.Edit,
                onClick = onNavigateToDiary,
                modifier = Modifier
                    .padding(horizontal = AppSpacing.screenH)
                    .padding(top = AppSpacing.section),
            )

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun DiaryRow(
    diary: DiaryDto.DiaryResponse,
    onClick: () -> Unit,
) {
    AppListItem(
        title = diary.title.ifBlank { "无标题" },
        subtitle = diary.mood,
        leadingEmoji = "📝",
        trailingText = diary.createdAt?.take(10),
        onClick = onClick,
    )
}
