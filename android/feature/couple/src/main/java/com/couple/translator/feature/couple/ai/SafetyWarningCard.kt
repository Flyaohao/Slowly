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
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppIsDark

enum class SafetyRiskLevel {
    HEATED_CONFLICT,
    MANIPULATION_RISK,
    ABUSE_RISK,
    SELF_HARM_RISK,
}

data class SafetyWarningInfo(
    val level: SafetyRiskLevel,
    val title: String,
    val message: String,
    val resources: List<String> = emptyList(),
    val actionLabel: String? = null,
)

private val safetyWarnings = mapOf(
    SafetyRiskLevel.HEATED_CONFLICT to SafetyWarningInfo(
        level = SafetyRiskLevel.HEATED_CONFLICT,
        title = "情绪提醒",
        message = "感觉你们现在情绪都比较高。建议先深呼吸几次，等双方都冷静一些再继续沟通。你们的感受都是真实的，但情绪激动时说出来的话容易让对方更受伤。",
        actionLabel = "我已冷静，继续",
    ),
    SafetyRiskLevel.MANIPULATION_RISK to SafetyWarningInfo(
        level = SafetyRiskLevel.MANIPULATION_RISK,
        title = "表达提醒",
        message = "我注意到这个表达可能带有控制或威胁的意味。健康的关系建立在尊重和理解之上。我没办法帮你生成这类内容，但我可以帮你想一个更真诚、更能被对方听见的表达方式。",
        actionLabel = "帮我改写",
    ),
    SafetyRiskLevel.ABUSE_RISK to SafetyWarningInfo(
        level = SafetyRiskLevel.ABUSE_RISK,
        title = "安全提示",
        message = "你的安全是最重要的。如果你正在经历或担心暴力、胁迫、控制，请联系：",
        resources = listOf(
            "全国妇女维权热线：12338",
            "报警：110",
        ),
        actionLabel = null,
    ),
    SafetyRiskLevel.SELF_HARM_RISK to SafetyWarningInfo(
        level = SafetyRiskLevel.SELF_HARM_RISK,
        title = "我很担心你现在的状态",
        message = "请记住你不是一个人：",
        resources = listOf(
            "全国 24 小时心理危机热线：400-161-9995",
            "北京心理危机研究与干预中心：010-82951332",
        ),
        actionLabel = null,
    ),
)

@Composable
fun SafetyWarningCard(
    riskLevel: SafetyRiskLevel,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val warning = safetyWarnings[riskLevel] ?: return
    val isDark = AppIsDark
    // 深色模式不能沿用浅色警示底（会在黑底上炸出一块白），改为同色系的深底 + 提亮图标色
    val (containerColor, iconTint) = when (riskLevel) {
        SafetyRiskLevel.HEATED_CONFLICT ->
            if (isDark) Color(0xFF3A2A12) to Color(0xFFFFB74D)
            else Color(0xFFFFF3E0) to Color(0xFFE65100)
        SafetyRiskLevel.MANIPULATION_RISK ->
            if (isDark) Color(0xFF3A3212) to Color(0xFFFFD54F)
            else Color(0xFFFFF8E1) to Color(0xFFF9A825)
        SafetyRiskLevel.ABUSE_RISK ->
            if (isDark) Color(0xFF3A1A1E) to AppErrorRed
            else Color(0xFFFFEBEE) to AppErrorRed
        SafetyRiskLevel.SELF_HARM_RISK ->
            if (isDark) Color(0xFF4A1A1C) to Color(0xFFFF8A80)
            else Color(0xFFFFCDD2) to Color(0xFFB71C1C)
    }
    // 深色下按钮底是提亮色，文字改用深底同色；浅色保持原有取色不变
    val buttonContentColor =
        if (isDark) containerColor else MaterialTheme.colorScheme.onSurface

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
