package com.couple.translator.feature.couple.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary

/**
 * 模式选择抽屉。
 *
 * [scenes] 由 [AiSceneCatalog] 提供（最终来自后端 `GET /ai/scenes`）。
 *
 * 此前这里的 `aiModes` 是一份独立硬编码清单，里面塞了 `reply` / `apologize`
 * 两个 key，而**后端根本没有这两个 scene**：选中后请求照常发出，服务端按
 * 未知 scene 静默回退到 `TranslateOutput`，用户拿到一段通用回答却看不出哪里不对。
 * 场景集合既有唯一来源（[AiSceneCatalog]）后，这类"前端凭空造场景"不可能再发生。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeDrawerSheet(
    scenes: List<AiScene>,
    currentSceneKey: String = "",
    onDismiss: () -> Unit,
    onModeSelected: (AiScene) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppBackground,
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
                color = AppTextSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            scenes.forEachIndexed { index, scene ->
                ModeItem(
                    scene = scene,
                    // P-C4：输入框下方的场景 chip 复用本抽屉——标出当前场景，
                    // 否则对话中重选时不知道现在是哪个
                    selected = scene.key == currentSceneKey,
                    onClick = { onModeSelected(scene) },
                )
                if (index < scenes.lastIndex) {
                    HorizontalDivider(color = AppBorderLight)
                }
            }
        }
    }
}

@Composable
private fun ModeItem(
    scene: AiScene,
    selected: Boolean = false,
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
            // 目录里没配图标的场景（后端新加、客户端还没补样式）用中性图标兜底
            imageVector = scene.icon ?: Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = AppTextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = scene.label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyLarge,
                color = AppAccent,
            )
        }
    }
}
