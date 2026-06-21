package com.couple.translator.feature.couple.couplebind

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.common.copyToClipboard
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoupleBindScreen(
    onNavigateBack: () -> Unit,
    onBindSuccess: () -> Unit,
    viewModel: CoupleBindViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
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

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.clearError() },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    text = "复制邀请码",
                    onClick = {
                        context.copyToClipboard(uiState.generatedCode)
                    },
                )
            } else {
                PrimaryButton(
                    text = "生成邀请码",
                    onClick = { viewModel.generateInviteCode() },
                    isLoading = uiState.isLoading,
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "我有邀请码",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextInputField(
                value = uiState.inputCode,
                onValueChange = viewModel::onInputCodeChange,
                label = "邀请码",
                placeholder = "输入对方的邀请码",
            )

            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                text = "绑定",
                onClick = { viewModel.bindCouple() },
                isLoading = uiState.isLoading,
            )
        }
    }
}
