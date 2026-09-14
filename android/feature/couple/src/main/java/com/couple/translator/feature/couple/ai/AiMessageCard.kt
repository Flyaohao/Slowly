package com.couple.translator.feature.couple.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiMessageCard(
    content: String,
    structuredOutput: AiDto.StructuredOutput?,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    if (structuredOutput == null) {
        Box(
            modifier = Modifier
                .widthIn(max = screenWidth * 0.85f)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(Surface)
                .padding(16.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
        }
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = screenWidth * 0.9f),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            structuredOutput.summary?.let { summary ->
                CollapsibleSection(title = "摘要", defaultExpanded = true) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                    )
                }
            }

            structuredOutput.emotionValidation?.let { emotion ->
                CollapsibleSection(title = "情绪确认") {
                    Text(
                        text = emotion,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            structuredOutput.partnerPossibleMeaning?.let { meaning ->
                CollapsibleSection(title = "对方可能含义") {
                    Text(
                        text = meaning,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            structuredOutput.suggestedReply?.let { reply ->
                CollapsibleSection(title = "建议回复") {
                    Surface(
                        color = AccentLight,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = reply,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Accent,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            structuredOutput.doNotSay?.let { doNotSay ->
                CollapsibleSection(title = "避免说的话") {
                    Surface(
                        color = com.couple.translator.core.ui.theme.ErrorRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = doNotSay,
                            style = MaterialTheme.typography.bodyLarge,
                            color = com.couple.translator.core.ui.theme.ErrorRed,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            structuredOutput.nextStep?.let { nextStep ->
                CollapsibleSection(title = "下一步建议") {
                    Text(
                        text = nextStep,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            // 信件解读（letter_understand）：这是聊天场景，与信件页的
            // letter_analysis 场景不是一回事——后者由 LetterDto 承接。
            // 此前客户端完全没有这三个字段，选中"信件解读"会渲染出空卡片。
            structuredOutput.surfaceMeaning?.let { surface ->
                CollapsibleSection(title = "字面意思") {
                    Text(
                        text = surface,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            structuredOutput.underlyingNeed?.let { need ->
                CollapsibleSection(title = "背后的需求") {
                    Text(
                        text = need,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            structuredOutput.emotionTone?.let { tone ->
                CollapsibleSection(title = "情绪基调") {
                    Text(
                        text = tone,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            structuredOutput.rewrites?.let { rewrites ->
                if (rewrites.isNotEmpty()) {
                    CollapsibleSection(title = "改写版本") {
                        rewrites.forEach { rewrite ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(
                                    text = rewrite.style,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Accent,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = AccentLight.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = rewrite.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            // 理论溯源：模型在 private_advisor / partner_translate 场景会给出本建议
            // 参考的心理学理论（如 Gottman 四骑士、依恋理论）。此前该字段被后端的
            // 二次白名单裁掉、客户端也没有对应字段，整条链路丢失；现已打通。
            structuredOutput.theoryRefs?.let { refs ->
                if (refs.isNotEmpty()) {
                    CollapsibleSection(title = "参考理论") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            refs.forEach { ref ->
                                Surface(
                                    color = BorderLight,
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Text(
                                        text = ref,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 6.dp,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Accent,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                content()
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (!expanded) {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
