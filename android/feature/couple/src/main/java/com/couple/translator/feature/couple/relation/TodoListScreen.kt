package com.couple.translator.feature.couple.relation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing

/**
 * 待办列表页（2026-09-27 关系页改版 ①A②A④A）。
 *
 * 调解邀请 / 双视角「我未提交」/ 解绑确认 三类待办的唯一列表入口，
 * 从关系页原样迁来（④A 沿用现有列表项样式，纯迁移）。点击行为不变：
 * - 调解邀请 → 调解邀请详情页（isInviter=false，进页后以 GET {id} 的 my_role 校正）
 * - 双视角 → 双视角详情页
 * - 解绑确认 → 情侣信息页（CoupleInfo）
 *
 * 整改 §8.4 的语义原样保留：三类源读取失败时显示「读取失败 + 重试」，
 * 不允许渲染成「暂无待办」骗用户说没事。
 */
@Composable
fun TodoListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (String) -> Unit,
    viewModel: TodoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "待办")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        color = AppAccent,
                        modifier = Modifier.padding(vertical = AppSpacing.block),
                    )
                }

                uiState.pendingCount == 0 && !uiState.pendingReliable -> {
                    // 整改 §8.4：读不到 ≠ 没有。任何一类待办源失败时都不能说
                    // 「暂无待办」——那会让用户以为真的没事要做，给就地重试的出口。
                    AppEmptyState(
                        icon = Icons.Outlined.Info,
                        title = "待办状态没读出来",
                        subtitle = "网络或服务异常，重试一次试试",
                        action = { AppPrimaryButton(text = "重试", onClick = { viewModel.load() }) },
                    )
                }

                uiState.pendingCount == 0 -> {
                    AppEmptyState(
                        icon = Icons.Outlined.Favorite,
                        title = "暂无待办",
                        subtitle = "调解邀请、双视角与解绑确认会出现在这里",
                    )
                }

                else -> {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.block),
                    ) {
                        var isFirst = true

                        // 2026-09-28 共同调解室（D-IA：双方强提醒）。进行中的房间
                        // 两端都可见，是最强的事件提醒对象。
                        if (uiState.mediationRoomActive > 0) {
                            isFirst = false
                            AppListItem(
                                title = "共同调解室",
                                subtitle = "有 ${uiState.mediationRoomActive} 间调解室正在进行",
                                leadingIcon = Icons.Outlined.Forum,
                                showChevron = true,
                                onClick = { onNavigateToRoute(Screen.MediationRoomList.route) },
                            )
                        }

                        uiState.mediationInvites.forEach { invite ->
                            if (!isFirst) AppListItemDivider()
                            isFirst = false
                            AppListItem(
                                title = invite.title ?: "各自的看法邀请",
                                subtitle = "对方发起了「各自的看法」，等你回应",
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
                            AppListItem(
                                title = "解除绑定待确认",
                                subtitle = when (unbind.isInitiator) {
                                    true -> "你已发起解绑，可前往取消"
                                    false -> "对方发起了结绑申请，可前往查看与确认"
                                    null -> "有一笔解绑申请待处理"
                                },
                                leadingIcon = Icons.Outlined.Info,
                                showChevron = true,
                                onClick = { onNavigateToRoute(Screen.CoupleInfo.route) },
                            )
                        }
                    }
                }
            }
        }
    }
}
