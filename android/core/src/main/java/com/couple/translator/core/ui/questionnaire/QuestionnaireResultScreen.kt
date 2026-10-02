package com.couple.translator.core.ui.questionnaire

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiStructuringHint
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppScoreBar
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.DimensionRadarChart
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppOnAccent
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSuccessGreen
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.AppWarning
import io.noties.markwon.Markwon

private val dimensionNames = mapOf(
    "attachment_anxiety" to "依恋焦虑",
    "attachment_avoidance" to "依恋回避",
    "conflict_pursue" to "冲突追问倾向",
    "conflict_withdraw" to "冲突退缩倾向",
    "defensive_response" to "防御反驳倾向",
    "emotional_validation_need" to "情绪确认需求",
    "factual_explanation_need" to "事实解释需求",
    "personal_space_need" to "独处冷静需求",
    "reassurance_need" to "安全感确认需求",
    "directness_preference" to "直接表达偏好",
    "softness_preference" to "柔和表达偏好",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionnaireResultScreen(
    questionnaireId: Long,
    submissionId: Long = 0,
    coupleProfileReady: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToCoupleProfile: () -> Unit,
    viewModel: QuestionnaireResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(questionnaireId, submissionId) {
        viewModel.setCoupleProfileReady(coupleProfileReady)
        if (submissionId > 0) {
            viewModel.loadFromSubmission(submissionId)
        } else {
            viewModel.loadAnalysis(questionnaireId)
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "测评结果",
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            // 流式生成期间直接展示过程本身：思考面板 + 正文打字机，
            // 比转圈诚实得多——用户能看见「它在想什么、已经写到哪了」。
            if (uiState.isStreaming) {
                AnalysisStreamingView(
                    thinkingText = uiState.thinkingText,
                    isThinking = uiState.isThinking,
                    thinkingSeconds = uiState.thinkingSeconds,
                    streamText = uiState.streamText,
                    isStructuring = uiState.isStructuring,
                    onStop = { viewModel.stopAnalysis() },
                )
                return@PullToRefreshLayout
            }

            if (uiState.isLoading || uiState.isAnalyzing) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(44.dp), color = AppAccent)
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    Text(
                        "AI 正在分析你的测评结果…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        "这可能需要 10-30 秒，请耐心等待",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                    )
                }
                return@PullToRefreshLayout
            }

            if (uiState.error.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AppEmptyState(
                        icon = Icons.Outlined.Quiz,
                        title = "分析生成失败",
                        subtitle = uiState.error,
                        action = {
                            AppPrimaryButton(text = "重新分析", onClick = { viewModel.retry() })
                        },
                    )
                }
                return@PullToRefreshLayout
            }

            val analysis = uiState.analysis
            if (analysis == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AppEmptyState(
                        icon = Icons.Outlined.Quiz,
                        title = "暂无分析结果",
                        subtitle = "重新分析一次，看看这次的结果。",
                        action = {
                            AppPrimaryButton(text = "重新分析", onClick = { viewModel.retry() })
                        },
                    )
                }
                return@PullToRefreshLayout
            }

            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.lg))

                // === 依恋类型主卡 ===
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(500, easing = FastOutSlowInEasing)) { it / 6 },
                ) {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.screenH),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = AppSpacing.lg,
                            vertical = AppSpacing.section,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = analysis.profileLabel,
                                style = MaterialTheme.typography.headlineMedium,
                                color = AppAccent,
                                fontWeight = FontWeight.Bold,
                            )
                            // 置信度为 0 表示后端未提供该值（旧记录），此时不展示，避免出现"置信度 0%"这种与正文矛盾的文案
                            if (analysis.confidence > 0f) {
                                Spacer(modifier = Modifier.height(AppSpacing.xs))
                                Text(
                                    text = "置信度 ${(analysis.confidence * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTextTertiary,
                                )
                            }
                        }
                    }
                }

                // === 维度雷达图 ===
                if (analysis.dimensionAnalyses.isNotEmpty() || analysis.dimensionScores.isNotEmpty()) {
                    SectionTitle("维度总览")
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.screenH),
                    ) {
                        val chartData = if (analysis.dimensionAnalyses.isNotEmpty()) {
                            analysis.dimensionAnalyses.map { Triple(it.key, it.label, it.score) }
                        } else {
                            analysis.dimensionScores.map { (k, v) -> Triple(k, dimensionNames[k] ?: k, v) }
                        }
                        DimensionRadarChart(dimensions = chartData, modifier = Modifier.padding(AppSpacing.md))
                    }
                }

                // === 依恋类型解读 ===
                if (analysis.profileAnalysis.isNotBlank()) {
                    SectionTitle("依恋类型解读")
                    BodyCard(analysis.profileAnalysis)
                }

                // === 逐维度解读 ===
                if (analysis.dimensionAnalyses.isNotEmpty()) {
                    SectionTitle("维度详细解读")
                    analysis.dimensionAnalyses.forEach { dim ->
                        DimensionAnalysisCard(dim)
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                    }
                }

                // === 关系优势 ===
                if (analysis.strengths.isNotBlank()) {
                    SectionTitle("你的关系优势")
                    HighlightCard(text = analysis.strengths, icon = Icons.Outlined.FitnessCenter)
                }

                // === 成长建议 ===
                if (analysis.growthTips.isNotEmpty()) {
                    SectionTitle("成长建议")
                    analysis.growthTips.forEachIndexed { index, tip ->
                        TipCard(number = index + 1, text = tip)
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                    }
                }

                // === 沟通指南 ===
                if (analysis.communicationGuide.isNotBlank()) {
                    SectionTitle("沟通指南")
                    BodyCard(analysis.communicationGuide)
                }

                // === 兜底：旧版纯文本分析 ===
                if (analysis.profileAnalysis.isBlank() && analysis.analysis.isNotBlank()) {
                    SectionTitle("分析报告")
                    BodyCard(analysis.analysis)
                }

                Spacer(modifier = Modifier.height(AppSpacing.block))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.screenH),
                ) {
                    AppSecondaryButton(
                        text = "查看完整关系画像",
                        onClick = onNavigateToProfile,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (uiState.coupleProfileReady) {
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        AppAccentButton(
                            text = "查看情侣组合画像",
                            onClick = onNavigateToCoupleProfile,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    AppAccentButton(
                        text = "返回首页",
                        onClick = onNavigateBack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.block))
            }
        }
    }
}

// ==================== 局部组件 ====================

/**
 * 量表分析的流式过程视图。
 *
 * 三块内容按出现顺序排列：思考面板（可折叠）→ 正文打字机 → 「正在整理要点」。
 * 停止按钮常驻：一次分析要跑十几秒，用户随时有权叫停；停下后已收到的半截
 * 正文仍然留在屏幕上可读，不会被清空。
 */
@Composable
private fun AnalysisStreamingView(
    thinkingText: String,
    isThinking: Boolean,
    thinkingSeconds: Int,
    streamText: String,
    isStructuring: Boolean,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenH),
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.lg))

        Text(
            text = "AI 正在解读你的测评结果",
            style = MaterialTheme.typography.titleMedium,
            color = AppTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        AiThinkingPanel(
            thinking = thinkingText,
            isLive = isThinking,
            seconds = thinkingSeconds,
        )

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(AppSpacing.lg)) {
                if (streamText.isBlank()) {
                    AiWaitingBubble()
                } else {
                    AiStreamingText(content = streamText, isStreaming = true)
                }
                if (isStructuring) {
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    AiStructuringHint()
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.section))

        TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text(
                "停止分析",
                color = AppTextSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.block))
    }
}


/** Markdown 正文卡（正文由 Markwon 渲染，保留原有的富文本能力）。 */
@Composable
private fun BodyCard(text: String) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
    ) {
        val textColor = AppTextPrimary
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.TextView(ctx).apply {
                    this.setTextColor(textColor.toArgb())
                    this.textSize = 15f
                    this.setLineSpacing(0f, 1.4f)
                }
            },
            update = { textView ->
                val markwon = Markwon.create(textView.context)
                markwon.setMarkdown(textView, text.trim())
                textView.setTextColor(textColor.toArgb())
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
        )
    }
}

/** 强调卡：品牌浅色底 + 图标 + 一句话优势。 */
@Composable
private fun HighlightCard(text: String, icon: ImageVector) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
        containerColor = AppAccentLight,
    ) {
        Row(modifier = Modifier.padding(AppSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Text(
                text = text.trim(),
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DimensionAnalysisCard(dim: QuestionnaireDto.DimensionAnalysis) {
    // 高/中/低是数据分级色，语义上正好对应色板里的 error / warning / success，
    // 三者已按深浅色各调过一档。写死色值（0xFFFF6B6B 等）在深色模式下是刺眼荧光。
    val levelColor = when (dim.level) {
        "高" -> AppErrorRed
        "中" -> AppWarning
        "低" -> AppSuccessGreen
        else -> AppAccent
    }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dim.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.xs))
                        .background(levelColor.copy(alpha = 0.15f))
                        .padding(horizontal = AppSpacing.sm, vertical = 3.dp),
                ) {
                    Text(
                        text = dim.level,
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text(
                    text = "${dim.score.toInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppAccent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        // 分值条（AppScoreBar：进入动画 + 统一轨道/圆角，替代手写 Box 条）
        AppScoreBar(
            score = dim.score,
            color = levelColor,
            trackColor = AppBorderLight,
            height = 6.dp,
        )

        if (dim.analysis.isNotBlank()) {
            Spacer(modifier = Modifier.height(AppSpacing.md))
            val dimTextColor = AppTextSecondary
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.TextView(ctx).apply {
                        this.setTextColor(dimTextColor.toArgb())
                        this.textSize = 14f
                        this.setLineSpacing(0f, 1.3f)
                    }
                },
                update = { textView ->
                    val markwon = Markwon.create(textView.context)
                    markwon.setMarkdown(textView, dim.analysis)
                    textView.setTextColor(dimTextColor.toArgb())
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TipCard(number: Int, text: String) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
    ) {
        Row(modifier = Modifier.padding(AppSpacing.md), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AppAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$number",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppOnAccent,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
