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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Restore
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
import com.couple.translator.core.data.model.ProfileDto
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
 * 「人格画像」（契约 §3.2 画像三合一，W4.3）。
 *
 * 2026-09-27 用户裁决：名称由「军师如何理解我们」改为「人格画像」。
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
            AppBackTopBar(onBack = onNavigateBack, title = "人格画像")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AppPageHeader(
                title = "人格画像",
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

            // ---------- 性格辅助信息 ----------
            // 2026-09-28 用户拍板：MBTI/星盘此前只进军师 prompt，画像页不展示；
            // 本次新增展示区。口径不变：问卷画像为主，MBTI 次之，星座/星盘最弱，
            // 权重说明由后端统一下发（reference_note），不混入上面的维度评分体系。
            SectionTitle("性格辅助信息")
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                val personality = uiState.personality
                if (personality == null) {
                    Text(
                        text = if (uiState.isLoading) "加载中…" else "暂时拿不到性格辅助信息",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                } else {
                    val me = personality.me
                    if (me != null && me.filled) {
                        PersonalityEntryContent(me)
                    } else {
                        AppListItem(
                            title = "去个人资料页补充 MBTI 与生日",
                            subtitle = "补充后，这里会显示你的性格参考信息",
                            leadingIcon = Icons.Outlined.Person,
                            showChevron = true,
                            onClick = { onNavigateToRoute(Screen.Profile.route) },
                        )
                    }

                    val partner = personality.partner
                    AppListItemDivider()
                    Text(
                        text = "TA 的性格",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    when {
                        partner == null -> Text(
                            text = "绑定伴侣后，可查看对方的性格辅助信息。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                        )
                        partner.filled -> PersonalityEntryContent(partner)
                        else -> Text(
                            text = "对方还没在 TA 的资料页补充 MBTI/生日。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                        )
                    }

                    personality.referenceNote?.takeIf { it.isNotBlank() }?.let { note ->
                        AppListItemDivider()
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary,
                        )
                    }
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
                // 用户需求 #5：观点能自动补充画像，所以画像必须能回到过去——
                // 没有后悔药就不能开这个写入的口子。
                AppListItemDivider()
                AppListItem(
                    title = "画像历史版本",
                    subtitle = "看每一次变化，也能撤回到某一个版本",
                    leadingIcon = Icons.Outlined.Restore,
                    showChevron = true,
                    onClick = { onNavigateToRoute(Screen.ProfileVersions.route) },
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.block))
        }
    }
}

/**
 * 一个人的 MBTI + 星座展示（自己/伴侣共用）。
 *
 * 数据全部由服务端算好下发；缺哪段就不显示哪段，不占位、不编数据——
 * 与后端 astrology_service「缺失如实为 null」的口径一致。
 */
@Composable
private fun PersonalityEntryContent(entry: ProfileDto.PersonalityEntryResponse) {
    val mbtiLine = buildString {
        entry.mbti?.let { append(it) }
        entry.mbtiName?.let { if (isNotEmpty()) append(" · "); append(it) }
    }
    if (mbtiLine.isNotBlank()) {
        Text(
            text = mbtiLine,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
        )
    }
    entry.mbtiDescription?.takeIf { it.isNotBlank() }?.let { desc ->
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
        )
    }

    val zodiacLine = buildString {
        entry.zodiac?.let { append("太阳 ${it}座") }
        entry.moonSign?.let { if (isNotEmpty()) append(" · "); append("月亮 ${it}座") }
        entry.risingSign?.let { if (isNotEmpty()) append(" · "); append("上升 ${it}座") }
    }
    if (zodiacLine.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = zodiacLine,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
        )
    }
}
