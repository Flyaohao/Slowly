package com.couple.translator.core.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.TextSecondary

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is LoginUiEvent.LoginSuccess -> onLoginSuccess()
                is LoginUiEvent.ShowError -> {}
            }
        }
    }

    if (uiState.generalError.isNotEmpty()) {
        ErrorDialog(
            message = uiState.generalError,
            onDismiss = { viewModel.clearError() },
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "登录",
                style = MaterialTheme.typography.headlineLarge,
                color = Accent,
            )

            Spacer(modifier = Modifier.height(32.dp))

            TextInputField(
                value = uiState.email,
                onValueChange = { viewModel.onEmailChange(it) },
                label = "邮箱",
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextInputField(
                value = uiState.password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = "密码",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "忘记密码？",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable { onNavigateToForgotPassword() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                text = "登录",
                onClick = { viewModel.login() },
                isLoading = uiState.isLoading,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "没有账号？去注册",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.clickable { onNavigateToRegister() },
            )
        }
    }
}
