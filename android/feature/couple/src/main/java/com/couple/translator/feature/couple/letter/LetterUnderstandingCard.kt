package com.couple.translator.feature.couple.letter

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.feature.couple.data.model.LetterDto

@Composable
fun LetterUnderstandingCard(
    understanding: LetterDto.LetterUnderstanding,
    onDismiss: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

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

        if (understanding.replySuggestions.isNotEmpty()) {
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
