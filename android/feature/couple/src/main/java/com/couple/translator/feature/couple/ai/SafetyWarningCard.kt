package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.components.AiRiskLevel
import com.couple.translator.core.ui.theme.AppErrorContainer
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppIsDark
import com.couple.translator.core.ui.theme.AppWarning
import com.couple.translator.core.ui.theme.AppWarningContainer

// 风险等级直接用 core 的 AiRiskLevel（P-A §2.2）：本文件此前有一份本地
// SafetyRiskLevel 枚举与之逐档重复，已删——wire 值映射只在 AiRiskLevel.fromWire 一处。

data class SafetyWarningInfo(
    val level: AiRiskLevel,
    val title: String,
    val message: String,
    val resources: List<String> = emptyList(),
    val actionLabel: String? = null,
)

private val safetyWarnings = mapOf(
    AiRiskLevel.HEATED_CONFLICT to SafetyWarningInfo(
        level = AiRiskLevel.HEATED_CONFLICT,
        title = "情绪提醒",
        message = "感觉你们现在情绪都比较高。建议先深呼吸几次，等双方都冷静一些再继续沟通。你们的感受都是真实的，但情绪激动时说出来的话容易让对方更受伤。",
        actionLabel = "我已冷静，继续",
    ),
    AiRiskLevel.MANIPULATION_RISK to SafetyWarningInfo(
        level = AiRiskLevel.MANIPULATION_RISK,
        title = "表达提醒",
        message = "我注意到这个表达可能带有控制或威胁的意味。健康的关系建立在尊重和理解之上。我没办法帮你生成这类内容，但我可以帮你想一个更真诚、更能被对方听见的表达方式。",
        actionLabel = "帮我改写",
    ),
    AiRiskLevel.ABUSE_RISK to SafetyWarningInfo(
        level = AiRiskLevel.ABUSE_RISK,
        title = "安全提示",
        message = "你的安全是最重要的。如果你正在经历或担心暴力、胁迫、控制，请联系：",
        resources = listOf(
            "全国妇女维权热线：12338",
            "报警：110",
        ),
        actionLabel = null,
    ),
    AiRiskLevel.SELF_HARM_RISK to SafetyWarningInfo(
        level = AiRiskLevel.SELF_HARM_RISK,
        title = "我很担心你现在的状态",
        message = "请记住你不是一个人：",
        resources = listOf(
            "全国 24 小时心理危机热线：400-161-9995",
            "北京心理危机研究与干预中心：010-82951332",
        ),
        actionLabel = null,
    ),
)

/** P-A §2.2：wire 风险等级 → 本地文案（用于正文去重比对；null=无卡片）。 */
fun safetyCannedMessage(level: AiRiskLevel): String? = safetyWarnings[level]?.message

/**
 * 这个风险等级**有没有**对应的本地卡片。
 *
 * 整改 B4.3 P0-4：`UNKNOWN` 与 `HEATED_CONFLICT` 之外的档位都没有文案，
 * 所以**不渲染卡片**——但「没有卡片」与「可以放行」是两件事，见
 * `AiChatViewModel.mediationBlockedByRisk`：UNKNOWN 不渲染卡片，**仍然阻断**
 * 双人动作。把这两件事混在一起正是旧实现 fail open 的病根。
 */
fun hasSafetyCard(level: AiRiskLevel?): Boolean = level != null && safetyWarnings.containsKey(level)

@Composable
fun SafetyWarningCard(
    riskLevel: AiRiskLevel,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // UNKNOWN 没有本地文案可渲染——它不是「某一档危险」，是「不知道有多危险」。
    // 这里直接不渲染；阻断动作的责任在 mediationBlockedByRisk，不在这张卡片。
    val warning = safetyWarnings[riskLevel] ?: return
    // F1（2026-09-28 视觉美化批2）：硬编码警示色全部迁入语义色板——
    // 注意档（情绪/表达提醒）用 warning，危险档（滥用/自伤）用 error；
    // 深色模式由 DarkAppColors 的深底+提亮前景自动成立，不再按 isDark 分支写死色值。
    // 原黄色系（MANIPULATION_RISK）与橙色系（HEATED_CONFLICT）同属「注意」，收敛为一档。
    val (containerColor, iconTint) = when (riskLevel) {
        AiRiskLevel.HEATED_CONFLICT -> AppWarningContainer to AppWarning
        AiRiskLevel.MANIPULATION_RISK -> AppWarningContainer to AppWarning
        AiRiskLevel.ABUSE_RISK -> AppErrorContainer to AppErrorRed
        AiRiskLevel.SELF_HARM_RISK -> AppErrorContainer to AppErrorRed
        // 走不到：上面 `safetyWarnings[riskLevel] ?: return` 已经挡掉没有文案的档位。
        // 显式写出来而不是加 `else`，是为了让「以后新增一个档位」时编译器
        // 在这里报错——新档位必须有文案，否则它会静默地什么都不显示。
        AiRiskLevel.UNKNOWN -> Color.Transparent to AppErrorRed
    }
    // 深色下按钮底是提亮色，文字改用深底同色；浅色保持原有取色不变
    val buttonContentColor =
        if (AppIsDark) containerColor else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(28.dp),
            )

            Text(
                text = warning.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = iconTint,
            )

            Text(
                text = warning.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (warning.resources.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    warning.resources.forEach { resource ->
                        Text(
                            text = "• $resource",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = iconTint,
                        )
                    }
                }
                Text(
                    text = "AI 无法替代现实中的专业帮助。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (warning.actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = iconTint,
                        contentColor = buttonContentColor,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(warning.actionLabel)
                }
            }
        }
    }
}
