package com.couple.translator.feature.couple.letter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.Surface
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.core.ui.theme.TextTertiary

@Composable
fun NewMailboxScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToCompose: () -> Unit,
    onNavigateToLetterList: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
    isCoupleMode: Boolean = true,
    viewModel: MailboxViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(isCoupleMode) {
        viewModel.loadMailbox(isCoupleMode)
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Background)) {
            LoadingIndicator()
        }
        return
    }

    PullToRefreshLayout(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refresh(isCoupleMode) },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        MailboxTopBar(onOpenDrawer = onOpenDrawer)

        Spacer(modifier = Modifier.height(16.dp))

        if (isCoupleMode) {
            CoupleMailboxContent(
                uiState = uiState,
                onNavigateToCompose = onNavigateToCompose,
                onNavigateToLetterList = onNavigateToLetterList,
                onNavigateToLetterDetail = onNavigateToLetterDetail,
            )
        } else {
            SingleDiaryContent(
                uiState = uiState,
                onNavigateToCompose = onNavigateToCompose,
                onNavigateToLetterList = onNavigateToLetterList,
                onNavigateToLetterDetail = onNavigateToLetterDetail,
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
    }
}

// ==================== Couple Mode ====================

@Composable
private fun CoupleMailboxContent(
    uiState: MailboxUiState,
    onNavigateToCompose: () -> Unit,
    onNavigateToLetterList: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
) {
    // Title
    Text(
        text = "信箱",
        style = MaterialTheme.typography.displayMedium,
        color = TextPrimary,
        modifier = Modifier.padding(horizontal = 20.dp),
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Write button
    Button(
        onClick = onNavigateToCompose,
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = Surface),
    ) {
        Icon(Icons.Outlined.MailOutline, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("写一封信", style = MaterialTheme.typography.titleSmall)
    }

    Spacer(modifier = Modifier.height(28.dp))

    // Received letters
    if (uiState.receivedLetters.isNotEmpty()) {
        SectionHeader(title = "收到的信", count = uiState.receivedLetters.size)
        uiState.receivedLetters.take(3).forEach { letter ->
            LetterRow(letter = letter, onClick = { onNavigateToLetterDetail(letter.id) })
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    // Sent letters
    if (uiState.sentLetters.isNotEmpty()) {
        SectionHeader(title = "已发出", count = uiState.sentLetters.size)
        uiState.sentLetters.take(3).forEach { letter ->
            LetterRow(letter = letter, onClick = { onNavigateToLetterDetail(letter.id) })
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    // Favorites
    if (uiState.favoriteLetters.isNotEmpty()) {
        SectionHeader(title = "收藏的信", count = uiState.favoriteLetters.size)
        uiState.favoriteLetters.take(3).forEach { letter ->
            LetterRow(letter = letter, onClick = { onNavigateToLetterDetail(letter.id) })
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    // Empty state
    if (uiState.receivedLetters.isEmpty() && uiState.sentLetters.isEmpty() && uiState.favoriteLetters.isEmpty()) {
        MailboxEmptyState(isCoupleMode = true)
    }

    Spacer(modifier = Modifier.height(24.dp))

    // All letters link
    AllLettersLink(onClick = onNavigateToLetterList)
}

// ==================== Single Mode ====================

@Composable
private fun SingleDiaryContent(
    uiState: MailboxUiState,
    onNavigateToCompose: () -> Unit,
    onNavigateToLetterList: () -> Unit,
    onNavigateToLetterDetail: (Long) -> Unit,
) {
    // Title
    Text(
        text = "我的日记",
        style = MaterialTheme.typography.displayMedium,
        color = TextPrimary,
        modifier = Modifier.padding(horizontal = 20.dp),
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Write button
    Button(
        onClick = onNavigateToCompose,
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = Surface),
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("写一篇日记", style = MaterialTheme.typography.titleSmall)
    }

    Spacer(modifier = Modifier.height(28.dp))

    // Recent diaries
    if (uiState.recentDiaries.isNotEmpty()) {
        SectionHeader(title = "最近日记", count = uiState.recentDiaries.size)
        uiState.recentDiaries.forEach { letter ->
            DiaryRow(letter = letter, onClick = { onNavigateToLetterDetail(letter.id) })
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    // Favorites
    if (uiState.favoriteLetters.isNotEmpty()) {
        SectionHeader(title = "收藏", count = uiState.favoriteLetters.size)
        uiState.favoriteLetters.take(3).forEach { letter ->
            DiaryRow(letter = letter, onClick = { onNavigateToLetterDetail(letter.id) })
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    // Empty state
    if (uiState.recentDiaries.isEmpty() && uiState.favoriteLetters.isEmpty()) {
        MailboxEmptyState(isCoupleMode = false)
    }

    Spacer(modifier = Modifier.height(24.dp))

    // All diaries link
    AllLettersLink(onClick = onNavigateToLetterList, label = "全部日记")
}

// ==================== Shared Components ====================

@Composable
private fun MailboxTopBar(onOpenDrawer: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).background(AccentLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Person, contentDescription = "打开侧边栏", tint = Accent, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun LetterRow(letter: LetterDto.LetterResponse, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(AccentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = letter.title?.ifBlank { "无标题" } ?: "无标题",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = letter.content?.take(50) ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDateShort(letter.sendTime ?: letter.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
        )
    }
    HorizontalDivider(color = BorderLight, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun DiaryRow(letter: LetterDto.LetterResponse, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(AccentLight),
            contentAlignment = Alignment.Center,
        ) {
            Text("📝", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = letter.title?.ifBlank { "无标题" } ?: "无标题",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = letter.content?.take(50) ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDateShort(letter.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
        )
    }
    HorizontalDivider(color = BorderLight, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun MailboxEmptyState(isCoupleMode: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (isCoupleMode) Icons.Outlined.MailOutline else Icons.Outlined.Edit,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isCoupleMode) "还没有信件" else "还没有日记",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isCoupleMode) "可以从第一句认真话开始。" else "记录此刻的心情。",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )
    }
}

@Composable
private fun AllLettersLink(onClick: () -> Unit, label: String = "全部信件") {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = TextSecondary)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(16.dp))
    }
}

private fun formatDateShort(isoString: String?): String {
    if (isoString == null) return ""
    return try {
        val odt = java.time.OffsetDateTime.parse(isoString)
        val dt = odt.toLocalDateTime()
        val now = java.time.LocalDateTime.now()
        when {
            dt.toLocalDate() == now.toLocalDate() -> dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            dt.year == now.year -> dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
            else -> dt.format(java.time.format.DateTimeFormatter.ofPattern("yy-MM-dd"))
        }
    } catch (_: Exception) {
        isoString.take(10)
    }
}
