package com.couple.translator.feature.couple.letter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetterDetailScreen(
    letterId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToComposeReply: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: LetterDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(letterId) {
        viewModel.loadLetter(letterId)
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is LetterDetailUiEvent.LetterDeleted -> onNavigateBack()
                is LetterDetailUiEvent.ShowError -> {}
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这封信件吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteLetter()
                }) {
                    Text("删除", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.loadLetter(letterId) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("信件详情") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val letter = uiState.letter
                    if (letter != null) {
                        // 草稿信件显示编辑按钮
                        if (letter.status == "draft") {
                            IconButton(onClick = { onNavigateToEdit(letter.id) }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = AppAccent)
                            }
                        }
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                if (letter.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (letter.isFavorite) AppAccent else AppTextSecondary,
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        val letter = uiState.letter
        if (letter == null) {
            Text(
                text = "信件不存在",
                modifier = Modifier.padding(padding).padding(24.dp),
                color = AppTextTertiary,
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = letter.title ?: "无标题",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                Text(
                    text = letterTypeName(letter.letterType),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = letter.createdAt ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextTertiary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = letter.content ?: "",
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row {
                TextButton(onClick = { onNavigateToComposeReply(letter.id) }) {
                    Text("回应", color = AppAccent)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { viewModel.understandLetter() },
                    enabled = !uiState.isLoadingAi,
                ) {
                    Text(
                        if (uiState.isLoadingAi) "AI 理解中..." else "AI 帮我理解",
                        color = AppAccent,
                    )
                }
            }

            if (uiState.showUnderstanding && uiState.understanding != null) {
                Spacer(modifier = Modifier.height(16.dp))
                LetterUnderstandingCard(
                    understanding = uiState.understanding!!,
                    onDismiss = { viewModel.dismissUnderstanding() },
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

private fun letterTypeName(type: String): String = when (type) {
    "normal" -> "普通信"
    "future" -> "未来信"
    "calm" -> "冷静信"
    "unsaid" -> "未说出口"
    "private" -> "私密信"
    "anniversary" -> "纪念信"
    "shared" -> "共同信"
    "reconcile" -> "和好信"
    else -> type
}
