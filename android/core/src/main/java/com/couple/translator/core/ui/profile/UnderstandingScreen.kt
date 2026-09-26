package com.couple.translator.core.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextSecondary
import kotlin.math.roundToInt

/**
 * 「军师如何理解我们」（契约 §3.2 画像三合一，W4.3）。
 *
 * 合并原「我的画像 / 了解自己 / 关系画像」三入口，纯组合现有端点（零新后端）：
 * - 我的画像：GET /profiles/me（summary + confidence）
 * - 判断来自哪里：GET /profiles/me/dimensions（逐维度 explanation + confidence）
 * - 关系画像：GET /profiles/couple（summary + conflict_pattern，缺失即降级提示）
 * - 记忆治理：既有记忆治理页（Screen.Memory）。整改 §8.8 起文案与真实能力对齐
 *   ——「查看、调整可见范围或删除」，不再叫「我要纠正军师」（那会让人以为
 *   能直接改写军师的判断，而实际能改的是它记住的内容）。
 * 旧路由 ProfileResult / QuestionnaireIntro / CoupleProfile / QuestionnaireHistory 全部保留。
 */
@Composable
fun UnderstandingScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (String) -> Unit,
    viewModel: UnderstandingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "军师如何理解我们")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AppPageHeader(
                title = "军师如何理解我们",
                subtitle = "画像来自你的问卷作答与你们的互动。这里能看到依据，也能纠正。",
            )

            if (uiState.error.isNotBlank()) {
                Text(
                    text = uiState.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.typography.bodySmall.color,
                    modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                )
            }

            // ---------- 我的画像 ----------
            SectionTitle("我的画像")
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                val myProfile = uiState.myProfile
                if (myProfile != null) {
                    Text(
                        text = myProfile.summary?.takeIf { it.isNotBlank() }
                            ?: "完成问卷后，军师会在这里写下对你的理解。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "置信度 ${(myProfile.confidence * 100).roundToInt()}% · " +
                            "作答越完整，这个数字越高",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                } else if (!uiState.isLoading) {
                    Text(
                        text = "还没有画像。做一份关系问卷，军师就开始理解你。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppListItem(
                        title = "去做问卷",
                        subtitle = "11 个维度，几分钟完成",
                        leadingIcon = Icons.Outlined.Quiz,
                        showChevron = true,
                        onClick = { onNavigateToRoute(Screen.QuestionnaireIntro.route) },
                    )
                }
            }

            // ---------- 判断来自哪里 ----------
            SectionTitle("判断来自哪里")
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                if (uiState.dimensions.isEmpty()) {
                    Text(
                        text = if (uiState.isLoading) "加载中…" else "完成问卷后，这里会列出每个维度的依据。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                } else {
                    Text(
                        text = "军师的每个判断都来自下面这些维度，分数与解读一一对应：",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    uiState.dimensions.forEachIndexed { index, dimension ->
                        if (index > 0) AppListItemDivider()
                        val name = dimensionNames[dimension.dimensionKey] ?: dimension.dimensionKey
                        val scoreText = if (dimension.score == dimension.score.toInt().toFloat()) {
                            "${dimension.score.toInt()} 分"
                        } else {
                            "${dimension.score} 分"
                        }
                        AppListItem(
                            title = name,
                            subtitle = dimension.explanation?.takeIf { it.isNotBlank() }
                                ?: "暂无更多解读",
                            trailingText = scoreText,
                            tileColor = Color.Transparent,
                        )
                    }
                }
            }

            // ---------- 关系画像 ----------
            SectionTitle("关系画像")
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                val coupleProfile = uiState.coupleProfile
                if (coupleProfile != null) {
                    Text(
                        text = coupleProfile.summary?.takeIf { it.isNotBlank() }
                            ?: "双人问卷完成后，军师会写下对你们的理解。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                    coupleProfile.conflictPattern?.takeIf { it.isNotBlank() }?.let { pattern ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "冲突模式：$pattern",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary,
                        )
                    }
                } else if (!uiState.isLoading) {
                    Text(
                        text = "绑定伴侣并完成双人问卷后生成。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                }
            }

            // ---------- 纠正与深入 ----------
            SectionTitle("纠正与深入")
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                AppListItem(
                    title = "查看、调整或删除军师记住的内容",
                    subtitle = "逐条查看军师记住了什么，改可见范围，或直接删掉",
                    leadingIcon = Icons.Outlined.Edit,
                    showChevron = true,
                    onClick = { onNavigateToRoute(Screen.Memory.route) },
                )
                AppListItemDivider()
                AppListItem(
                    title = "重新做问卷",
                    subtitle = "重新填写，更新画像与依据",
                    leadingIcon = Icons.Outlined.Quiz,
                    showChevron = true,
                    onClick = { onNavigateToRoute(Screen.QuestionnaireIntro.route) },
                )
                AppListItemDivider()
                AppListItem(
                    title = "问卷历史",
                    subtitle = "回看每一次作答与结果",
                    leadingIcon = Icons.Outlined.History,
                    showChevron = true,
                    onClick = { onNavigateToRoute(Screen.QuestionnaireHistory.route) },
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.block))
        }
    }
}
