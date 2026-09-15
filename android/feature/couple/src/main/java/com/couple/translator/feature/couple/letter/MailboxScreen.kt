package com.couple.translator.feature.couple.letter

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(
    onNavigateToCompose: () -> Unit,
    onNavigateToLetterList: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    isCoupleMode: Boolean = true,
    viewModel: MailboxViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("信箱", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCompose,
                containerColor = AppAccent,
            ) {
                Icon(Icons.Default.Add, contentDescription = "写信", tint = AppSurface)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Quick action: write letter
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToCompose),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppAccent),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.MailOutline, contentDescription = null, tint = AppSurface)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("写一封信", style = MaterialTheme.typography.titleMedium, color = AppSurface)
                            Text("把心里话写下来", style = MaterialTheme.typography.bodySmall, color = AppSurface.copy(alpha = 0.8f))
                        }
                    }
                }
            }

            // Received letters section
            if (isCoupleMode && uiState.receivedLetters.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "收到的信",
                        count = uiState.receivedLetters.size,
                        onViewAll = onNavigateToLetterList,
                    )
                }
                items(uiState.receivedLetters.take(3)) { letter ->
                    LetterPreviewCard(
                        letter = letter,
                        onClick = { onNavigateToLetterDetail(letter.id) },
                    )
                }
            }

            // Sent letters section
            if (isCoupleMode && uiState.sentLetters.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "已发出",
                        count = uiState.sentLetters.size,
                        onViewAll = onNavigateToLetterList,
                    )
                }
                items(uiState.sentLetters.take(3)) { letter ->
                    LetterPreviewCard(
                        letter = letter,
                        onClick = { onNavigateToLetterDetail(letter.id) },
                    )
                }
            }

            // Favorites section
            if (uiState.favoriteLetters.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "收藏的信",
                        count = uiState.favoriteLetters.size,
                        onViewAll = onNavigateToLetterList,
                    )
                }
                items(uiState.favoriteLetters.take(3)) { letter ->
                    LetterPreviewCard(
                        letter = letter,
                        onClick = { onNavigateToLetterDetail(letter.id) },
                    )
                }
            }

            // Empty state
            if (!uiState.isLoading &&
                uiState.receivedLetters.isEmpty() &&
                uiState.sentLetters.isEmpty() &&
                uiState.favoriteLetters.isEmpty()
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.MailOutline,
                            contentDescription = null,
                            tint = AppTextTertiary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("还没有信件", style = MaterialTheme.typography.bodyLarge, color = AppTextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "点击右下角按钮，写一封给 TA 的信",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                }
            }

            // View all entry
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToLetterList),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("全部信件", style = MaterialTheme.typography.titleSmall, color = AppAccent)
                    }
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, onViewAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = AppTextSecondary,
            fontWeight = FontWeight.Medium,
        )
        TextButton(onClick = onViewAll) {
            Text("查看全部", style = MaterialTheme.typography.labelMedium, color = AppAccent)
        }
    }
}

@Composable
private fun LetterPreviewCard(
    letter: LetterDto.LetterResponse,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = letter.title?.ifBlank { "无标题" } ?: "无标题",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (letter.isFavorite) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = AppAccent, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = letter.content ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = letterTypeName(letter.letterType),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppAccent,
                )
                Text(
                    text = formatDate(letter.sendTime ?: letter.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }
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

private fun formatDate(isoString: String?): String {
    if (isoString == null) return ""
    return try {
        val odt = java.time.OffsetDateTime.parse(isoString)
        odt.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    } catch (_: Exception) {
        try {
            val dt = java.time.LocalDateTime.parse(isoString)
            dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        } catch (_: Exception) {
            isoString.take(16)
        }
    }
}
