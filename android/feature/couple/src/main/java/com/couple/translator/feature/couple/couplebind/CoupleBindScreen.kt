package com.couple.translator.feature.couple.couplebind

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.common.copyToClipboard
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.InterFontFamily
import com.couple.translator.feature.couple.data.repository.AppMode
import com.couple.translator.feature.couple.data.repository.CoupleStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoupleBindScreen(
    onNavigateBack: () -> Unit,
    onBindSuccess: () -> Unit,
    coupleStateManager: CoupleStateManager,
    viewModel: CoupleBindViewModel = hiltViewModel(),
    /**
     * 强制绑定模式（2026-09-29 用户裁决：删除单身模式）。
     *
     * true 时本页是 Main 目的地的落地页而非二级页：不显示返回键（没有上一级可回），
     * 顶栏右侧改为退出登录入口——不给出口会让未绑定用户彻底困在 App 里。
     */
    forced: Boolean = false,
    onLogout: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val coupleState by coupleStateManager.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is CoupleBindUiEvent.BindSuccess -> onBindSuccess()
                is CoupleBindUiEvent.ShowError -> {}
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
        )
    }

    if (uiState.showGenderDialog) {
        GenderDialog(
            selected = uiState.gender,
            isLoading = uiState.isLoading,
            onSelect = viewModel::onGenderChange,
            onConfirm = viewModel::confirmGender,
            onDismiss = viewModel::dismissGenderDialog,
        )
    }

    // Unbind confirmation dialog（契约 §2.6-2：后果说明与 CoupleInfoScreen 共用一份文案）
    if (uiState.showUnbindDialog) {
        UnbindConfirmDialog(
            onConfirm = { viewModel.requestUnbind() },
            onDismiss = { viewModel.dismissUnbindDialog() },
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            if (forced) {
                // 强制绑定：无返回键（没有上一级），右侧给退出登录出口。
                // 用 AppBackTopBar 的自绘顶栏以复用状态栏 inset 处理，
                // 但 onBack 传空实现——视觉上不给箭头（见下方 FrameTopBar）。
                FrameTopBar(
                    title = "情侣绑定",
                    onLogout = onLogout,
                )
            } else {
                AppBackTopBar(
                    onBack = onNavigateBack,
                    title = "情侣绑定",
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (coupleState.mode == AppMode.COUPLE || coupleState.mode == AppMode.UNBINDING) {
            // Already bound state
            AlreadyBoundContent(
                modifier = Modifier.padding(padding),
                coupleStateManager = coupleStateManager,
                viewModel = viewModel,
            )
        } else {
            // Not bound state - original UI
            NotBoundContent(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                viewModel = viewModel,
                context = context,
            )
        }
    }
}

/**
 * 强制绑定模式的顶栏：无返回箭头 + 标题 + 右上角「退出登录」。
 *
 * 不能复用 AppBackTopBar——它必然渲染返回箭头，而强制绑定页没有上一级。
 * 状态栏 inset 的处理与 AppBackTopBar 保持一致（挂在根 NavHost 上，无外壳代垫）。
 */
@Composable
private fun FrameTopBar(
    title: String,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(AppSize.topBar)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "退出登录",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onLogout)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * 「先选性别」弹窗（2026-09-29）。
 *
 * 为什么是弹窗而不是常驻卡片：后端绑定链路强制校验性别（自己缺 → 30008 / 对方缺 → 30009），
 * 刚注册用户没进过资料页，点「生成恋爱码」必被拦。把选择放进弹窗，主线（生成/绑定）
 * 保持干净，只有真需要时才问一次；选定确认后自动续跑被中断的动作。
 *
 * 只给「男 / 女」两项——与后端 has_explicit_gender 的白名单严格对齐；
 * 不做任何同/异性组合限制，双方同性同样允许绑定。
 */
@Composable
private fun GenderDialog(
    selected: String,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = AppBackground,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "设置你的性别",
                style = MaterialTheme.typography.titleLarge,
                color = AppTextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text = "军师需要知道怎么称呼你们两位。只用于对话称呼，可随时在「我的」里修改。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        CoupleBindUiState.GENDER_MALE,
                        CoupleBindUiState.GENDER_FEMALE,
                    ).forEach { option ->
                        GenderChip(
                            text = option,
                            selected = selected == option,
                            onClick = { onSelect(option) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            AppAccentButton(
                text = "确认",
                onClick = onConfirm,
                enabled = !isLoading &&
                    (selected == CoupleBindUiState.GENDER_MALE ||
                        selected == CoupleBindUiState.GENDER_FEMALE),
            )
        },
        dismissButton = {
            Text(
                text = "取消",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isLoading, onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    )
}

/** 单枚性别 chip：选中 = 品牌色实底白字，未选 = 浅描边。 */
@Composable
private fun GenderChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) AppAccent else AppBackground
    val content = if (selected) Color.White else AppTextPrimary
    val border = if (selected) AppAccent else AppBorderLight

    Box(
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 32.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = content,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun AlreadyBoundContent(
    modifier: Modifier,
    coupleStateManager: CoupleStateManager,
    viewModel: CoupleBindViewModel,
) {
    val coupleState by coupleStateManager.state.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val info = coupleState.coupleInfo
    val partnerNickname = "TA"
    val isUnbinding = coupleState.mode == AppMode.UNBINDING

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        AppCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = AppAccentLight,
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Partner avatar placeholder
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = AppAccent,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = partnerNickname,
                    style = MaterialTheme.typography.headlineSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                info?.bindTime?.let {
                    Text(
                        text = "绑定时间：$it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isUnbinding) "解绑冷静期中" else "已绑定",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUnbinding) AppErrorRed else AppTextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isUnbinding) {
            // Show cancel unbind button during unbinding period
            AppAccentButton(
                text = "取消解绑",
                onClick = { viewModel.cancelUnbind() },
                enabled = !uiState.isLoading,
            )
        } else {
            // Show unbind button for active couples
            AppAccentButton(
                text = "解除绑定",
                onClick = { viewModel.showUnbindDialog() },
                enabled = !uiState.isLoading,
            )
        }
    }
}

@Composable
private fun NotBoundContent(
    modifier: Modifier,
    uiState: CoupleBindUiState,
    viewModel: CoupleBindViewModel,
    context: android.content.Context,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "把这个空间交给你们两个人",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "邀请 TA",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.generatedCode.isNotEmpty()) {
            // 恋爱码是这一屏唯一的视觉主角，用显式的大字号 + 宽字距，
            // 不再借用 displayMedium 的通用大标题样式（那会让"码"和"标题"长得一样）。
            Text(
                text = uiState.generatedCode,
                style = TextStyle(
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                    lineHeight = 46.sp,
                    letterSpacing = 2.sp,
                ),
                color = AppAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "有效期至：${uiState.codeExpiresAt}",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            AppAccentButton(
                text = "复制恋爱码",
                onClick = {
                    context.copyToClipboard(uiState.generatedCode)
                },
            )
        } else {
            AppAccentButton(
                text = "生成恋爱码",
                onClick = { viewModel.generateInviteCode() },
                enabled = !uiState.isLoading,
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "我有恋爱码",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextInputField(
            value = uiState.inputCode,
            onValueChange = viewModel::onInputCodeChange,
            label = "恋爱码",
            placeholder = "输入对方的恋爱码",
        )

        Spacer(modifier = Modifier.height(16.dp))

        AppAccentButton(
            text = "绑定",
            onClick = { viewModel.bindCouple() },
            enabled = !uiState.isLoading,
        )
    }
}
