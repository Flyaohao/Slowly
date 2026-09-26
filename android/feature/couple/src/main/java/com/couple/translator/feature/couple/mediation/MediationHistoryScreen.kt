package com.couple.translator.feature.couple.mediation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppLinkText
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing

/**
 * 已完成调解回看（整改 §8.5-6「能回看」）。
 *
 * 后端已经保证 completed 可读（`_get_session` 不再拒绝）且留在 mine/all 里，
 * 但「接口能读」不等于「用户找得到」——§8.0 的走查口径明确要求**能回看**。
 * 本页就是这个入口：列出 history 列表，点进去复用调解结果页（它按会话 id
 * 读服务端总结，不区分「刚结束」还是「一个月前」）。
 *
 * 整改 B4.1-6：每行带一个「重新发起」——上一次谈完，过一阵子又遇到类似的
 * 情况是常态，而用户从这里的动机明确是「再谈一次」。它**不复用**旧会话
 * （旧会话的总结是那次沟通的记录，不能被新的一轮覆盖），而是走说明页
 * `POST /ai/mediation/start` 新建一场——真实 id 由后端给，与 AI 军师那条
 * 建议入口走的是同一条路。
 */
@Composable
fun MediationHistoryScreen(
    onNavigateBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onRestartMediation: () -> Unit = {},
    viewModel: MediationHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "调解回看")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AppPageHeader(
                title = "调解回看",
                subtitle = "已经谈完的那些，随时可以回来看看当时说定了什么",
            )

            if (uiState.isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = AppSpacing.block),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = AppAccent)
                }
                return@Column
            }

            if (uiState.items.isEmpty()) {
                // 空 ≠ 读不到：只有拿到成功响应且确实为空才说「还没有」。
                if (uiState.loadFailed) {
                    AppEmptyState(
                        icon = Icons.Outlined.History,
                        title = "调解记录没读出来",
                        subtitle = "网络或服务异常，重试一次试试",
                        action = { AppPrimaryButton(text = "重试", onClick = { viewModel.load() }) },
                    )
                } else {
                    AppEmptyState(
                        icon = Icons.Outlined.History,
                        title = "还没有完成的调解",
                        subtitle = "谈完之后，总结会留在这里",
                        // 空列表也留一条出路：用户点进「调解回看」却没看到东西时，
                        // 最可能的意图就是「那再谈一次」。
                        action = { AppPrimaryButton(text = "发起调解", onClick = onRestartMediation) },
                    )
                }
                return@Column
            }

            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                var isFirst = true
                uiState.items.forEach { item ->
                    if (!isFirst) AppListItemDivider()
                    isFirst = false
                    AppListItem(
                        title = item.title ?: "双人调解",
                        subtitle = listOfNotNull(
                            item.myRole?.let { if (it == "inviter") "你发起的" else "对方发起的" },
                            item.updatedAt?.take(10) ?: item.createdAt?.take(10),
                        ).joinToString(" · "),
                        leadingIcon = Icons.Outlined.History,
                        showChevron = true,
                        // 整改 B4.1-6：行尾「重新发起」。它在整行的点击区里，
                        // AppLinkText 自己消费了点击，所以点它不会连带打开详情。
                        trailing = {
                            AppLinkText(
                                label = "重新发起",
                                onClick = onRestartMediation,
                            )
                        },
                        onClick = { onOpenSession(item.sessionId) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.block))
        }
    }
}
