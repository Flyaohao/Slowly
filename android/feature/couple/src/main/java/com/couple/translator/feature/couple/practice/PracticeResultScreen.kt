package com.couple.translator.feature.couple.practice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeResultScreen(
    recordId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToAddMuseum: () -> Unit,
    viewModel: PracticeResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(recordId) {
        viewModel.loadResult(recordId)
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.loadResult(recordId) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("练习结果", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator()
            return@Scaffold
        }

        val record = uiState.record ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = record.practiceTitle,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = practiceStatusText(record.status),
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
            )

            if (record.mySubmission != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "我的提交",
                            style = MaterialTheme.typography.labelLarge,
                            color = AppAccent,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = record.mySubmission,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (record.partnerSubmission != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppAccentLight),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "对方的提交",
                            style = MaterialTheme.typography.labelLarge,
                            color = AppAccent,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = record.partnerSubmission,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (record.summary != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "AI 总结",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                ) {
                    Text(
                        text = record.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun practiceStatusText(status: String): String = when (status) {
    "initiated" -> "等待对方完成"
    "both_completed" -> "双方已完成"
    "summarized" -> "AI 已总结"
    else -> status
}
