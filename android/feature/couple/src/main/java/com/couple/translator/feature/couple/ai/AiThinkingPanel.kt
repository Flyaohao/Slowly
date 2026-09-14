package com.couple.translator.feature.couple.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary

/**
 * 推理模型的「深度思考」面板。
 *
 * **它解决的是什么问题**：qwen3.7-flash 这类推理模型会先产出几秒到几十秒的
 * 思考内容，正文首帧实测要 18s 以上。此前面板不存在时，用户发完消息后盯着
 * 一片空白（实测 24.67s，占整次请求的 96%），体感像卡死。
 *
 * 现在的行为：
 * - 思考中（[isLive]）：**默认展开**，边收边滚动，所以首个字节后 0.5s 左右
 *   屏幕上就有内容在动，而不是等 24 秒。
 * - 思考结束后：**自动折叠**成一行「已深度思考 N 秒」，点一下仍可展开回看，
 *   不占用正文的阅读空间。
 *
 * 折叠状态用 `remember` 存本地：面板是纯展示件，不值得为它引入一层 UI 状态。
 * 但要注意 [isLive] 变化时要重置回默认态，否则"思考中展开过"会带到结束后。
 */
@Composable
fun AiThinkingPanel(
    thinking: String,
    modifier: Modifier = Modifier,
    isLive: Boolean = false,
    seconds: Int = 0,
    /**
     * 历史消息里的思考过程（[isLive] 为 false 且 [seconds] 为 0）没有耗时数据，
     * 用一个中性标题，不要显示"已深度思考 0 秒"。
     */
    idleLabel: String = "深度思考过程",
) {
    if (thinking.isBlank()) return

    // 思考中默认展开，结束后默认折叠
    var expanded by remember(isLive) { mutableStateOf(isLive) }

    val scrollState = rememberScrollState()
    // 思考中自动滚到底部，让"还在输出"这件事一直可见
    LaunchedEffect(thinking, expanded) {
        if (isLive && expanded) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val title = when {
        isLive -> "正在深度思考"
        seconds > 0 -> "已深度思考 $seconds 秒"
        else -> idleLabel
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThinkingIndicator(isLive = isLive)

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (isLive) Accent else TextSecondary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = thinking.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier
                        // 思考过程动辄数千字，限高避免把正文挤到屏幕外
                        .heightIn(max = 220.dp)
                        .verticalScroll(scrollState),
                )
            }
        }
    }
}

/**
 * 标题左侧的小圆点：思考中呼吸闪烁，结束后变成长亮的实心点。
 *
 * 用透明度动画而不是转圈进度条：它表达的是"有东西在持续产出"，
 * 而 progress 语义上暗示"有一个可预估的完成度"，这里并不存在。
 */
@Composable
private fun ThinkingIndicator(isLive: Boolean) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-alpha",
    )

    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(AccentLight),
        contentAlignment = Alignment.Center,
    ) {
        if (isLive) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(Accent),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * 正文还没到、思考也没吐出来时的占位气泡。
 *
 * 三个点依次呼吸，避免用静态文字显得像卡死。
 */
@Composable
fun AiWaitingBubble(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .background(Surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { index ->
                    val transition = rememberInfiniteTransition(label = "dot$index")
                    val alpha by transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            // 每个点错开相位，形成"波浪"推进感
                            animation = tween(durationMillis = 700, delayMillis = index * 160),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot-alpha$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .alpha(alpha)
                            .clip(CircleShape)
                            .background(TextTertiary),
                    )
                    if (index < 2) Spacer(modifier = Modifier.width(5.dp))
                }
            }
        }
    }
}
