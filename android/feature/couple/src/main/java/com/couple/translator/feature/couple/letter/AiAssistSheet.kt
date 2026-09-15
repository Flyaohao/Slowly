package com.couple.translator.feature.couple.letter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppTextSecondary

private data class AiStyle(val label: String, val style: String)

private val aiStyles = listOf(
    AiStyle("写得更温柔", "gentle"),
    AiStyle("帮我道歉", "apologize"),
    AiStyle("解释但不狡辩", "explain"),
    AiStyle("变成适合TA的表达", "adapt"),
    AiStyle("帮我补一个结尾", "ending"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistSheet(
    onDismiss: () -> Unit,
    onSelectStyle: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "AI 辅助写信",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "选择一种风格，AI 会帮你改写当前内容",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            aiStyles.forEach { item ->
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectStyle(item.style) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}
