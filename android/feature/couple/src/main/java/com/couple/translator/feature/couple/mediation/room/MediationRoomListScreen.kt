package com.couple.translator.feature.couple.mediation.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
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

/** 房间列表（设计 §一：主入口，军师 tab 顶部 / 抽屉都落到这里）。 */
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
        AppBackTopBar(onBack = onNavigateBack, title = "共同调解室")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (uiState.activeCount > 0) "有 ${uiState.activeCount} 间调解室正在进行"
                else "吵架了？把军师请进房间",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )
        }

        AppPrimaryButton(
            text = "发起调解",
            onClick = onCreateRoom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.loading) {
            Text(
                text = "加载中…",
                modifier = Modifier.padding(20.dp),
                color = AppTextTertiary,
            )
        } else if (uiState.rooms.isEmpty()) {
            AppEmptyState(
                icon = Icons.Outlined.Forum,
                title = "还没有调解室",
                subtitle = "发生争执后在这里开一间房间，双方一起聊，军师在场",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.rooms, key = { it.id }) { room ->
                    Card(
                        onClick = { onOpenRoom(room.id) },
                        colors = CardDefaults.cardColors(containerColor = AppSurface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = room.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AppTextPrimary,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Text(
                                    text = statusLabel(room.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (room.isActiveRoom) AppErrorRed else AppTextTertiary,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = buildString {
                                    append(room.eventTime)
                                    if (room.staleOverAWeek) append(" · 已 7 天无活动，建议结算")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
