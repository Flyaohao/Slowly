package com.couple.translator.core.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppInfoBanner
import com.couple.translator.core.ui.components.AppListItem
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.components.AppRingProgress
import com.couple.translator.core.ui.components.AppScoreBar
import com.couple.translator.core.ui.components.AppTag
import com.couple.translator.core.ui.components.DimensionRadarChart
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import kotlin.math.roundToInt

/** 后端 profile_type_label 缺失时的客户端兜底（与 profile_service.PROFILE_TYPE_LABELS 对齐）。 */
internal val profileTypeNames = mapOf(
    "secure" to "安全型依恋",
    "anxious" to "焦虑依恋型",
    "dismissive" to "疏离回避型",
    "fearful" to "恐惧回避型",
    "mixed" to "混合型依恋",
)

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
            AppPageHeader(title = "人格画像")

            AppInfoBanner(
                text = "画像来自你的问卷作答与你们的互动。这里能看到依据，也能纠正。",
                icon = Icons.Outlined.Info,
                modifier = Modifier.padding(horizontal = AppSpacing.screenH),
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
                    // 类型徽章 + 置信度环 + AI 解读正文 —— 类型与把握程度是
                    // 这张卡最重要的两个信息，先用图形元素立住，正文退居其次。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            AppTag(
                                text = myProfile.profileTypeLabel
                                    ?: profileTypeNames[myProfile.profileType]
                                    ?: myProfile.profileType,
                                leadingIcon = Icons.Outlined.Favorite,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = myProfile.summary?.takeIf { it.isNotBlank() }
                                    ?: "完成问卷后，军师会在这里写下对你的理解。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        AppRingProgress(progress = myProfile.confidence) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${(myProfile.confidence * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AppAccent,
                                )
                                Text(
                                    text = "置信度",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppTextTertiary,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "作答越完整，这个数字越高",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextTertiary,
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
            // 2026-09-28 用户拍板：MBTI/星盘此前只进军师 prompt，画像页不展示。
            // 口径：问卷画像为主，MBTI 与星座/星盘仅作辅助参考（展示版说明由
            // 后端统一下发，不用 prompt 里那句「不得作为专业结论」——刚展示完
            // 就自我否定，读起来前后矛盾，同日用户反馈）。
            // 布局（用户反馈）：MBTI 一栏、星盘一栏，不挤在一起。
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
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    PersonalityRows(
                        entry = personality.me,
                        label = "我的",
                        onFillProfile = { onNavigateToRoute(Screen.Profile.route) },
                    )

                    AppListItemDivider()
                    val partner = personality.partner
                    if (partner == null) {
                        AppListItem(
                            title = "TA 的性格",
                            subtitle = "绑定伴侣后，可查看对方的性格辅助信息",
                        )
                    } else {
                        PersonalityRows(entry = partner, label = "TA 的", onFillProfile = null)
                    }

                    personality.referenceNote?.takeIf { it.isNotBlank() }?.let { note ->
                        AppListItemDivider()
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
                    // 雷达图给「整体形状」，分数条给「逐项细节」——两层数据视角，
                    // 替代之前整卡纯文字的排法。维度不足 3 个时雷达图画不出来，只上分数条。
                    if (uiState.dimensions.size >= 3) {
                        DimensionRadarChart(
                            dimensions = uiState.dimensions.map { dimension ->
                                Triple(
                                    dimension.dimensionKey,
                                    dimension.label
                                        ?: dimensionNames[dimension.dimensionKey]
                                        ?: dimension.dimensionKey,
                                    dimension.score,
                                )
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = "军师的每个判断都来自下面这些维度，分数与解读一一对应：",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    uiState.dimensions.forEachIndexed { index, dimension ->
                        if (index > 0) AppListItemDivider()
                        DimensionScoreRow(dimension)
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
 * 「判断来自哪里」的维度行：名称 + 分数 + 分数条 + 整行解读。
 *
 * 不用 AppListItem——它的 subtitle 是单行省略，解读文字会被截断；
 * 这里解读整行展示，分数条替代「行尾一个孤零零的数字」。
 * 分数条口径 0-100（与后端 bands 一致）；存量旧量纲数据只影响条的相对长度，
 * 数字仍原样展示，不掩盖原始值。
 */
@Composable
private fun DimensionScoreRow(dimension: ProfileDto.DimensionScoreResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dimension.label
                    ?: dimensionNames[dimension.dimensionKey]
                    ?: dimension.dimensionKey,
                style = MaterialTheme.typography.titleSmall,
                color = AppTextPrimary,
            )
            val scoreText = if (dimension.score == dimension.score.toInt().toFloat()) {
                "${dimension.score.toInt()} 分"
            } else {
                "${dimension.score} 分"
            }
            Text(
                text = scoreText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppAccent,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        AppScoreBar(score = dimension.score)
        val explanation = dimension.explanation?.takeIf { it.isNotBlank() }
        if (explanation != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
    }
}

/**
 * 一个人的性格辅助两栏：MBTI 一栏、星盘一栏（2026-09-28 用户反馈，不再挤成一坨）。
 *
 * 2026-09-28 二次反馈：一句话形容不了一个人的性格，且 AppListItem 副行只有
 * 单行省略号——改为「抽屉式」：标题行点击展开/收起，完整解读自绘 Text，
 * 不限行数；默认展开（信息要全）。
 *
 * 数据全部由服务端算好下发；缺哪段就显示对应引导，不占位、不编数据——
 * 与后端 astrology_service「缺失如实为 null」的口径一致。
 * [onFillProfile] 仅自己有（跳资料页补充）；伴侣的资料只能本人改，传 null。
 */
@Composable
private fun PersonalityRows(
    entry: ProfileDto.PersonalityEntryResponse?,
    label: String,
    onFillProfile: (() -> Unit)?,
) {
    if (entry == null || !entry.filled) {
        AppListItem(
            title = "${label}性格",
            subtitle = if (onFillProfile != null) {
                "还没补充 MBTI 与生日，点这里去资料页填写"
            } else {
                "对方还没在 TA 的资料页补充 MBTI/生日"
            },
            leadingIcon = if (onFillProfile != null) Icons.Outlined.Person else null,
            showChevron = onFillProfile != null,
            onClick = onFillProfile,
        )
    } else {
        if (entry.mbti != null) {
            val mbtiTitle = buildString {
                append(label)
                append("MBTI：")
                append(entry.mbti)
                entry.mbtiName?.let {
                    append(" · ")
                    append(it)
                }
            }
            ExpandablePersonalityRow(
                title = mbtiTitle,
                detail = entry.mbtiDescription ?: "暂无更多解读",
            )
        } else {
            AppListItem(
                title = "${label}MBTI",
                subtitle = "还没填 MBTI，可在个人资料页补充",
            )
        }

        AppListItemDivider()
        val signSummary = buildString {
            entry.zodiac?.let { append("太阳 ${it}座") }
            entry.moonSign?.let { if (isNotEmpty()) append(" · "); append("月亮 ${it}座") }
            entry.risingSign?.let { if (isNotEmpty()) append(" · "); append("上升 ${it}座") }
        }
        val zodiacDetail = buildString {
            append(signSummary)
            entry.zodiacInterpretation?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(it)
            }
        }
        ExpandablePersonalityRow(
            title = "${label}星盘",
            detail = zodiacDetail.ifBlank { "填了生日（含出生时辰更准）后自动推算" },
        )
    }
}

/**
 * 抽屉式信息行：标题行 + 可展开的完整解读。
 *
 * 刻意不走 AppListItem.subtitle——它是 maxLines=1 + Ellipsis，长解读必然被
 * 截断（用户反馈的原问题）。完整文本放在展开区自绘，不限行数。
 * 默认展开：用户明确要求「信息一定要全」，折叠只是收起手段，不是隐藏手段。
 */
@Composable
private fun ExpandablePersonalityRow(
    title: String,
    detail: String,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    AppListItem(
        title = title,
        showChevron = true,
        onClick = { expanded = !expanded },
    )
    AnimatedVisibility(visible = expanded) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}
