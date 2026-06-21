package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary

data class SceneItem(
    val key: String,
    val name: String,
    val description: String,
)

private val scenes = listOf(
    SceneItem(
        key = "private_advisor",
        name = "私人军师",
        description = "先安抚情绪，再分析局势",
    ),
    SceneItem(
        key = "partner_translate",
        name = "对方翻译",
        description = "TA 这句话是什么意思",
    ),
    SceneItem(
        key = "expression_rewrite",
        name = "表达改写",
        description = "帮我把话说软一点",
    ),
    SceneItem(
        key = "cold_war",
        name = "冷战开解",
        description = "帮你结束冷战，生成低压力开场白",
    ),
    SceneItem(
        key = "mediation",
        name = "双人调解",
        description = "双方一起，AI 帮你们更好地沟通",
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceneSelector(
    currentScene: String,
    onSceneSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "选择模式",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            scenes.forEach { scene ->
                val isSelected = currentScene == scene.key
                Surface(
                    color = if (isSelected) Accent else AccentLight,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable { onSceneSelected(scene.key) },
                ) {
                    Text(
                        text = scene.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.surface else Accent,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
