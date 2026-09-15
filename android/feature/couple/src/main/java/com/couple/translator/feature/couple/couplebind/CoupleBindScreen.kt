package com.couple.translator.feature.couple.couplebind

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.common.copyToClipboard
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.feature.couple.data.repository.AppMode
import com.couple.translator.feature.couple.data.repository.CoupleStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoupleBindScreen(
    onNavigateBack: () -> Unit,
    onBindSuccess: () -> Unit,
    coupleStateManager: CoupleStateManager,
    viewModel: CoupleBindViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val coupleState by coupleStateManager.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is CoupleBindUiEvent.BindSuccess -> onBindSuccess()
                is CoupleBindUiEvent.ShowError -> {}
            }
        }
    }

    LaunchedEffect(uiState.unbindMessage) {
        if (uiState.unbindMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(uiState.unbindMessage)
        }
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.clearError() },
        )
    }

    // Unbind confirmation dialog
    if (uiState.showUnbindDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnbindDialog() },
            title = { Text("确认解绑") },
            text = { Text("解绑设有 72 小时冷静期：申请后由对方在冷静期满后确认才生效，期间任意一方可取消。解绑后将失去情侣空间的所有数据，确定要申请吗？") },
            confirmButton = {
                TextButton(onClick = { viewModel.requestUnbind() }) {
                    Text("确认解绑", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUnbindDialog() }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("情侣绑定") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (coupleState.mode == AppMode.COUPLE || coupleState.mode == AppMode.UNBINDING) {
            // Already bound state
            AlreadyBoundContent(
                modifier = Modifier.padding(padding),
                coupleStateManager = coupleStateManager,
                viewModel = viewModel,
            )
        } else {
            // Not bound state - original UI
            NotBoundContent(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                viewModel = viewModel,
                context = context,
            )
        }
    }
}

@Composable
private fun AlreadyBoundContent(
    modifier: Modifier,
    coupleStateManager: CoupleStateManager,
    viewModel: CoupleBindViewModel,
) {
    val coupleState by coupleStateManager.state.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val info = coupleState.coupleInfo
    val partnerNickname = "TA"
    val isUnbinding = coupleState.mode == AppMode.UNBINDING

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppAccentLight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Partner avatar placeholder
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = partnerNickname,
                    style = MaterialTheme.typography.headlineSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                info?.bindTime?.let {
                    Text(
                        text = "绑定时间：$it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isUnbinding) "解绑冷静期中" else "已绑定",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUnbinding) MaterialTheme.colorScheme.error else TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isUnbinding) {
            // Show cancel unbind button during unbinding period
            PrimaryButton(
                text = "取消解绑",
                onClick = { viewModel.cancelUnbind() },
                isLoading = uiState.isLoading,
            )
        } else {
            // Show unbind button for active couples
            PrimaryButton(
                text = "解除绑定",
                onClick = { viewModel.showUnbindDialog() },
                isLoading = uiState.isLoading,
            )
        }
    }
}

@Composable
private fun NotBoundContent(
    modifier: Modifier,
    uiState: CoupleBindUiState,
    viewModel: CoupleBindViewModel,
    context: android.content.Context,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "把这个空间交给你们两个人",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "邀请 TA",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.generatedCode.isNotEmpty()) {
            Text(
                text = uiState.generatedCode,
                style = MaterialTheme.typography.displayMedium,
                color = Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "有效期至：${uiState.codeExpiresAt}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            PrimaryButton(
                text = "复制恋爱码",
                onClick = {
                    context.copyToClipboard(uiState.generatedCode)
                },
            )
        } else {
            PrimaryButton(
                text = "生成恋爱码",
                onClick = { viewModel.generateInviteCode() },
                isLoading = uiState.isLoading,
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "我有恋爱码",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextInputField(
            value = uiState.inputCode,
            onValueChange = viewModel::onInputCodeChange,
            label = "恋爱码",
            placeholder = "输入对方的恋爱码",
        )

        Spacer(modifier = Modifier.height(16.dp))

        PrimaryButton(
            text = "绑定",
            onClick = { viewModel.bindCouple() },
            isLoading = uiState.isLoading,
        )
    }
}
