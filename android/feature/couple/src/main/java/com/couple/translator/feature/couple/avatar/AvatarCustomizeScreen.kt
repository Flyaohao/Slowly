package com.couple.translator.feature.couple.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppDivider
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AvatarCustomizeScreen(
    onNavigateBack: () -> Unit,
    viewModel: AvatarCustomizeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // P-B §4.2：语气由「点一下循环」改为底部五选一
    var toneSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            snackbarHostState.showSnackbar("形象已保存")
            viewModel.clearSaved()
        }
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "AI 形象",
                trailing = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = !uiState.isSaving,
                    ) {
                        Text("保存", color = AppAccent)
                    }
                },
            )
        },
        containerColor = AppBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarPreview(
                faceShape = uiState.faceShape,
                eyeStyle = uiState.eyeStyle,
                mouthStyle = uiState.mouthStyle,
                blushStyle = uiState.blushStyle,
            )

            Spacer(modifier = Modifier.height(32.dp))

            CustomizeSection(
                title = "捏脸",
                items = listOf("脸型 A", "脸型 B", "脸型 C"),
                selectedIndex = uiState.faceShape,
                onSelect = { viewModel.updateFaceShape(it) },
            )

            CustomizeSection(
                title = "眼睛",
                items = listOf("圆眼", "细眼", "笑眼"),
                selectedIndex = uiState.eyeStyle,
                onSelect = { viewModel.updateEyeStyle(it) },
            )

            CustomizeSection(
                title = "嘴巴",
                items = listOf("微笑", "平静", "开心"),
                selectedIndex = uiState.mouthStyle,
                onSelect = { viewModel.updateMouthStyle(it) },
            )

            CustomizeSection(
                title = "腮红",
                items = listOf("无", "浅粉", "自然"),
                selectedIndex = uiState.blushStyle,
                onSelect = { viewModel.updateBlushStyle(it) },
            )

            AppDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingRow(
                label = "语气设置",
                value = toneLabels[uiState.toneIndex],
                onClick = { toneSheetOpen = true },
            )

            Spacer(modifier = Modifier.height(40.dp))
        }

        // P-B §4.2：五选一底部面板（与军师页回答深度面板同一套视觉）
        if (toneSheetOpen) {
            ToneSheet(
                selectedIndex = uiState.toneIndex,
                source = uiState.toneSource,
                onDismiss = { toneSheetOpen = false },
                onSelect = { index ->
                    toneSheetOpen = false
                    viewModel.setTone(index)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomizeSection(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = AppTextTertiary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEachIndexed { index, item ->
                Box(
                    modifier = Modifier
                        .pressFeedback(onClick = { onSelect(index) })
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (index == selectedIndex) AppAccentLight else AppSurface)
                        .border(
                            width = if (index == selectedIndex) 1.5.dp else 0.5.dp,
                            color = if (index == selectedIndex) AppAccent else AppBorderLight,
                            shape = RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == selectedIndex) AppAccent else AppTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextPrimary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextTertiary,
            )
        }
    }
    AppDivider(modifier = Modifier.padding(horizontal = 20.dp))
}

/**
 * P-B §4.2：语气五选一底部面板——与军师页「回答深度」面板同一套视觉。
 * 一次看全 5 档 + 每档副文案；来源为 auto 时顶部提示「当前由画像自动选择」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToneSheet(
    selectedIndex: Int,
    source: String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
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
                text = "语气设置",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            if (source == "auto") {
                Text(
                    text = "当前由画像自动选择",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            toneLabels.indices.forEach { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (index == selectedIndex) AppAccentLight else AppSurface)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toneLabels[index],
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = AppTextPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = toneDescriptions[index],
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary,
                        )
                    }
                    if (index == selectedIndex) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppAccent,
                        )
                    }
                }
                if (index < toneLabels.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
