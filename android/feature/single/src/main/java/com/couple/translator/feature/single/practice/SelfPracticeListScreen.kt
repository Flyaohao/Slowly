package com.couple.translator.feature.single.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.single.data.model.SelfPracticeDto
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfPracticeListScreen(
    onNavigateBack: () -> Unit,
    viewModel: SelfPracticeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    when {
        uiState.isCompleted -> PracticeCompletedScreen(
            onFinish = { viewModel.resetToPracticeList() }
        )
        uiState.currentPractice != null -> PracticeDetailScreen(
            practice = uiState.currentPractice!!,
            uiState = uiState,
            onContentChange = viewModel::updateContent,
            onReflectionChange = viewModel::updateReflection,
            onSubmit = viewModel::submitPractice,
            onBack = { viewModel.resetToPracticeList() },
        )
        else -> PracticeListScreen(
            uiState = uiState,
            onNavigateBack = onNavigateBack,
            onStartPractice = viewModel::startPractice,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PracticeListScreen(
    uiState: SelfPracticeUiState,
    onNavigateBack: () -> Unit,
    onStartPractice: (Long) -> Unit,
) {
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("自我练习") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                    titleContentColor = AppTextPrimary,
                    navigationIconContentColor = AppTextPrimary,
                ),
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                LoadingIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    text = "选择一个练习开始",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextTertiary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                uiState.practices.forEach { practice ->
                    PracticeCard(
                        practice = practice,
                        onClick = { onStartPractice(practice.id) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun PracticeCard(
    practice: SelfPracticeDto.SelfPracticeResponse,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = AppSurface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppAccentLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SelfImprovement,
                    contentDescription = null,
                    tint = AppAccent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = practice.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = practice.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PracticeDetailScreen(
    practice: SelfPracticeDto.SelfPracticeResponse,
    uiState: SelfPracticeUiState,
    onContentChange: (String) -> Unit,
    onReflectionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text(practice.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                    titleContentColor = AppTextPrimary,
                    navigationIconContentColor = AppTextPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 练习指引
            if (practice.guidance != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppAccentLight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "练习指引",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppAccent,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = practice.guidance,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextPrimary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // 练习内容
            Text(
                text = "练习记录",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.content,
                onValueChange = onContentChange,
                placeholder = { Text("记录你的练习过程...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 反思
            Text(
                text = "反思感悟",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.reflection,
                onValueChange = onReflectionChange,
                placeholder = { Text("这次练习给你带来了什么启发？") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                ),
            )

            if (uiState.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = AppTextPrimary, contentColor = AppSurface),
                enabled = !uiState.isSubmitting,
            ) {
                Text(
                    text = if (uiState.isSubmitting) "提交中..." else "完成练习",
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PracticeCompletedScreen(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "练习完成",
            style = MaterialTheme.typography.headlineSmall,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "坚持练习，你会遇见更好的自己",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onFinish,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = AppTextPrimary, contentColor = AppSurface),
        ) {
            Text("返回练习列表")
        }
    }
}
