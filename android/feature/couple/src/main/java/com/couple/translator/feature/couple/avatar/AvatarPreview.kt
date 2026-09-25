package com.couple.translator.feature.couple.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppTextTertiary

@Composable
fun AvatarPreview(
    faceShape: Int,
    eyeStyle: Int,
    mouthStyle: Int,
    blushStyle: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(AppAccentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(48.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "军师",
            style = MaterialTheme.typography.titleMedium,
            color = AppAccent,
        )

        Text(
            text = listOf(
                "脸型 ${('A' + faceShape)}",
                "眼睛 ${eyeStyle + 1}",
                "嘴巴 ${mouthStyle + 1}",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = AppTextTertiary,
        )
    }
}
