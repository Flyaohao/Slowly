package com.couple.translator.feature.couple.presence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 异地陪伴页：见面倒计时 + 共享此刻 + 一键陪伴请求。
 * 把散在首页的在场感能力聚合成独立空间，异地时当「我们的小站」用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresenceScreen(
    onNavigateBack: () -> Unit,
    viewModel: PresenceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var momentText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            val pickerState = rememberDatePickerState()
            DatePicker(state = pickerState)
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.setMeetDate(
                            Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        )
                    }
                    showDatePicker = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("就定这一天") }
        }
    }

    Scaffold(
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "异地陪伴")
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.screenH),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- 见面倒计时 ----
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    MeetDateCard(
                        meetDate = uiState.meetDate,
                        onPickDate = { showDatePicker = true },
                    )
                }

                // ---- 陪伴请求 ----
                item {
                    AppSecondaryButton(
                        text = if (uiState.companionSent) "陪伴请求已送达，等 TA 回应" else "发送陪伴请求（想你了）",
                        onClick = { viewModel.sendCompanionRequest() },
                    )
                }

                // ---- 共享此刻：发布 ----
                item {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = "共享此刻",
                            style = MaterialTheme.typography.titleSmall,
                            color = AppTextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = momentText,
                            onValueChange = { momentText = it },
                            placeholder = { Text("现在的你在做什么、心情如何…") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AppAccentButton(
                            text = "发布",
                            onClick = {
                                viewModel.shareMoment(momentText)
                                momentText = ""
                            },
                        )
                    }
                }

                // ---- 共享此刻：动态流 ----
                if (uiState.moments.isNotEmpty()) {
                    item {
                        Text(
                            text = "最近的彼此",
                            style = MaterialTheme.typography.titleSmall,
                            color = AppTextPrimary,
                        )
                    }
                    items(uiState.moments) { moment ->
                        MomentItem(
                            content = moment.content,
                            createdAt = moment.createdAt,
                            isMine = moment.userId == uiState.myUserId,
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun MeetDateCard(
    meetDate: LocalDate?,
    onPickDate: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        if (meetDate == null) {
            Text(
                text = "还没约定下次见面",
                style = MaterialTheme.typography.titleSmall,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "定一个日子，倒计时会一直陪你们数着",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            AppAccentButton(text = "约定见面日期", onClick = onPickDate)
        } else {
            val daysLeft = java.time.temporal.ChronoUnit.DAYS
                .between(LocalDate.now(), meetDate)
                .coerceAtLeast(0)
            Text(
                text = if (daysLeft == 0L) "就是今天" else "还有 $daysLeft 天",
                style = MaterialTheme.typography.headlineMedium,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${meetDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))} 见面",
                style = MaterialTheme.typography.bodyMedium,
                color = AppAccent,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onPickDate) { Text("改日子", color = AppTextSecondary) }
        }
    }
}

@Composable
private fun MomentItem(
    content: String,
    createdAt: String?,
    isMine: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = if (isMine) "我" else "TA",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextTertiary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.lg))
                    .padding(0.dp),
            ) {
                AppCard(
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                    )
                    if (!createdAt.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = createdAt.take(16).replace('T', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextTertiary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(2.dp))
        }
    }
}
