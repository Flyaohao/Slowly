package com.couple.translator.feature.couple.mediation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary

@Composable
fun MediationExplanationScreen(
    onStartMediation: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "双人调解室")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                contentPadding = PaddingValues(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "什么是双人调解室？",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppAccent,
                    )

                    Text(
                        text = "双人调解室是一个安全的空间，帮助你们双方更好地沟通：",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    val steps = listOf(
                        "1. 你发起调解邀请，等待对方接受",
                        "2. 双方分别输入自己的感受和诉求",
                        "3. AI 会将你们的表达改写为对方更容易接受的版本",
                        "4. 双方确认改写是否准确",
                        "5. AI 总结共同点、分歧点和下一步行动",
                    )
                    steps.forEach { step ->
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                        )
                    }

                    Text(
                        text = "AI 不会站队，不会判定对错，只是帮助你们更好地理解彼此。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            AppPrimaryButton(text = "开始调解", onClick = onStartMediation)

            Spacer(modifier = Modifier.height(12.dp))

            AppSecondaryButton(text = "暂不开始", onClick = onNavigateBack)
        }
    }
}

@Composable
fun MediationInviteScreen(
    sessionId: Long,
    isInviter: Boolean,
    onNavigateToInput: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: MediationInviteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        if (sessionId > 0) viewModel.setSessionId(sessionId)
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is MediationInviteUiEvent.Accepted -> onNavigateToInput(uiState.sessionId ?: return@collect)
                is MediationInviteUiEvent.Rejected -> onNavigateBack()
                is MediationInviteUiEvent.ShowError -> {}
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "调解邀请")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(color = AppAccent)
                Spacer(modifier = Modifier.height(16.dp))
            }

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                contentPadding = PaddingValues(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (isInviter) {
                        Text(
                            text = "等待对方接受邀请...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "邀请已发送，请耐心等待对方接受",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            text = "你收到了调解邀请",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "对方希望和你一起进行双人调解，帮助你们更好地沟通",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        AppPrimaryButton(
                            text = "接受邀请",
                            onClick = { viewModel.acceptInvite() },
                            enabled = !uiState.isLoading,
                        )

                        AppSecondaryButton(
                            text = "暂不接受",
                            onClick = { viewModel.rejectInvite() },
                            enabled = !uiState.isLoading,
                        )
                    }
                }
            }

            if (uiState.error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun MediationInputScreen(
    sessionId: Long,
    onSubmitSuccess: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: MediationInputViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.setSessionId(sessionId)
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is MediationInputUiEvent.InputSubmitted -> onSubmitSuccess(uiState.sessionId)
                is MediationInputUiEvent.ShowError -> {}
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "表达你的感受")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "请写下你的感受和诉求，AI 会帮你改写为对方更容易接受的表达",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )

            InputField(
                label = "我现在的感受",
                value = uiState.feeling,
                onValueChange = viewModel::onFeelingChange,
                placeholder = "比如：我觉得很委屈、很受伤...",
            )

            InputField(
                label = "触发我的事情",
                value = uiState.trigger,
                onValueChange = viewModel::onTriggerChange,
                placeholder = "比如：当你说了那句话的时候...",
            )

            InputField(
                label = "我希望你理解的",
                value = uiState.wishUnderstood,
                onValueChange = viewModel::onWishUnderstoodChange,
                placeholder = "比如：我不是在无理取闹...",
            )

            InputField(
                label = "我希望接下来可以怎样",
                value = uiState.wishNext,
                onValueChange = viewModel::onWishNextChange,
                placeholder = "比如：下次我们可以先冷静一下再聊...",
            )

            AppPrimaryButton(
                text = "提交",
                onClick = viewModel::submitInput,
                enabled = uiState.feeling.isNotBlank() && uiState.trigger.isNotBlank() && !uiState.isLoading,
            )

            if (uiState.error.isNotEmpty()) {
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun MediationConfirmScreen(
    sessionId: Long,
    onConfirmed: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: MediationConfirmViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.setSessionId(sessionId)
        viewModel.loadRewrite()
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is MediationConfirmUiEvent.Confirmed -> onConfirmed(uiState.sessionId)
                is MediationConfirmUiEvent.ShowError -> {}
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "确认改写")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "AI 将你的表达改写为对方更容易接受的版本",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = AppAccent,
                )
            }

            uiState.myRewrite?.let { rewrite ->
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.md),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "你的原话",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTextSecondary,
                        )
                        Text(
                            text = rewrite.original,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "AI 改写后",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppAccent,
                        )
                        Text(
                            text = rewrite.rewritten,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            TextInputField(
                value = uiState.supplement,
                onValueChange = viewModel::onSupplementChange,
                label = "补充说明（可选）",
                placeholder = "如果改写不够准确，可以补充...",
                singleLine = false,
            )

            AppPrimaryButton(
                text = "确认准确",
                onClick = { viewModel.confirm(true) },
                enabled = !uiState.isLoading,
            )

            AppSecondaryButton(
                text = "需要修改",
                onClick = { viewModel.confirm(false) },
                enabled = !uiState.isLoading,
            )

            if (uiState.error.isNotEmpty()) {
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun MediationResultScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit,
    viewModel: MediationResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.setSessionId(sessionId)
        viewModel.loadResult()
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "调解结果")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = AppAccent,
                )
            }

            if (uiState.commonPoints.isNotEmpty()) {
                ResultSection(
                    title = "共同点",
                    items = uiState.commonPoints,
                    accentColor = AppAccent,
                )
            }

            if (uiState.diffPoints.isNotEmpty()) {
                ResultSection(
                    title = "分歧点",
                    items = uiState.diffPoints,
                    accentColor = AppTextSecondary,
                )
            }

            if (uiState.nextActions.isNotEmpty()) {
                ResultSection(
                    title = "下一步行动",
                    items = uiState.nextActions,
                    accentColor = AppAccent,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "选择下一步",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            AppPrimaryButton(
                text = "继续沟通",
                onClick = { viewModel.chooseNextAction("continue") },
                enabled = !uiState.isLoading,
            )

            AppSecondaryButton(
                text = "暂停一下",
                onClick = { viewModel.chooseNextAction("pause") },
                enabled = !uiState.isLoading,
            )

            AppSecondaryButton(
                text = "结束调解",
                onClick = { viewModel.chooseNextAction("end") },
                enabled = !uiState.isLoading,
            )

            if (uiState.error.isNotEmpty()) {
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultSection(
    title: String,
    items: List<String>,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextInputField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            singleLine = false,
        )
    }
}
