package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppListItem
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
 * 关系复盘页（整改 §8.7）。
 *
 * 对应功能设计 六.9：把一次争吵 / 冷战 / 和好的经过讲给 AI，
 * 它给出触发点、双方真实需求、误解发生处、升级与降温话术、下次可用的表达。
 *
 * 整改修掉的两处真实缺陷：
 * 1. 「重新复盘一次」原来只清空两个用户看不见的输入框、结果卡片原地不动
 *    —— 现在走 [ReviewViewModel.restartReview]，清结果 + 恢复原文 + 强制重置输入框；
 * 2. 只有最新一条能看（`ai_generation` 覆盖式），历史无从回看
 *    —— 现在 [ReviewMode.HISTORY] 是真实列表，点进去按 id 回读。
 *
 * 这是二级页（挂在根 NavHost 上、外面没有壳层 Scaffold 兜 inset），
 * 所以顶栏必须自带状态栏 inset，否则会压在状态栏下面。
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenReview: (Long) -> Unit = {},
    /** >0 = 直接看这一条留档（历史列表 / 首页回访卡带进来） */
    reviewId: Long = 0L,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // 入口参数只喂一次（VM 内部用 initialized 挡重复），之后由用户操作驱动
    LaunchedEffect(reviewId) { viewModel.initialize(reviewId) }

    // 历史列表是独立页面，导航意图由 VM 上报、这里消费——只跳一次
    LaunchedEffect(uiState.navigateToHistory) {
        if (uiState.navigateToHistory) {
            viewModel.consumeNavigateToHistory()
            onOpenHistory()
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onBack,
                title = "关系复盘",
                subtitle = uiState.meta?.eventTime?.take(10)?.takeIf { it.isNotBlank() }
                    ?: "把这次的事讲清楚，看看下次怎么说",
                trailing = {
                    IconButton(onClick = viewModel::openHistory) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = "历史复盘",
                            tint = AppTextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
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
            ReviewLatestHint(uiState = uiState, onOpenReview = onOpenReview)
            ReviewInputSection(
                uiState = uiState,
                viewModel = viewModel,
                onOpenHistory = viewModel::openHistory,
            )
            ReviewStreamSection(uiState = uiState, viewModel = viewModel)
            ReviewResultSection(uiState = uiState, viewModel = viewModel)
            ReviewOutcomeSection(uiState = uiState, viewModel = viewModel)
            ReviewActionsSection(uiState = uiState, viewModel = viewModel)

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 无参入口顶部那条「最近一次」入口。
 *
 * 只在输入表单态出现：用户是来写新的，但也可能只是想看看上次说到哪了，
 * 一次点击就能进；不点它也不占地方。结果态下不显示（已经在看一条复盘了）。
 */
@Composable
private fun ReviewLatestHint(uiState: ReviewUiState, onOpenReview: (Long) -> Unit) {
    val latest = uiState.latest ?: return
    if (uiState.mode != ReviewMode.INPUT || uiState.isGenerating) return

    AppCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        AppListItem(
            title = latest.summary.ifBlank { "最近一次复盘" },
            subtitle = buildString {
                latest.eventTime?.take(10)?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (isNotEmpty()) append(" · ")
                append(if (latest.outcome.isNullOrBlank()) "还没有结果" else "已记录结果")
            },
            showChevron = true,
            onClick = { onOpenReview(latest.reviewId) },
        )
    }
}

// ---------------------------------------------------------------------- #
// 输入
// ---------------------------------------------------------------------- #

@Composable
private fun ReviewInputSection(
    uiState: ReviewUiState,
    viewModel: ReviewViewModel,
    onOpenHistory: () -> Unit,
) {
    if (!uiState.showForm) return

    // 「重新复盘一次」必须是**真的重来**：输入框要按新的初值重建，
    // 而 Compose 的 TextField 只在首个组合时读 value，之后由用户输入接管
    // （VM 里的 description/context 更新不会再回流到输入框）。因此每次
    // attempt 自增都换一次 key，强制它用新的初值重新组一遍。
    key(uiState.replayAttempt) {
        // 初值只在本次 attempt 内取一次；之后由用户输入接管，并镜像进 VM
        val initial = remember { uiState.replay }
        var description by remember { mutableStateOf(initial.description) }
        var context by remember { mutableStateOf(initial.context) }
        // 发生时间不进重放快照：它是「这次是什么时候」，跟复盘内容无关
        var eventTime by remember { mutableStateOf("") }

        Column {
            TextInputField(
                value = description,
                onValueChange = {
                    description = it
                    viewModel.onDescriptionChange(it)
                },
                label = "发生了什么",
                placeholder = "比如：昨晚因为回消息慢吵起来了，我说她不在乎我，她说我太黏人，然后两个人都没再说话…",
                enabled = !uiState.isGenerating,
                minLines = 5,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextInputField(
                value = context,
                onValueChange = {
                    context = it
                    viewModel.onContextChange(it)
                },
                label = "补充背景（可选）",
                placeholder = "最近是不是本来就压力大、以前有没有类似的情况…",
                enabled = !uiState.isGenerating,
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextInputField(
                value = eventTime,
                onValueChange = {
                    eventTime = it
                    viewModel.onEventTimeChange(it)
                },
                label = "事情发生的时间（可选）",
                placeholder = "YYYY-MM-DD",
                enabled = !uiState.isGenerating,
                minLines = 1,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppPrimaryButton(
                    text = "开始复盘",
                    onClick = viewModel::startReview,
                    enabled = !uiState.isGenerating && description.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
                AppSecondaryButton(
                    text = "历史复盘",
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- #
// 流式过程
// ---------------------------------------------------------------------- #

@Composable
private fun ReviewStreamSection(uiState: ReviewUiState, viewModel: ReviewViewModel) {
    if (uiState.error.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = uiState.error,
            style = MaterialTheme.typography.bodyMedium,
            color = AppErrorRed,
        )
    }

    if (uiState.message.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = uiState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
        )
    }

    if (!uiState.isGenerating) return

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

// ---------------------------------------------------------------------- #
// 结果
// ---------------------------------------------------------------------- #

@Composable
private fun ReviewResultSection(uiState: ReviewUiState, viewModel: ReviewViewModel) {
    if (uiState.mode != ReviewMode.RESULT || uiState.isGenerating) return

    // 历史回看时把「当时发生了什么」放在最上面：复盘卡片讲的是 AI 的结论，
    // 没有原文用户会记不清这条说的是哪一次。只在留档里带来原文时显示，
    // 刚生成完那一次不该把用户刚写的东西再抄一遍给他看。
    val description = uiState.meta?.description.orEmpty()
    if (uiState.reviewId > 0L && description.isNotBlank()) {
        Spacer(modifier = Modifier.height(16.dp))
        SectionCard(title = "当时发生了什么", body = description)
    }

    // 模型没吐出结构化 JSON 时至少把正文留下来，别让页面空着
    val review = uiState.review
    if (review == null) {
        if (uiState.savedContent.isNotBlank()) {
            Spacer(modifier = Modifier.height(20.dp))
            AppMarkdownText(markdown = uiState.savedContent, color = AppTextPrimary)
        } else if (uiState.reviewId > 0L) {
            Spacer(modifier = Modifier.height(20.dp))
            AppEmptyState(
                icon = Icons.Outlined.Info,
                title = "这条复盘没有留下结果",
                subtitle = "可能是生成中途断开了，重新复盘一次试试",
                action = {
                    AppPrimaryButton(text = "重新复盘一次", onClick = viewModel::restartReview)
                },
            )
        }
        return
    }

    Spacer(modifier = Modifier.height(20.dp))
    ReviewResultCards(review = review)
}

// ---------------------------------------------------------------------- #
// 后续结果（§8.7「后续结果」+ §8.7 回访）
// ---------------------------------------------------------------------- #

@Composable
private fun ReviewOutcomeSection(uiState: ReviewUiState, viewModel: ReviewViewModel) {
    // 只对**已留档**的复盘问「后来怎么样了」：还在流式/刚生成完那一瞬，
    // 后端刚排的回访时间还没回来，问了也没地方存。
    if (uiState.mode != ReviewMode.RESULT || uiState.isGenerating) return
    if (uiState.reviewId <= 0L) return

    Spacer(modifier = Modifier.height(8.dp))
    AppCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = "后来怎么样了",
            style = MaterialTheme.typography.titleSmall,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (uiState.hasOutcome) {
            Text(
                text = uiState.meta?.outcome.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "可以随时改，改完回访任务不会再来打扰你",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextSecondary,
            )
        } else {
            Text(
                text = "记下来，下次复盘时能对上：",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutcomeInput(uiState = uiState, viewModel = viewModel)
    }
}

@Composable
private fun OutcomeInput(uiState: ReviewUiState, viewModel: ReviewViewModel) {
    // 已填过结果时把原文放进输入框，用户改的是它而不是从空白重写
    val seed = uiState.meta?.outcome?.takeIf { it.isNotBlank() } ?: uiState.outcome
    key(seed) {
        var text by remember { mutableStateOf(seed) }
        Column {
            TextInputField(
                value = text,
                onValueChange = {
                    text = it
                    viewModel.onOutcomeChange(it)
                },
                label = "实际结果 / 对方的反应",
                placeholder = "比如：第二天我照着你说的开了口，她愣了一下就笑了",
                enabled = !uiState.isSubmittingOutcome,
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(12.dp))
            AppPrimaryButton(
                text = when {
                    uiState.isSubmittingOutcome -> "提交中…"
                    uiState.hasOutcome -> "更新结果"
                    else -> "记录结果"
                },
                onClick = viewModel::submitOutcome,
                enabled = !uiState.isSubmittingOutcome && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------- #
// 底部动作
// ---------------------------------------------------------------------- #

@Composable
private fun ReviewActionsSection(uiState: ReviewUiState, viewModel: ReviewViewModel) {
    if (uiState.isGenerating) return
    if (uiState.mode != ReviewMode.RESULT) return
    if (uiState.review == null && uiState.savedContent.isBlank()) return

    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppSecondaryButton(
            text = "重新复盘一次",
            onClick = viewModel::restartReview,
            modifier = Modifier.weight(1f),
        )
        AppSecondaryButton(
            text = "历史复盘",
            onClick = viewModel::openHistory,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------- #
// 卡片
// ---------------------------------------------------------------------- #

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
