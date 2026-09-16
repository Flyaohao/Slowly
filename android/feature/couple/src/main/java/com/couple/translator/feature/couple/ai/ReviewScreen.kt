package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppMarkdownText
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiStructuringHint
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary

/**
 * 关系复盘页。
 *
 * 对应功能设计 六.9：把一次争吵 / 冷战 / 和好的经过讲给 AI，
 * 它给出触发点、双方真实需求、误解发生处、升级与降温话术、下次可用的表达。
 *
 * 这是二级页（挂在根 NavHost 上、外面没有壳层 Scaffold 兜 inset），
 * 所以顶栏必须自带状态栏 inset，否则会压在状态栏下面。
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onBack,
                title = "关系复盘",
                subtitle = "把这次的事讲清楚，看看下次怎么说",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (uiState.review == null && uiState.savedContent.isBlank()) {
                TextInputField(
                    value = uiState.description,
                    onValueChange = viewModel::onDescriptionChange,
                    label = "发生了什么",
                    placeholder = "比如：昨晚因为回消息慢吵起来了，我说她不在乎我，她说我太黏人，然后两个人都没再说话…",
                    enabled = !uiState.isGenerating,
                    minLines = 5,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextInputField(
                    value = uiState.context,
                    onValueChange = viewModel::onContextChange,
                    label = "补充背景（可选）",
                    placeholder = "最近是不是本来就压力大、以前有没有类似的情况…",
                    enabled = !uiState.isGenerating,
                    minLines = 3,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AppPrimaryButton(
                    text = "开始复盘",
                    onClick = viewModel::startReview,
                    enabled = !uiState.isGenerating && uiState.description.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (uiState.error.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = uiState.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppErrorRed,
                )
            }

            if (uiState.isGenerating) {
                Spacer(modifier = Modifier.height(20.dp))
                AiThinkingPanel(
                    thinking = uiState.thinkingText,
                    isLive = uiState.isThinking,
                    seconds = uiState.thinkingSeconds,
                )
                if (uiState.streamText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    AiStreamingText(content = uiState.streamText, isStreaming = true)
                }
                if (uiState.isStructuring) {
                    Spacer(modifier = Modifier.height(12.dp))
                    AiStructuringHint(text = "正在整理复盘要点…")
                }
                Spacer(modifier = Modifier.height(16.dp))
                AppSecondaryButton(
                    text = "停止生成",
                    onClick = viewModel::stopReview,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 模型没吐出结构化 JSON 时至少把正文留下来，别让页面空着
            if (!uiState.isGenerating && uiState.review == null && uiState.savedContent.isNotBlank()) {
                Spacer(modifier = Modifier.height(20.dp))
                AppMarkdownText(
                    markdown = uiState.savedContent,
                    color = AppTextPrimary,
                )
            }

            uiState.review?.let { review ->
                Spacer(modifier = Modifier.height(20.dp))
                ReviewResultCards(review = review)
            }

            if (uiState.review != null || (!uiState.isGenerating && uiState.savedContent.isNotBlank())) {
                Spacer(modifier = Modifier.height(20.dp))
                AppSecondaryButton(
                    text = "重新复盘一次",
                    onClick = {
                        viewModel.onDescriptionChange("")
                        viewModel.onContextChange("")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ReviewResultCards(review: com.couple.translator.core.data.model.AiDto.ReviewResult) {
    if (review.summary.isNotBlank()) {
        SectionCard(title = "一句话", body = review.summary)
    }
    if (review.trigger.isNotBlank()) {
        SectionCard(title = "真正的触发点", body = review.trigger)
    }
    if (review.ownNeed.isNotBlank()) {
        SectionCard(title = "你真正想要的", body = review.ownNeed)
    }
    if (review.partnerNeed.isNotBlank()) {
        SectionCard(title = "TA 真正想要的", body = review.partnerNeed)
    }
    if (review.misunderstanding.isNotBlank()) {
        SectionCard(title = "误解从哪开始", body = review.misunderstanding)
    }
    if (review.escalationPhrases.isNotEmpty()) {
        SectionCard(title = "把火拱起来的话", items = review.escalationPhrases)
    }
    if (review.deescalationPhrases.isNotEmpty()) {
        SectionCard(title = "当时能降温的说法", items = review.deescalationPhrases)
    }
    if (review.nextTimeScripts.isNotEmpty()) {
        SectionCard(title = "下次可以提前说", items = review.nextTimeScripts)
    }
}

@Composable
private fun SectionCard(
    title: String,
    body: String = "",
    items: List<String> = emptyList(),
) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )
        }
        items.forEach { item ->
            Text(
                text = "· $item",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
