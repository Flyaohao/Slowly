package com.couple.translator.feature.couple.anniversary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppPrimaryButton
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
import com.couple.translator.core.ui.theme.AppTextSecondary

@Composable
fun AddAnniversaryScreen(
    onNavigateBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: AddAnniversaryViewModel = hiltViewModel(),
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
            AppBackTopBar(onBack = onNavigateBack, title = "新增纪念日")
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
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(56.dp))
                Spacer(modifier = Modifier.height(AppSpacing.md))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(56.dp))
                Spacer(modifier = Modifier.height(AppSpacing.md))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(120.dp))
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
                text = "记录对你们有意义的日子",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = uiState.title,
                onValueChange = { viewModel.updateTitle(it) },
                label = { Text("标题") },
                placeholder = { Text("例如：在一起纪念日") },
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
                value = uiState.date,
                onValueChange = { viewModel.updateDate(it) },
                label = { Text("日期") },
                placeholder = { Text("YYYY-MM-DD") },
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
                value = uiState.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text("说明（选填）") },
                placeholder = { Text("这个日子对你们的意义") },
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

            Spacer(modifier = Modifier.height(16.dp))

            // 整改 §8.8：一次性 vs 每年重复必须由用户显式选择。
            // 此前没有这一项，服务端一律按年滚动，于是「去年的一次性纪念」被
            // 算成明年的「还有 N 天」，和旁边印着的年份打架——契约点名的冲突。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每年重复",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (uiState.repeatAnnually) {
                            "每年到这天都会提醒，例如生日、在一起的纪念日"
                        } else {
                            "只算这一次，过期后不再显示「还有几天」"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }
                Switch(
                    checked = uiState.repeatAnnually,
                    onCheckedChange = { viewModel.updateRepeatAnnually(it) },
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AppPrimaryButton(
                text = "保存",
                onClick = { viewModel.createAnniversary() },
            )
        }
    }
}
