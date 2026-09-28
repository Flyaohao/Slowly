package com.couple.translator.core.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiStructuringHint
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.DimensionRadarChart
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppOnAccent
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.AppTrack
import io.noties.markwon.Markwon

/** internal：同包 UnderstandingScreen（三合一页）复用同一套维度中文名。 */
internal val dimensionNames = mapOf(
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
fun ProfileResultScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCoupleProfile: () -> Unit,
    isCoupleMode: Boolean = true,
    viewModel: ProfileResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "我的关系画像",
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                SkeletonPageHeader()
                Spacer(modifier = Modifier.height(AppSpacing.section))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(120.dp))
                Spacer(modifier = Modifier.height(AppSpacing.section))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(200.dp))
            }
            return@PullToRefreshLayout
        }

        if (uiState.submissions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.screenH),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppEmptyState(
                    icon = Icons.Outlined.Quiz,
                    title = "还没有关系画像",
                    subtitle = "完成问卷后，AI 会根据你们的回答生成专属画像",
                    action = {
                        AppPrimaryButton(text = "去完成问卷", onClick = onNavigateBack)
                    },
                )
            }
            return@PullToRefreshLayout
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // === 测评选择器 ===
            SubmissionSelector(
                submissions = uiState.submissions,
                selectedId = uiState.selectedSubmissionId,
                onSelect = { viewModel.selectSubmission(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // === 画像类型卡片 ===
            uiState.profile?.let { profile ->
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AppSurface,
                    shape = RoundedCornerShape(AppRadius.xl),
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = uiState.profileTypeName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = AppAccent,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.profileTypeDescription,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppTextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === AI 分析报告（Markdown） ===
            if (uiState.hasAnalysis) {
                Text(
                    text = "AI 分析报告",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 流式生成中：思考面板 + 正文打字机。
                // done 帧到达后 isGenerating 归位，下面那套结构化卡片自动接手渲染。
                if (uiState.isGenerating) {
                    AiThinkingPanel(
                        thinking = uiState.thinkingText,
                        isLive = uiState.isThinking,
                        seconds = uiState.thinkingSeconds,
                    )
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppSurface,
                        shape = RoundedCornerShape(AppRadius.xl),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        if (uiState.streamText.isBlank()) {
                            AiWaitingBubble()
                        } else {
                            AiStreamingText(content = uiState.streamText, isStreaming = true)
                        }
                        if (uiState.isStructuring) {
                            Spacer(modifier = Modifier.height(8.dp))
                            AiStructuringHint()
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.stopAnalysis() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("停止生成", color = AppTextSecondary)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // profile_analysis（生成中留空，免得与上面的打字机内容重复一遍）
                if (!uiState.isGenerating && uiState.profileAnalysis.isNotBlank()) {
                    MarkdownCard(uiState.profileAnalysis)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // dimension_analyses
                if (uiState.dimensionAnalyses.isNotEmpty()) {
                    Text(
                        text = "维度详细解读",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.dimensionAnalyses.forEach { dim ->
                        DimensionAnalysisCard(dim)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // strengths
                if (uiState.strengths.isNotBlank()) {
                    Text(
                        text = "你的关系优势",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HighlightCard(text = uiState.strengths, icon = Icons.Outlined.FitnessCenter)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // growth_tips
                if (uiState.growthTips.isNotEmpty()) {
                    Text(
                        text = "成长建议",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.growthTips.forEachIndexed { index, tip ->
                        TipCard(number = index + 1, text = tip)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // communication_guide
                if (uiState.communicationGuide.isNotBlank()) {
                    Text(
                        text = "沟通指南",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MarkdownCard(uiState.communicationGuide)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === 雷达图 ===
            if (uiState.dimensions.isNotEmpty()) {
                Text(
                    text = "维度总览",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AppSurface,
                    shape = RoundedCornerShape(AppRadius.xl),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    val chartData = uiState.dimensions.map { dim ->
                        Triple(dim.dimensionKey, dimensionNames[dim.dimensionKey] ?: dim.dimensionKey, dim.score)
                    }
                    DimensionRadarChart(dimensions = chartData, modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(24.dp))

                // 维度详情
                Text(
                    text = "维度详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                uiState.dimensions.sortedByDescending { it.score }.forEach { dimension ->
                    DimensionScoreItem(
                        name = dimensionNames[dimension.dimensionKey] ?: dimension.dimensionKey,
                        score = dimension.score,
                        explanation = dimension.explanation,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === AI 深度画像报告（独立流式长文，基于 11 维画像 + 依恋类型生成）===
            Text(
                text = "AI 深度画像报告",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                // 生成中：思考面板 + 正文打字机
                uiState.isReportGenerating -> {
                    AiThinkingPanel(
                        thinking = uiState.reportThinkingText,
                        isLive = uiState.isReportThinking,
                        seconds = uiState.reportThinkingSeconds,
                    )
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppSurface,
                        shape = RoundedCornerShape(AppRadius.xl),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        if (uiState.reportStreamText.isBlank()) {
                            AiWaitingBubble()
                        } else {
                            AiStreamingText(content = uiState.reportStreamText, isStreaming = true)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.stopProfileReport() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("停止生成", color = AppTextSecondary)
                    }
                }

                // 已有报告（回读或刚生成完）
                uiState.reportText.isNotBlank() -> {
                    MarkdownCard(uiState.reportText)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.startProfileReport() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重新生成报告", color = AppTextSecondary)
                    }
                }

                // 回读完成且从未生成过：给入口
                uiState.reportLoaded -> {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppSurface,
                        shape = RoundedCornerShape(AppRadius.xl),
                        contentPadding = PaddingValues(20.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "结合你的依恋类型与 11 个关系维度，生成一份更完整的深度解读",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AppPrimaryButton(
                                text = "生成 AI 深度报告",
                                onClick = { viewModel.startProfileReport() },
                            )
                        }
                    }
                }

                // 回读尚未完成：先不渲染，避免回读慢时出现可重复点按的生成入口
                else -> Unit
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isCoupleMode) {
                AppSecondaryButton(
                    text = "查看情侣组合画像",
                    onClick = onNavigateToCoupleProfile,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
        }
    }
}

// ==================== 测评选择器 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubmissionSelector(
    submissions: List<com.couple.translator.core.data.model.QuestionnaireDto.SubmissionResponse>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = submissions.find { it.id == selectedId }
    val displayText = selected?.let {
        "${it.createdAt?.take(10) ?: ""} · ${it.profileType ?: ""}"
    } ?: "选择测评"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("选择测评记录") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppAccent,
                focusedLabelColor = AppAccent,
            ),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            submissions.forEach { sub ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(sub.createdAt?.take(10) ?: "", style = MaterialTheme.typography.bodyMedium)
                            Text(sub.questionnaireTitle, style = MaterialTheme.typography.bodySmall, color = AppTextTertiary)
                        }
                    },
                    onClick = {
                        onSelect(sub.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ==================== Markdown 组件 ====================

@Composable
private fun MarkdownCard(markdown: String) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = AppSurface,
        contentPadding = PaddingValues(16.dp),
    ) {
        val textColor = AppTextPrimary
        AndroidView(
            factory = { ctx ->
                android.widget.TextView(ctx).apply {
                    this.setTextColor(textColor.toArgb())
                    this.textSize = 15f
                    this.setLineSpacing(0f, 1.4f)
                }
            },
            update = { textView ->
                val markwon = Markwon.create(textView.context)
                markwon.setMarkdown(textView, markdown.trim())
                textView.setTextColor(textColor.toArgb())
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HighlightCard(text: String, icon: ImageVector) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = AppAccentLight,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text.trim(),
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TipCard(number: Int, text: String) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = AppSurface,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .width(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = MaterialTheme.typography.labelSmall, color = AppOnAccent, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DimensionAnalysisCard(dim: QuestionnaireDto.DimensionAnalysis) {
    val levelColor = when (dim.level) {
        "高" -> androidx.compose.ui.graphics.Color(0xFFFF6B6B)
        "中" -> androidx.compose.ui.graphics.Color(0xFFFFA726)
        "低" -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
        else -> AppAccent
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = AppSurface,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dim.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTextPrimary, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(levelColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(dim.level, style = MaterialTheme.typography.labelSmall, color = levelColor, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${dim.score.toInt()}", style = MaterialTheme.typography.titleMedium, color = AppAccent, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppTrack),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (dim.score / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AppAccent),
                )
            }

            if (dim.analysis.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                val dimTextColor = AppTextSecondary
                AndroidView(
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
}

// ==================== 基础组件 ====================

@Composable
private fun DimensionScoreItem(
    name: String,
    score: Float,
    explanation: String?,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = AppSurface,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${score.toInt()}", style = MaterialTheme.typography.titleMedium, color = AppAccent, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(AppTrack),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = score / 100f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppAccent),
                )
            }
            if (!explanation.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = AppTextTertiary)
            }
        }
    }
}
