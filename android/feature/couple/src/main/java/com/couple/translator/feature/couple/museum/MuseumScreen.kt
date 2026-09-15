package com.couple.translator.feature.couple.museum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import coil.compose.AsyncImage
import com.couple.translator.core.network.toAbsoluteUrl
import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextTertiary

private val typeFilters = listOf(
    null to "全部",
    "letter" to "信件",
    "photo" to "照片",
    "word" to "一句话",
    "record" to "记录",
    "joke" to "梗",
    "apology" to "道歉",
    "promise" to "承诺",
    "dual_perspective" to "双视角",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuseumScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToAdd: () -> Unit,
    viewModel: MuseumViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关系博物馆", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = AppAccent,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增藏品", tint = AppSurface)
            }
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(typeFilters) { (type, label) ->
                    FilterChip(
                        selected = uiState.selectedType == type,
                        onClick = { viewModel.selectType(type) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppAccentLight,
                            selectedLabelColor = AppAccent,
                        ),
                    )
                }
            }

            // 注意：此处不能写 return@Column —— Column 是 inline composable，
            // qualified return 会触发 Compose 编译器 group 错位 bug（compose-jb#2230，
            // 症状为进入页面即 ArrayIndexOutOfBoundsException: index=-5 闪退），
            // 必须用 when 分支结构代替提前返回。
            when {
                uiState.isLoading -> LoadingIndicator()

                uiState.items.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "博物馆还是空的",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextTertiary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "收藏你们珍贵的瞬间",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextTertiary,
                    )
                }

                else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                uiState.items.groupBy { it.createdAt?.take(7) ?: "" }
                    .forEach { (month, monthItems) ->
                        item {
                            TimelineMonthHeader(month)
                        }
                        items(monthItems) { item ->
                            TimelineItem(
                                item = item,
                                onClick = { onNavigateToDetail(item.id) },
                            )
                        }
                    }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
            }
        }
        }
    }
}

@Composable
private fun TimelineMonthHeader(month: String) {
    if (month.isNotBlank()) {
        Text(
            text = month,
            style = MaterialTheme.typography.titleSmall,
            color = AppAccent,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun TimelineItem(
    item: MuseumDto.MuseumItemResponse,
    onClick: () -> Unit,
) {
    // App* 是 @Composable 取色，必须在 Composable 作用域内先取出来，不能直接写进 Canvas 的绘制 lambda
    val dotColor = AppAccent
    val lineColor = AppBorderLight
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(24.dp),
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = dotColor)
            }
            Spacer(modifier = Modifier.height(2.dp))
            androidx.compose.foundation.Canvas(modifier = Modifier.size(1.dp, 40.dp)) {
                drawRect(color = lineColor)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.imageUrl != null) {
                    AsyncImage(
                        model = item.imageUrl.toAbsoluteUrl(),
                        contentDescription = item.title,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    if (item.story != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.story,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                            maxLines = 2,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = museumItemTypeText(item.itemType),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppAccent,
                    )
                }
                if (item.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "已置顶",
                        tint = AppAccent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

internal fun museumItemTypeText(type: String): String = when (type) {
    "letter" -> "信件"
    "photo" -> "照片"
    "word" -> "一句话"
    "record" -> "记录"
    "joke" -> "梗"
    "apology" -> "道歉"
    "promise" -> "承诺"
    "dual_perspective" -> "双视角"
    "chat" -> "聊天"
    "decision" -> "决定"
    else -> type
}
