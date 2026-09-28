package com.couple.translator.feature.couple.mediation.room

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppTag
import com.couple.translator.core.ui.components.AppTagTone
import com.couple.translator.core.ui.components.SkeletonPlainListPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentFaint
import com.couple.translator.core.ui.theme.AppSoftGradient
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.AppWarm
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.feature.couple.data.model.MediationRoomDto
import com.couple.translator.feature.couple.data.model.isActiveRoom
import com.couple.translator.feature.couple.data.model.staleOverAWeek
import com.couple.translator.feature.couple.data.repository.MediationRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class RoomListUiState(
    val loading: Boolean = true,
    val rooms: List<MediationRoomDto.RoomSummary> = emptyList(),
    val activeCount: Int = 0,
    val error: String = "",
)

@HiltViewModel
class MediationRoomListViewModel @Inject constructor(
    private val repository: MediationRoomRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomListUiState())
    val uiState: StateFlow<RoomListUiState> = _uiState

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            repository.listRooms().fold(
                onSuccess = { data ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            rooms = data?.items ?: emptyList(),
                            activeCount = data?.activeCount ?: 0,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
                },
            )
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "active" -> "进行中"
    "settling" -> "正在生成调解书"
    "settlement_ready" -> "调解书待确认"
    "settled" -> "已结算"
    else -> status
}

/**
 * 相对时间（D4）：今天/昨天/N 天前/MM-dd。
 * 时间源用后端 ISO 时间（lastMessageAt ?: createdAt），event_time 是用户手填的自由文本
 * 解析不了，解析失败时原样展示。
 */
private fun relativeTime(iso: String?, fallback: String): String {
    if (iso.isNullOrBlank()) return fallback
    val instant = runCatching {
        Instant.parse(iso.replace(" ", "T") + if (iso.length == 19) "Z" else "")
    }.getOrNull() ?: return fallback
    val zone = ZoneId.systemDefault()
    val local = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val date = local.toLocalDate()
    return when {
        date == today -> "今天 ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        date == today.minusDays(1) -> "昨天 ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        date.isAfter(today.minusDays(7)) -> "${today.toEpochDay() - date.toEpochDay()} 天前"
        else -> local.format(DateTimeFormatter.ofPattern("MM-dd"))
    }
}

/** 房间列表（改版 2026-09-28：列表为主角，创建入口收进顶栏，低频 CTA 只在空态出现）。 */
@Composable
fun MediationRoomListScreen(
    onNavigateBack: () -> Unit,
    onOpenRoom: (Long) -> Unit,
    onCreateRoom: () -> Unit,
    viewModel: MediationRoomListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize()) {
        AppBackTopBar(
            onBack = onNavigateBack,
            title = "共同调解室",
            trailing = {
                IconButton(onClick = onCreateRoom, modifier = Modifier.size(32.dp)) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AppAccentFaint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "发起调解",
                            tint = AppAccent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )

        if (uiState.loading) {
            // G3：骨架屏替代「加载中…」文字，数据到位不跳版
            SkeletonPlainListPage(cardRows = 4)
        } else if (uiState.rooms.isEmpty()) {
            AppEmptyState(
                icon = Icons.Outlined.Forum,
                title = "还没有调解室",
                subtitle = "发生争执后在这里开一间房间，双方一起聊，军师在场",
                action = {
                    AppPrimaryButton(
                        text = "发起第一场调解",
                        onClick = onCreateRoom,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                },
            )
        } else {
            // D2：进行中的房间置顶分组，已结束的沉底；分组头承担「N 间正在进行」的信息
            val activeRooms = uiState.rooms.filter { it.isActiveRoom }
            val endedRooms = uiState.rooms.filterNot { it.isActiveRoom }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (activeRooms.isNotEmpty()) {
                    item(key = "header_active") {
                        GroupHeader(text = "进行中 · ${activeRooms.size}")
                    }
                    items(activeRooms, key = { it.id }) { room ->
                        RoomCard(room = room, onOpen = { onOpenRoom(room.id) })
                    }
                }
                if (endedRooms.isNotEmpty()) {
                    item(key = "header_ended") { GroupHeader(text = "已结束") }
                    items(endedRooms, key = { it.id }) { room ->
                        RoomCard(room = room, onOpen = { onOpenRoom(room.id) })
                    }
                }
            }
        }
    }
}

/** 分组头：小号标签样式，弱存在感，不与卡片抢层级。 */
@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = AppTextTertiary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** 房间卡（D3）：左侧首字头像给视觉锚点，进行中用柔和渐变底，已结束用中性底。 */
@Composable
private fun RoomCard(room: MediationRoomDto.RoomSummary, onOpen: () -> Unit) {
    AppCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .then(
                        if (room.isActiveRoom) {
                            Modifier.background(brush = AppSoftGradient)
                        } else {
                            Modifier.background(AppAccentFaint)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = room.name.trim().firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (room.isActiveRoom) AppSurface else AppTextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTextPrimary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = relativeTime(room.lastMessageAt ?: room.createdAt, room.eventTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
                if (room.staleOverAWeek) {
                    // 疏于活动是独立提醒，不再用「·」拼在时间后面
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "已 7 天无活动，建议结算",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppWarm,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (room.isActiveRoom) {
                PulsingDot()
                Spacer(modifier = Modifier.width(4.dp))
            }
            // 状态徽章（P-3f）：AppTag 分色替代裸文字
            AppTag(
                text = statusLabel(room.status),
                tone = when (room.status) {
                    "settlement_ready" -> AppTagTone.Warm
                    "active", "settling" -> AppTagTone.Accent
                    else -> AppTagTone.Neutral
                },
            )
        }
    }
}

/** 进行中状态的红点脉冲（M-8）：呼吸透明度，弱提醒不喧宾。 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(AppErrorRed),
    )
}
