package com.couple.translator.feature.couple.dual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.components.SkeletonTopBar
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDualEventScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSubmitRecord: (Long) -> Unit,
    /** 军师行动行「邀请 TA 补充双视角」进来时为 true（路由 /create_dual_event/invite） */
    invite: Boolean = false,
    viewModel: CreateDualEventViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 入口参数只喂一次（VM 内部挡重复），之后由用户输入驱动
    LaunchedEffect(invite) { viewModel.initialize(invite) }

    LaunchedEffect(uiState.created) {
        if (uiState.created) {
            onNavigateToSubmitRecord(uiState.createdEventId)
        }
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    if (uiState.isLoading) {
        CreateDualEventSkeleton()
        return
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "创建事件",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenH),
        ) {
            Text(
                text = "记录一次事件，双方分别写下自己的视角",
                style = MaterialTheme.typography.bodyMedium,
                color = com.couple.translator.core.ui.theme.AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = uiState.title,
                onValueChange = { viewModel.updateTitle(it) },
                label = { Text("事件标题") },
                placeholder = { Text("例如：那次争吵、那次旅行") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
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
                value = uiState.eventTime,
                onValueChange = { viewModel.updateEventTime(it) },
                label = { Text("事件时间（选填）") },
                placeholder = { Text("YYYY-MM-DD") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppBorderLight,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                ),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 整改 §8.6：邀请语。填了就在创建后通知伴侣一起来写；
            // 留空 = 自己先记着，不打扰对方（后端据此决定发不发实时帧）。
            OutlinedTextField(
                value = uiState.inviteMessage,
                onValueChange = { viewModel.updateInviteMessage(it) },
                label = { Text("邀请 TA 一起写（选填）") },
                placeholder = { Text("比如：我想听听你当时是怎么想的") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppBorderLight,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                ),
                minLines = 2,
            )

            Spacer(modifier = Modifier.height(32.dp))

            AppAccentButton(
                text = if (uiState.inviteMessage.isBlank()) {
                    "创建并记录我的视角"
                } else {
                    "创建并邀请 TA 一起写"
                },
                onClick = { viewModel.createEvent() },
            )
        }
    }
}

@Composable
private fun CreateDualEventSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        SkeletonTopBar()
        Column(modifier = Modifier.padding(horizontal = AppSpacing.screenH)) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            SkeletonPageHeader()
            Spacer(modifier = Modifier.height(AppSpacing.section))
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(AppRadius.md),
            )
            Spacer(modifier = Modifier.height(AppSpacing.lg))
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(AppRadius.md),
            )
            Spacer(modifier = Modifier.height(AppSpacing.block))
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(AppSize.button),
                shape = RoundedCornerShape(AppRadius.pill),
            )
        }
    }
}
