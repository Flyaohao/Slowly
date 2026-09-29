package com.couple.translator.feature.couple.letter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.components.AiStructuringHint
import com.couple.translator.core.ui.components.AiStreamingText
import com.couple.translator.core.ui.components.AiThinkingPanel
import com.couple.translator.core.ui.components.AiWaitingBubble
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppDivider
import com.couple.translator.core.ui.components.AppMarkdownText
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.feature.couple.data.model.LetterDto

/**
 * 「AI 理解」结果卡片。同时承担四种形态：
 *
 * | 形态 | 触发条件 | 呈现 |
 * | --- | --- | --- |
 * | 思考中 | [isThinking] | 思考面板展开滚动，正文位置是占位气泡 |
 * | 正文流式 | [isStreaming] 且有 [streamContent] | 逐字正文 + 光标 |
 * | 整理中 | [isStructuring] | 正文定格，提示「正在整理要点」 |
 * | 已完成 | [understanding] 非空 | 正文（Markdown）+ 解读要点卡片 |
 *
 * **为什么完成态要让正文与要点并存**：两者回答的是不同问题。正文是模型这次
 * 分析的原始回复，有完整的推理脉络与语气，用户读它才知道结论是怎么来的；
 * 要点则是同一次调用里抽出来的结构化字段，方便扫读和事后回看。早先的版本
 * 在完成态用要点替换掉正文，等于把模型花掉大半时间写的那段解读扔掉了——
 * 现在两份都留：正文在上（Markdown 渲染），要点在下（结构化渲染）。
 * 中途停止（`structured` 为空）时自然只剩正文，走的是同一条渲染路径。
 *
 * 状态徽标（思考中 / 生成中 / 已中断）是必要的：没有它，用户分不清
 * 「还在跑」和「已经跑完了但内容少」。
 */
@Composable
fun LetterUnderstandingCard(
    understanding: LetterDto.LetterUnderstanding?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    streamContent: String = "",
    thinking: String = "",
    isStreaming: Boolean = false,
    isThinking: Boolean = false,
    isStructuring: Boolean = false,
    thinkingSeconds: Int = 0,
    status: String = "",
    /**
     * 是否展示「回信建议」分组（2026-09-29）。
     * 只有收信人才需要知道怎么回；自己写的信展示它属于语义错位，由调用方按
     * 信件方向传入。默认 true 以兼容既有调用点。
     */
    showReplySuggestions: Boolean = true,
) {
    val badge: Pair<String, Color>? = when {
        isThinking -> "深度思考中" to AppAccent
        isStructuring -> "整理要点中" to AppAccent
        isStreaming -> "生成中" to AppAccent
        status == "interrupted" -> "已中断" to AppTextTertiary
        else -> null
    }

    AppCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = AppAccentLight,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AI 理解",
                style = MaterialTheme.typography.titleSmall,
                color = AppAccent,
            )

            if (badge != null) {
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(text = badge.first, color = badge.second)
            }

            Spacer(modifier = Modifier.weight(1f))

            // 生成中不给关闭入口：关掉之后进度就看不见了，用户会以为卡住
            if (!isStreaming) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        }

        if (thinking.isNotBlank()) {
            AiThinkingPanel(
                thinking = thinking,
                isLive = isThinking,
                seconds = thinkingSeconds,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            // 结果已经出来了：正文（模型原始解读）在上，要点在下，两份都留
            understanding != null -> {
                if (streamContent.isNotBlank()) {
                    AppMarkdownText(
                        markdown = streamContent,
                        color = AppTextSecondary,
                        textSizeSp = 14f,
                    )
                    // 只在真的要渲染要点时才画分隔线，否则正文下面会挂一条孤零零的线
                    if (understanding.hasAnySection(showReplySuggestions)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        AppDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionLabel("解读要点")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                UnderstandingSections(understanding, showReplySuggestions)
            }

            // 正文还在流：逐字显示
            streamContent.isNotBlank() -> AiStreamingText(
                content = streamContent,
                isStreaming = isStreaming && !isStructuring,
                color = AppTextSecondary,
            )

            // 请求已发出，连思考都还没吐出来（通常只有几百毫秒）
            isStreaming -> AiWaitingBubble()
        }

        if (isStructuring) {
            Spacer(modifier = Modifier.height(10.dp))
            AiStructuringHint()
        }
    }
}

/**
 * 结构化要点里是否有任何一段要渲染。
 *
 * 用来决定正文与要点之间要不要画那条分隔线：模型偶尔会整段 JSON 都没吐出
 * （被截断或格式跑偏），这时只有正文可看，多一条线就显得莫名其妙。
 */
private fun LetterDto.LetterUnderstanding.hasAnySection(showReplySuggestions: Boolean): Boolean =
    emotion.isNotEmpty() ||
        keyConcerns.isNotEmpty() ||
        expectedResponse.isNotEmpty() ||
        misunderstandable.isNotEmpty() ||
        (showReplySuggestions && replySuggestions.isNotEmpty())

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = AppAccent,
    )
}

@Composable
private fun UnderstandingSections(
    understanding: LetterDto.LetterUnderstanding,
    showReplySuggestions: Boolean = true,
) {
    if (understanding.emotion.isNotEmpty()) {
        SectionItem(label = "对方情绪", value = understanding.emotion)
    }

    if (understanding.keyConcerns.isNotEmpty()) {
        Text(
            text = "关键关注点",
            style = MaterialTheme.typography.labelMedium,
            color = AppAccent,
        )
        Spacer(modifier = Modifier.height(4.dp))
        understanding.keyConcerns.forEach { concern ->
            Text(
                text = "• $concern",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (understanding.expectedResponse.isNotEmpty()) {
        SectionItem(label = "期待回应", value = understanding.expectedResponse)
    }

    if (understanding.misunderstandable.isNotEmpty()) {
        Text(
            text = "容易误解的句子",
            style = MaterialTheme.typography.labelMedium,
            color = AppAccent,
        )
        Spacer(modifier = Modifier.height(4.dp))
        understanding.misunderstandable.forEach { item ->
            Text(
                text = "\"${item.sentence}\"",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Text(
                text = "→ ${item.note}",
                style = MaterialTheme.typography.bodySmall,
                color = AppAccent,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showReplySuggestions && understanding.replySuggestions.isNotEmpty()) {
        Text(
            text = "回信建议",
            style = MaterialTheme.typography.labelMedium,
            color = AppAccent,
        )
        Spacer(modifier = Modifier.height(4.dp))
        understanding.replySuggestions.forEach { suggestion ->
            Text(
                text = "• $suggestion",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionItem(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppAccent,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
        )
    }
}
