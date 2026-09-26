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
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

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
                .background(AppSurface)
                .padding(16.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
            )
        }
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = screenWidth * 0.9f),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
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
                        color = AppTextPrimary,
                    )
                }
            }

            structuredOutput.emotionValidation?.let { emotion ->
                CollapsibleSection(title = "情绪确认") {
                    Text(
                        text = emotion,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                }
            }

            structuredOutput.partnerPossibleMeaning?.let { meaning ->
                CollapsibleSection(title = "对方可能含义") {
                    Text(
                        text = meaning,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                }
            }

            structuredOutput.suggestedReply?.let { reply ->
                CollapsibleSection(title = "建议回复") {
                    Surface(
                        color = AppAccentLight,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = reply,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppAccent,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            structuredOutput.doNotSay?.let { doNotSay ->
                CollapsibleSection(title = "避免说的话") {
                    Surface(
                        color = com.couple.translator.core.ui.theme.AppErrorRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = doNotSay,
                            style = MaterialTheme.typography.bodyLarge,
                            color = com.couple.translator.core.ui.theme.AppErrorRed,
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
                        color = AppTextSecondary,
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
                        color = AppTextSecondary,
                    )
                }
            }

            structuredOutput.underlyingNeed?.let { need ->
                CollapsibleSection(title = "背后的需求") {
                    Text(
                        text = need,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                }
            }

            structuredOutput.emotionTone?.let { tone ->
                CollapsibleSection(title = "情绪基调") {
                    Text(
                        text = tone,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                }
            }

            // 冷战开解（scene_key = cold_war）：这一场景的「建议表达」不是
            // suggested_reply 而是 opening_lines（破冰话术）。此前卡片只认
            // 通用字段，选中"冷静一下"看到的是一张残缺卡——行动行能复制到
            // 破冰话术，卡片里却一个字都不显示。
            structuredOutput.goalAnalysis?.let { goal ->
                CollapsibleSection(title = "他的目标是什么") {
                    Text(
                        text = goal,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                }
            }

            structuredOutput.faceVsNeed?.let { faceVsNeed ->
                CollapsibleSection(title = "面子背后的需求") {
                    Text(
                        text = faceVsNeed,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                }
            }

            structuredOutput.approach?.let { approach ->
                CollapsibleSection(title = "建议怎么做") {
                    Text(
                        text = approach,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextSecondary,
                    )
                    structuredOutput.approachReason?.let { reason ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                }
            }

            structuredOutput.openingLines?.takeIf { it.isNotEmpty() }?.let { lines ->
                CollapsibleSection(title = "破冰话术", defaultExpanded = true) {
                    lines.forEach { line ->
                        Surface(
                            color = AppAccentLight,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppAccent,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }

            structuredOutput.avoidReminders?.takeIf { it.isNotEmpty() }?.let { reminders ->
                CollapsibleSection(title = "别踩的坑") {
                    reminders.forEach { reminder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.bodyLarge,
                                color = com.couple.translator.core.ui.theme.AppErrorRed,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = reminder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = com.couple.translator.core.ui.theme.AppErrorRed,
                            )
                        }
                    }
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
                                    color = AppAccent,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = AppAccentLight.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = rewrite.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = AppTextPrimary,
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
                                    color = AppBorderLight,
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Text(
                                        text = ref,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppTextSecondary,
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
                color = AppAccent,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = AppTextTertiary,
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
