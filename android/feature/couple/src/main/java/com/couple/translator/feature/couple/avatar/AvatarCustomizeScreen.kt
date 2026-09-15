package com.couple.translator.feature.couple.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                onClick = { viewModel.cycleTone() },
            )

            Spacer(modifier = Modifier.height(40.dp))
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
