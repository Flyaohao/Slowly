package com.couple.translator.feature.couple.presence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun MeetCountdown(
    targetDate: LocalDate?,
    modifier: Modifier = Modifier,
) {
    if (targetDate == null) return

    val daysLeft = remember(targetDate) {
        ChronoUnit.DAYS.between(LocalDate.now(), targetDate).coerceAtLeast(0).toInt()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppSurface)
            .padding(16.dp),
    ) {
        Text(
            text = "见面倒计时",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextTertiary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = daysLeft.toString(),
                style = MaterialTheme.typography.headlineLarge,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "天后见面",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
