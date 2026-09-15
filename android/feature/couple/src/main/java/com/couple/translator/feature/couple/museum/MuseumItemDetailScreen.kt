package com.couple.translator.feature.couple.museum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.couple.translator.core.network.toAbsoluteUrl
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@Composable
fun MuseumItemDetailScreen(
    itemId: Long,
    onNavigateBack: () -> Unit,
    viewModel: MuseumItemDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(itemId) {
        viewModel.loadItem(itemId)
    }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.loadItem(itemId) })
    }

    if (uiState.isLoading) {
        SkeletonDetailPage()
        return
    }

    val item = uiState.item ?: return

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "藏品详情",
                trailing = {
                    if (item != null) {
                        IconButton(onClick = { viewModel.togglePin(itemId) }) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = if (item.pinned) "取消置顶" else "置顶",
                                tint = if (item.pinned) AppAccent else AppTextTertiary,
                            )
                        }
                        IconButton(onClick = { viewModel.deleteItem(itemId) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = AppErrorRed)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = museumItemTypeText(item.itemType),
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (item.imageUrl != null) {
                AsyncImage(
                    model = item.imageUrl.toAbsoluteUrl(),
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.createdAt?.take(10) ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )

            if (item.story != null) {
                Spacer(modifier = Modifier.height(24.dp))
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.md),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column {
                        Text(
                            text = "故事",
                            style = MaterialTheme.typography.labelLarge,
                            color = AppAccent,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.story,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (item.sourceType != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "来源：${museumItemTypeText(item.sourceType)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                )
            }
        }
    }
}
