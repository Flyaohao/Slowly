package com.couple.translator.core.ui.auth

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onNavigateBack: () -> Unit,
    onRegisterSuccess: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is RegisterUiEvent.RegisterSuccess -> onRegisterSuccess()
                is RegisterUiEvent.ShowError -> {}
            }
        }
    }

    if (uiState.generalError.isNotEmpty()) {
        ErrorDialog(
            message = uiState.generalError,
            onDismiss = { viewModel.clearError() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注册") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                ),
            )
        },
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

            TextInputField(
                value = uiState.email,
                onValueChange = viewModel::onEmailChange,
                label = "邮箱",
                placeholder = "请输入邮箱",
                keyboardType = KeyboardType.Email,
                isError = uiState.emailError.isNotEmpty(),
                errorMessage = uiState.emailError,
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextInputField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChange,
                label = "密码",
                placeholder = "至少8位，含大小写字母和数字",
                isPassword = true,
                isError = uiState.passwordError.isNotEmpty(),
                errorMessage = uiState.passwordError,
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextInputField(
                value = uiState.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChange,
                label = "确认密码",
                placeholder = "请再次输入密码",
                isPassword = true,
                isError = uiState.confirmPasswordError.isNotEmpty(),
                errorMessage = uiState.confirmPasswordError,
                imeAction = ImeAction.Done,
                onImeAction = { viewModel.register() },
            )

            Spacer(modifier = Modifier.height(32.dp))

            PrimaryButton(
                text = "注册",
                onClick = { viewModel.register() },
                isLoading = uiState.isLoading,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "已有账号？去登录",
                style = MaterialTheme.typography.bodyMedium,
                color = AppAccent,
                modifier = Modifier.clickable { onNavigateBack() },
            )
        }
    }
}
