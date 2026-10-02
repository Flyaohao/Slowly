package com.couple.translator.feature.couple.museum

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppAsyncImage
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppFilterChip
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface

private val itemTypes = listOf(
    "letter" to "信件",
    "photo" to "照片",
    "word" to "一句话",
    "record" to "记录",
    "joke" to "梗",
    "apology" to "道歉",
    "promise" to "承诺",
    "decision" to "决定",
    "chat" to "聊天",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddMuseumItemScreen(
    onNavigateBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: AddMuseumItemViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.created) {
        if (uiState.created) onCreated()
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "新增藏品")
        },
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                SkeletonPageHeader()
                Spacer(modifier = Modifier.height(AppSpacing.section))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(4) {
                        SkeletonBlock(modifier = Modifier.height(34.dp).weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.section))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(56.dp))
                Spacer(modifier = Modifier.height(AppSpacing.md))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(120.dp))
                Spacer(modifier = Modifier.height(AppSpacing.section))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(180.dp))
                Spacer(modifier = Modifier.height(AppSpacing.section))
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppSize.button),
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH),
        ) {
            Text(
                text = "选择藏品类型",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemTypes.forEach { (type, label) ->
                    AppFilterChip(
                        text = label,
                        selected = uiState.itemType == type,
                        onClick = { viewModel.updateItemType(type) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = uiState.title,
                onValueChange = { viewModel.updateTitle(it) },
                label = { Text("标题") },
                placeholder = { Text("给这件藏品起个名字") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppBorderLight,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                ),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.story,
                onValueChange = { viewModel.updateStory(it) },
                label = { Text("故事（选填）") },
                placeholder = { Text("这件藏品背后的故事") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(AppRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppBorderLight,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                ),
            )

            val pickImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia(),
            ) { uri ->
                viewModel.updateImage(uri)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 配图（照片类藏品可配图，其他类型选填）
            if (uiState.imageUri != null) {
                AppAsyncImage(
                    model = uiState.imageUri,
                    contentDescription = "藏品配图预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(AppRadius.md),
                    iconSize = 30.dp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppSecondaryButton(
                        text = "更换图片",
                        onClick = {
                            pickImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    AppSecondaryButton(
                        text = "移除图片",
                        onClick = { viewModel.updateImage(null) },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                AppSecondaryButton(
                    text = if (uiState.itemType == "photo") "添加照片" else "添加配图（选填）",
                    onClick = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AppPrimaryButton(
                text = "收藏",
                onClick = { viewModel.createItem() },
            )
        }
    }
}
