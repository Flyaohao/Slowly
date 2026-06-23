package com.couple.translator.feature.single.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.Surface

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeDiaryScreen(
    onNavigateBack: () -> Unit,
    diaryId: Long? = null,
    viewModel: ComposeDiaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 如果传入了 diaryId，加载编辑模式
    LaunchedEffect(diaryId) {
        if (diaryId != null && diaryId > 0) {
            viewModel.loadForEdit(diaryId)
        }
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    if (uiState.isLoading) {
        LoadingIndicator()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        TopAppBar(
            title = { Text(if (uiState.isEditMode) "编辑日记" else "写日记") },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 标题
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("标题") },
                placeholder = { Text("给日记起个名字") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                ),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 内容
            OutlinedTextField(
                value = uiState.content,
                onValueChange = viewModel::updateContent,
                label = { Text("内容") },
                placeholder = { Text("记录今天的心情...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 心情选择
            Text(
                text = "今天的心情",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MOOD_OPTIONS.forEach { mood ->
                    val isSelected = uiState.mood == mood
                    Surface(
                        onClick = { viewModel.updateMood(if (isSelected) null else mood) },
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) Accent else AppSurface,
                    ) {
                        Text(
                            text = mood,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) Surface else AppTextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 天气选择
            Text(
                text = "今天的天气",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WEATHER_OPTIONS.forEach { weather ->
                    val isSelected = uiState.weather == weather
                    Surface(
                        onClick = { viewModel.updateWeather(if (isSelected) null else weather) },
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) Accent else AppSurface,
                    ) {
                        Text(
                            text = weather,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) Surface else AppTextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // 错误提示
            if (uiState.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 保存按钮
            Button(
                onClick = viewModel::save,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTextPrimary,
                    contentColor = Surface,
                ),
                enabled = !uiState.isSaving,
            ) {
                Text(
                    text = when {
                        uiState.isSaving -> "保存中..."
                        uiState.isEditMode -> "更新日记"
                        else -> "保存日记"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
