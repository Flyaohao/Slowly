package com.couple.translator.feature.couple.couplebind

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppDivider
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.feature.couple.data.repository.AppMode
import com.couple.translator.feature.couple.data.repository.CoupleStateManager
import com.couple.translator.feature.couple.navigation.ShellLandingHolder
import com.couple.translator.feature.couple.navigation.ShellPage
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoupleInfoScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMain: () -> Unit,
    coupleStateManager: CoupleStateManager,
    viewModel: CoupleInfoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val coupleState by coupleStateManager.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is CoupleInfoEvent.NavigateToMain -> onNavigateToMain()
            }
        }
    }

    LaunchedEffect(uiState.unbindMessage) {
        if (uiState.unbindMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(uiState.unbindMessage)
        }
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.clearError() },
            title = "加载失败",
        )
    }

    // Unbind confirmation dialog（契约 §2.6-2：后果说明与 CoupleBindScreen 共用一份文案）
    if (uiState.showUnbindDialog) {
        UnbindConfirmDialog(
            onConfirm = { viewModel.requestUnbind() },
            onDismiss = { viewModel.dismissUnbindDialog() },
        )
    }

    val isUnbinding = coupleState.mode == AppMode.UNBINDING

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "情侣信息",
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            uiState.isLoading -> SkeletonDetailPage(modifier = Modifier.padding(padding))
            uiState.coupleInfo != null -> {
                val info = uiState.coupleInfo!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(24.dp))

                    // Both profiles side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Self profile
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = AppAccent,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = coupleState.userNickname ?: "我",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "我",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary,
                            )
                        }

                        // Heart symbol
                        Text(
                            text = "&",
                            style = MaterialTheme.typography.headlineLarge,
                            color = AppAccent,
                        )

                        // Partner profile
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = AppAccent,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "TA",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "伴侣",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Space name
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppAccentLight,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = info.space?.name ?: "我们的空间",
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center,
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            info.bindTime?.let {
                                Text(
                                    text = "绑定时间：$it",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppTextSecondary,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "在一起的时光",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppAccent,
                                )
                            }

                            if (isUnbinding) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "解绑冷静期中",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppErrorRed,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "申请发起 72 小时后，由对方确认解绑；期间任意一方可取消。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTextSecondary,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Navigate to main space button
                    AppAccentButton(
                        text = "进入我们的空间",
                        onClick = {
                            // 2026-09-29：先请求壳层精确落到「我们的空间」页，再回 Main。
                            // 壳层的 pager 会保留上次停留的 tab，光靠 navigate(Main)
                            // 可能落在军师页——与按钮文案不符。
                            ShellLandingHolder.request(ShellPage.Home)
                            viewModel.navigateToMain()
                        },
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Unbind section at bottom
                    AppDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    if (isUnbinding) {
                        // 冷却状态展示（契约 §2.6-2）：有 unbind_requested_at 才展示剩余时间
                        val requestedAtMillis = info.unbindRequestedAt?.let { raw ->
                            parseUnbindRequestedAt(raw)
                        }
                        val remainingMillis = requestedAtMillis?.let { start ->
                            (start + UNBIND_COOLDOWN_MILLIS) - System.currentTimeMillis()
                        }
                        if (info.unbindRequestedAt != null) {
                            Text(
                                text = if (remainingMillis == null) {
                                    "解绑冷静期中（72 小时）"
                                } else if (remainingMillis > 0) {
                                    "冷静期还剩 ${formatCooldownRemaining(remainingMillis)}"
                                } else {
                                    "冷静期已满"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppErrorRed,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        TextButton(
                            onClick = { viewModel.cancelUnbind() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("取消解绑")
                        }

                        // 确认按钮门控（契约 §2.6-2）：仅非发起方且满 72h 展示；
                        // 字段为 null（后端未落地/解析失败）→ 降级旧行为，由服务端 30004/30006 拦截。
                        val gatingPresent =
                            info.unbindRequestedAt != null && info.unbindRequestedBy != null
                        val isInitiator =
                            gatingPresent && uiState.myUserId != null &&
                                info.unbindRequestedBy == uiState.myUserId
                        val showConfirm = when {
                            !gatingPresent -> true
                            isInitiator -> false
                            remainingMillis == null -> true
                            else -> remainingMillis <= 0
                        }
                        if (showConfirm) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { viewModel.confirmUnbind() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (gatingPresent) "确认解绑" else "确认解绑（冷静期满后可用）",
                                    color = AppErrorRed,
                                )
                            }
                        } else if (isInitiator && remainingMillis != null && remainingMillis > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "你是申请方：冷静期满后由对方确认生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { viewModel.showUnbindDialog() },
                        ) {
                            Text(
                                text = "解除绑定",
                                color = AppErrorRed,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppEmptyState(
                        icon = Icons.Outlined.People,
                        title = "这个空间还差一个人",
                    )
                }
            }
        }
    }
}

/** 解绑冷静期 72 小时（契约 §2.6-2，与后端 unbinding_timeout 一致） */
private const val UNBIND_COOLDOWN_MILLIS = 72L * 60 * 60 * 1000

/**
 * 解析后端 `unbind_requested_at`（ISO-8601，可能带/不带时区）→ epoch millis。
 * 解析失败返回 null → FE 降级旧行为（按钮仍展示，由服务端 30004 拦截）。
 *
 * 注意第三级 fallback：后端是 `datetime.utcnow()` 的 naive ISO（无时区后缀），
 * 必须按 **UTC** 解析——按设备本地时区解析会让 UTC+8 设备早 8 小时显示「冷静期已满」。
 */
private fun parseUnbindRequestedAt(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
    return runCatching {
        LocalDateTime.parse(text).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun formatCooldownRemaining(remainingMillis: Long): String {
    val totalMinutes = remainingMillis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} 小时 $minutes 分钟" else "$minutes 分钟"
}
