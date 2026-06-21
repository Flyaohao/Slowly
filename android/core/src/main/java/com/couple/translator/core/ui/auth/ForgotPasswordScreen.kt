package com.couple.translator.core.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    onNavigateBack: () -> Unit,
    viewModel: ForgotPasswordViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(uiState.successMessage)
            viewModel.clearSuccess()
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
                title = { Text("找回密码") },
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
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            when (uiState.step) {
                ForgotStep.ENTER_EMAIL -> {
                    Text(
                        text = "输入注册邮箱，我们将发送验证码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    TextInputField(
                        value = uiState.email,
                        onValueChange = viewModel::onEmailChange,
                        label = "邮箱",
                        placeholder = "请输入注册邮箱",
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    PrimaryButton(
                        text = "发送验证码",
                        onClick = { viewModel.sendCode() },
                        isLoading = uiState.isLoading,
                    )
                }

                ForgotStep.ENTER_CODE -> {
                    Text(
                        text = "验证码已发送到 ${uiState.email}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    TextInputField(
                        value = uiState.code,
                        onValueChange = viewModel::onCodeChange,
                        label = "验证码",
                        placeholder = "输入 6 位验证码",
                        keyboardType = KeyboardType.Number,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    TextInputField(
                        value = uiState.newPassword,
                        onValueChange = viewModel::onNewPasswordChange,
                        label = "新密码",
                        placeholder = "至少 8 位，含大小写和数字",
                        isPassword = true,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    TextInputField(
                        value = uiState.confirmPassword,
                        onValueChange = viewModel::onConfirmPasswordChange,
                        label = "确认密码",
                        placeholder = "再次输入新密码",
                        isPassword = true,
                        imeAction = ImeAction.Done,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    PrimaryButton(
                        text = "重置密码",
                        onClick = { viewModel.resetPassword() },
                        isLoading = uiState.isLoading,
                    )
                }

                ForgotStep.DONE -> {
                    Spacer(modifier = Modifier.height(48.dp))

                    Text(
                        text = "密码重置成功",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Accent,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "请使用新密码登录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    PrimaryButton(
                        text = "返回",
                        onClick = onNavigateBack,
                    )
                }
            }
        }
    }
}
