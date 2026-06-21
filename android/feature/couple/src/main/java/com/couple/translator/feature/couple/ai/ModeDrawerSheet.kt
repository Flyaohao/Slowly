package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Icecream
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary

private data class AiMode(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

private val aiModes = listOf(
    AiMode("expression_rewrite", "帮我表达", Icons.Outlined.AutoAwesome),
    AiMode("partner_translate", "听懂 TA", Icons.Outlined.Hearing),
    AiMode("reply", "回信", Icons.Outlined.MailOutline),
    AiMode("apologize", "道歉", Icons.Outlined.FavoriteBorder),
    AiMode("cold_war", "冷静一下", Icons.Outlined.Icecream),
    AiMode("mediation", "双人调解", Icons.Outlined.People),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeDrawerSheet(
    onDismiss: () -> Unit,
    onModeSelected: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "选择模式",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            aiModes.forEachIndexed { index, mode ->
                ModeItem(
                    mode = mode,
                    onClick = { onModeSelected(mode.key) },
                )
                if (index < aiModes.lastIndex) {
                    HorizontalDivider(color = BorderLight)
                }
            }
        }
    }
}

@Composable
private fun ModeItem(
    mode: AiMode,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = mode.icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = mode.label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
    }
}
