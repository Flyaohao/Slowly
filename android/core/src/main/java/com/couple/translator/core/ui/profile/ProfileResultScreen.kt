package com.couple.translator.core.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.couple.translator.core.ui.components.DimensionRadarChart
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.cor0e.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
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
fun ProfileResultScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCoupleProfile: () -> Unit,
    isCoupleMode: Boolean = true,
    viewModel: ProfileResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的关系画像") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                ),
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        if (uiState.isLoading) {
            LoadingIndicator()
            return@PullToRefreshLayout
        }

        if (uiState.submissions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有关系画像",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "完成问卷后，AI 会根据你们的回答生成专属画像",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextTertiary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                PrimaryButton(text = "去完成问卷", onClick = onNavigateBack)
            }
            return@PullToRefreshLayout
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = uiState.profileTypeName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Accent,
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
                )
                Spacer(modifier = Modifier.height(12.dp))

                // profile_analysis
                if (uiState.profileAnalysis.isNotBlank()) {
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
                    HighlightCard(text = uiState.strengths, icon = "💪")
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
                )
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    val chartData = uiState.dimensions.map { dim ->
                        Triple(dim.dimensionKey, dimensionNames[dim.dimensionKey] ?: dim.dimensionKey, dim.score)
                    }
                    DimensionRadarChart(dimensions = chartData, modifier = Modifier.padding(16.dp))
                }
                Spacer(modifier = Modifier.height(24.dp))

                // 维度详情
                Text(
                    text = "维度详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
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

            if (isCoupleMode) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onNavigateToCoupleProfile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("查看情侣组合画像")
                }
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
                focusedBorderColor = Accent,
                focusedLabelColor = Accent,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(14.dp),
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun HighlightCard(text: String, icon: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AccentLight),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Text(icon, style = MaterialTheme.typography.titleLarge)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .width(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
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
        else -> Accent
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dim.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                    Text("${dim.score.toInt()}", style = MaterialTheme.typography.titleMedium, color = Accent, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppBorderLight),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (dim.score / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Accent),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${score.toInt()}", style = MaterialTheme.typography.titleMedium, color = Accent, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(AppBorderLight),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = score / 100f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Accent),
                )
            }
            if (!explanation.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = AppTextTertiary)
            }
        }
    }
}
