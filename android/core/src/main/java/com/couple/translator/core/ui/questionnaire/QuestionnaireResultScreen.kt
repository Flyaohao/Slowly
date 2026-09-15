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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.couple.translator.core.ui.components.DimensionRadarChart
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
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
        topBar = {
            TopAppBar(
                title = { Text("测评结果") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        // Loading
        if (uiState.isLoading || uiState.isAnalyzing) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), color = AppAccent)
                Spacer(modifier = Modifier.height(16.dp))
                Text("AI 正在分析你的测评结果...", style = MaterialTheme.typography.bodyLarge, color = AppTextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Text("这可能需要 10-30 秒，请耐心等待", style = MaterialTheme.typography.bodySmall, color = AppTextTertiary)
            }
            return@PullToRefreshLayout
        }

        // Error with retry
        if (uiState.error.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("分析生成失败", style = MaterialTheme.typography.headlineMedium, color = AppTextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(uiState.error, style = MaterialTheme.typography.bodyMedium, color = AppTextTertiary, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                PrimaryButton(text = "重新分析", onClick = { viewModel.retry() })
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onNavigateBack) { Text("返回首页") }
            }
            return@PullToRefreshLayout
        }

        val analysis = uiState.analysis
        if (analysis == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无分析结果", style = MaterialTheme.typography.headlineMedium, color = AppTextSecondary)
                Spacer(modifier = Modifier.height(24.dp))
                PrimaryButton(text = "重新分析", onClick = { viewModel.retry() })
            }
            return@PullToRefreshLayout
        }

        // Main content
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // === Profile type card ===
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(500, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(500, easing = FastOutSlowInEasing)) { it / 6 },
            ) {
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
                        Text(analysis.profileLabel, style = MaterialTheme.typography.headlineMedium, color = AppAccent, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("置信度 ${(analysis.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = AppTextTertiary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Radar chart ===
            if (analysis.dimensionAnalyses.isNotEmpty() || analysis.dimensionScores.isNotEmpty()) {
                SectionTitle("维度总览")
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    val chartData = if (analysis.dimensionAnalyses.isNotEmpty()) {
                        analysis.dimensionAnalyses.map { Triple(it.key, it.label, it.score) }
                    } else {
                        analysis.dimensionScores.map { (k, v) -> Triple(k, dimensionNames[k] ?: k, v) }
                    }
                    DimensionRadarChart(dimensions = chartData, modifier = Modifier.padding(12.dp))
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // === Profile analysis text ===
            if (analysis.profileAnalysis.isNotBlank()) {
                SectionTitle("依恋类型解读")
                Spacer(modifier = Modifier.height(12.dp))
                BodyCard(analysis.profileAnalysis)
                Spacer(modifier = Modifier.height(24.dp))
            }

            // === Per-dimension analysis cards ===
            if (analysis.dimensionAnalyses.isNotEmpty()) {
                SectionTitle("维度详细解读")
                Spacer(modifier = Modifier.height(12.dp))
                analysis.dimensionAnalyses.forEach { dim ->
                    DimensionAnalysisCard(dim)
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // === Strengths ===
            if (analysis.strengths.isNotBlank()) {
                SectionTitle("你的关系优势")
                Spacer(modifier = Modifier.height(12.dp))
                HighlightCard(text = analysis.strengths, icon = "💪")
                Spacer(modifier = Modifier.height(24.dp))
            }

            // === Growth tips ===
            if (analysis.growthTips.isNotEmpty()) {
                SectionTitle("成长建议")
                Spacer(modifier = Modifier.height(12.dp))
                analysis.growthTips.forEachIndexed { index, tip ->
                    TipCard(number = index + 1, text = tip)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // === Communication guide ===
            if (analysis.communicationGuide.isNotBlank()) {
                SectionTitle("沟通指南")
                Spacer(modifier = Modifier.height(12.dp))
                BodyCard(analysis.communicationGuide)
                Spacer(modifier = Modifier.height(24.dp))
            }

            // === Fallback: old analysis text ===
            if (analysis.profileAnalysis.isBlank() && analysis.analysis.isNotBlank()) {
                SectionTitle("分析报告")
                Spacer(modifier = Modifier.height(12.dp))
                BodyCard(analysis.analysis)
                Spacer(modifier = Modifier.height(24.dp))
            }

            // === Action buttons ===
            OutlinedButton(onClick = onNavigateToProfile, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("查看完整关系画像")
            }

            if (uiState.coupleProfileReady) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onNavigateToCoupleProfile, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AppAccent)) {
                    Text("查看情侣组合画像")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AppAccent)) {
                Text("返回首页")
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
        }
    }
}

// ==================== Components ====================

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun BodyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(14.dp),
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun HighlightCard(text: String, icon: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppAccentLight),
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
private fun DimensionAnalysisCard(dim: QuestionnaireDto.DimensionAnalysis) {
    val levelColor = when (dim.level) {
        "高" -> Color(0xFFFF6B6B)
        "中" -> Color(0xFFFFA726)
        "低" -> Color(0xFF66BB6A)
        else -> AppAccent
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // Header: name + score badge
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
                    Text("${dim.score.toInt()}", style = MaterialTheme.typography.titleMedium, color = AppAccent, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Score bar
            Box(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppBorderLight),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (dim.score / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AppAccent),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // AI analysis text (Markdown)
            if (dim.analysis.isNotBlank()) {
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
                modifier = Modifier.size(24.dp).clip(CircleShape).background(AppAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary, modifier = Modifier.weight(1f))
        }
    }
}
