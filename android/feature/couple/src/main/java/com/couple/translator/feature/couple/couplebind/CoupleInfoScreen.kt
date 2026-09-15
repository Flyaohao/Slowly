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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.TextSecondary
import com.couple.translator.feature.couple.data.repository.AppMode
import com.couple.translator.feature.couple.data.repository.CoupleStateManager

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

    // Unbind confirmation dialog
    if (uiState.showUnbindDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnbindDialog() },
            title = { Text("确认解绑") },
            text = { Text("解绑设有 72 小时冷静期：申请后由对方在冷静期满后确认才生效，期间任意一方可取消。解绑后将失去情侣空间的所有数据，确定要申请吗？") },
            confirmButton = {
                TextButton(onClick = { viewModel.requestUnbind() }) {
                    Text("确认解绑", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUnbindDialog() }) {
                    Text("取消")
                }
            },
        )
    }

    val isUnbinding = coupleState.mode == AppMode.UNBINDING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("情侣信息") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
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
                                tint = Accent,
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
                                color = TextSecondary,
                            )
                        }

                        // Heart symbol
                        Text(
                            text = "&",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Accent,
                        )

                        // Partner profile
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = Accent,
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
                                color = TextSecondary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Space name
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppAccentLight),
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
                                    color = TextSecondary,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "在一起的时光",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Accent,
                                )
                            }

                            if (isUnbinding) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "解绑冷静期中",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "申请发起 72 小时后，由对方确认解绑；期间任意一方可取消。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Navigate to main space button
                    PrimaryButton(
                        text = "进入我们的空间",
                        onClick = { viewModel.navigateToMain() },
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Unbind section at bottom
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    if (isUnbinding) {
                        TextButton(
                            onClick = { viewModel.cancelUnbind() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("取消解绑")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.confirmUnbind() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "确认解绑（冷静期满后可用）",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { viewModel.showUnbindDialog() },
                        ) {
                            Text(
                                text = "解除绑定",
                                color = MaterialTheme.colorScheme.error,
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
                    Text(
                        text = "这个空间还差一个人",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}
