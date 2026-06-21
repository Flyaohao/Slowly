package com.couple.translator.feature.couple.practice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextSecondary

private val practiceSteps = mapOf(
    "listening" to listOf("写下你想对伴侣说的话", "用你自己的话复述你理解的对方的意思"),
    "thanks" to listOf("写下你感谢伴侣的一件事", "写下这件事对你的意义"),
    "apology" to listOf("描述你做了什么", "说明你理解这如何影响了对方", "写下你打算怎么改变"),
    "need" to listOf("用「我感到…因为我需要…」的句式表达"),
    "reconcile" to listOf("回顾冲突中发生了什么", "写下你的感受", "写下对方可能的感受", "你希望怎么修复"),
    "comfort" to listOf("写下伴侣可能正在经历的", "写下你想给对方的安慰"),
    "clarify" to listOf("写下你理解的对方的意思", "写下你觉得被误解的地方", "用更清晰的方式重新表达"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeDetailScreen(
    practiceId: Long,
    recordId: Long,
    onNavigateBack: () -> Unit,
    onSubmitSuccess: (Long) -> Unit,
    viewModel: PracticeDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(recordId) {
        viewModel.loadRecord(recordId)
    }

    LaunchedEffect(uiState.submitted) {
        if (uiState.submitted) onSubmitSuccess(recordId)
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = {})
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.record?.practiceTitle ?: "练习", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
    ) { padding ->
        if (uiState.isLoading && uiState.record == null) {
            LoadingIndicator()
            return@Scaffold
        }

        val record = uiState.record ?: return@Scaffold
        val steps = practiceSteps[record.practiceType] ?: listOf("写下你的想法")
        val currentStep = uiState.currentStep.coerceAtMost(steps.lastIndex)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
        ) {
            LinearProgressIndicator(
                progress = { (currentStep + 1).toFloat() / steps.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Accent,
                trackColor = AccentLight,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Text(
                    text = "步骤 ${currentStep + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "/ ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = steps[currentStep],
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.content,
                onValueChange = { viewModel.updateContent(it) },
                placeholder = { Text("写下你的想法...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BorderLight,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (currentStep < steps.lastIndex) {
                Button(
                    onClick = { viewModel.nextStep() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("下一步", modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                Button(
                    onClick = { viewModel.submitPractice(record.practiceId, recordId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("提交练习", modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}
