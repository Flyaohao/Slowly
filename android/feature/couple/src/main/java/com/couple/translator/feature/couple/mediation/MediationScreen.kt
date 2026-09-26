package com.couple.translator.feature.couple.mediation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.AppSecondaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextSecondary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 调解说明页。契约 §2.3-1 API 优先：「开始调解」先在服务端创建会话拿到真实
 * session_id，再导航进邀请页（废除 sessionId=0 默认导航）。
 */
@Composable
fun MediationExplanationScreen(
    onStartMediation: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: MediationExplanationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is MediationExplanationUiEvent.Started -> onStartMediation(event.sessionId)
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "双人调解室")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                contentPadding = PaddingValues(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "什么是双人调解室？",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppAccent,
                    )

                    Text(
                        text = "双人调解室是一个安全的空间，帮助你们双方更好地沟通：",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    val steps = listOf(
                        "1. 你发起调解邀请，等待对方接受",
                        "2. 双方分别输入自己的感受和诉求",
                        "3. AI 会将你们的表达改写为对方更容易接受的版本",
                        "4. 双方确认改写是否准确",
                        "5. AI 总结共同点、分歧点和下一步行动",
                    )
                    steps.forEach { step ->
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                        )
                    }

                    Text(
                        text = "AI 不会站队，不会判定对错，只是帮助你们更好地理解彼此。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.error.isNotEmpty()) {
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            AppPrimaryButton(
                text = if (uiState.isStarting) "正在创建会话..." else "开始调解",
                onClick = { viewModel.startMediation() },
                enabled = !uiState.isStarting,
            )

            Spacer(modifier = Modifier.height(12.dp))

            AppSecondaryButton(text = "暂不开始", onClick = onNavigateBack)
        }
    }
}

@Composable
fun MediationInviteScreen(
    sessionId: Long,
    isInviter: Boolean,
    onNavigateToInput: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToStep: (Long, MediationStep) -> Unit = { _, _ -> },
    statusFrames: Flow<String> = emptyFlow(),
    viewModel: MediationInviteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 契约 §2.3-2：GET {id} 的 my_role 是角色真源；后端未落地/拉取失败时降级回导航参数。
    val effectiveIsInviter = uiState.isInviter ?: isInviter

    LaunchedEffect(sessionId) {
        if (sessionId > 0) {
            viewModel.setSessionId(sessionId)
            viewModel.loadSession(sessionId)
            // §8.5-1：发起方等待页必须能获知对方接受——WS 状态帧 + 可靠轮询双保险。
            viewModel.startWaiting(sessionId)
        }
    }

    // §2.3-4 推送侧：服务端状态帧到达时立即推进（不必等下一次轮询）。
    LaunchedEffect(sessionId) {
        statusFrames.collect { viewModel.onStatusFrame(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            val id = uiState.sessionId ?: return@collect
            when (event) {
                // 我自己点了接受：直接去写自己的部分
                is MediationInviteUiEvent.Accepted -> onNavigateToInput(id)
                is MediationInviteUiEvent.Rejected -> onNavigateBack()
                // §8.5-1：等待期间对方推进了（接受了 / 已经一路写完）。
                // 落点由服务端状态裁决，不假设「对方接受 = 双方都去输入页」。
                is MediationInviteUiEvent.MoveTo -> onNavigateToStep(id, event.step)
                is MediationInviteUiEvent.ShowError -> {}
            }
        }
    }

    // 离开本页（返回/进入下一屏）停止轮询，别让 ViewModel 在后台空转。
    DisposableEffect(sessionId) {
        onDispose { viewModel.stopWaiting() }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "调解邀请")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(color = AppAccent)
                Spacer(modifier = Modifier.height(16.dp))
            }

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                contentPadding = PaddingValues(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (effectiveIsInviter) {
                        Text(
                            text = "等待对方接受邀请...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "邀请已发送，请耐心等待对方接受",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                            textAlign = TextAlign.Center,
                        )
                        // §8.5-1：状态变化会自动刷新（WS 推送 + 轮询），
                        // 让用户知道「不用退出去重进」，也说明页面是活的。
                        // 整改 B4.1-5：窗口用尽后不再自称「刷新中」——那时轮询
                        // 确实停了，说在刷新是骗人的；改由「继续等待」按钮承接。
                        Text(
                            text = when {
                                uiState.waitingPaused -> MediationPolling.HUMAN_WAIT_PAUSED_NOTICE
                                uiState.isPolling -> "对方接受后这里会自动进入下一步"
                                else -> "状态刷新中…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary,
                            textAlign = TextAlign.Center,
                        )
                        // 整改 B4.1-5：轮询窗口用尽后由用户决定要不要再等一轮。
                        // 没有这个按钮，用户就只能看着一句「暂停」然后退出 App。
                        if (uiState.waitingPaused) {
                            AppPrimaryButton(
                                text = "继续等待",
                                onClick = { viewModel.resumeWaiting() },
                                enabled = !uiState.isLoading,
                            )
                        }
                    } else {
                        Text(
                            text = "你收到了调解邀请",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "对方希望和你一起进行双人调解，帮助你们更好地沟通",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextSecondary,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        AppPrimaryButton(
                            text = "接受邀请",
                            onClick = { viewModel.acceptInvite() },
                            enabled = !uiState.isLoading,
                        )

                        AppSecondaryButton(
                            text = "暂不接受",
                            onClick = { viewModel.rejectInvite() },
                            enabled = !uiState.isLoading,
                        )
                    }
                }
            }

            if (uiState.error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun MediationInputScreen(
    sessionId: Long,
    onMoveToStep: (Long, MediationStep) -> Unit,
    onNavigateBack: () -> Unit,
    statusFrames: Flow<String> = emptyFlow(),
    viewModel: MediationInputViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.setSessionId(sessionId)
        // §8.5-7：断线重进按服务端状态恢复——已经写过的人不该再看到一张空表单。
        viewModel.loadSession()
    }

    // key 里必须带上 submitted：§8.5-7 的重进恢复是「loadSession 回来才知道
    // 我提交过」，而那一刻 status 可能没变（仍是 inputting）——只用 status 做 key
    // 的话这次恢复不会触发，用户会又看到一张空白表单并再提交一遍。
    LaunchedEffect(uiState.status, uiState.submitted) {
        // 服务端在我等待期间推进（对方写完 / 开始改写 / 已进确认）→ 由 MediationFlow 裁决落点
        if (uiState.submitted) {
            val step = MediationFlow.afterSubmit(uiState.status)
            when (step) {
                MediationStep.WAITING_PARTNER -> viewModel.startWaiting()
                else -> onMoveToStep(sessionId, step)
            }
        }
    }

    // §2.3-4 推送侧：状态帧到达时直接用它更新（轮询是兜底）
    LaunchedEffect(sessionId) {
        statusFrames.collect { viewModel.onStatusFrame(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is MediationInputUiEvent.MoveTo -> onMoveToStep(sessionId, event.step)
                // 提交成功本身不跳转：落点由上面的 status 分支决定（§8.5-2）
                is MediationInputUiEvent.InputSubmitted -> Unit
                is MediationInputUiEvent.ShowError -> Unit
            }
        }
    }

    DisposableEffect(sessionId) {
        onDispose { viewModel.stopWaiting() }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "表达你的感受")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.submitted) {
                // ---- §8.5-2 等待态：我是第一方（或双方都写完、AI 正在改写）----
                // 整改 B4.1-4：失败态**不在本页**停：改写的归宿是确认页，那一页
                // 才带完整的失败卡片与「重试」。上面的 LaunchedEffect 已经把
                // `rewrite_failed` 交给 MediationFlow 判成 FAILED_REWRITE 并导航过去，
                // 本页只留「窗口用尽暂停」这一种需要用户介入的形态。
                if (uiState.waitingPaused) {
                    WaitingPausedCard(onResume = viewModel::resumeWaiting)
                } else {
                    WaitingCard(
                        title = waitHeadlineOf(uiState.generation, summarizing = false)
                            .ifBlank { "已提交，等对方写下 TA 的感受" },
                        subtitle = if (uiState.isGenerating) {
                            "通常需要十几秒，好了会自动进入确认页"
                        } else {
                            "对方提交后，AI 会同时为你们生成改写"
                        },
                    )
                }
            } else {
                Text(
                    text = "请写下你的感受和诉求，AI 会帮你改写为对方更容易接受的表达",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                )

                if (uiState.partnerSubmitted) {
                    Text(
                        text = "对方已经写好了，等你提交",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppAccent,
                    )
                }

                InputField(
                    label = "我现在的感受",
                    value = uiState.feeling,
                    onValueChange = viewModel::onFeelingChange,
                    placeholder = "比如：我觉得很委屈、很受伤...",
                )

                InputField(
                    label = "触发我的事情",
                    value = uiState.trigger,
                    onValueChange = viewModel::onTriggerChange,
                    placeholder = "比如：当你说了那句话的时候...",
                )

                InputField(
                    label = "我希望你理解的",
                    value = uiState.wishUnderstood,
                    onValueChange = viewModel::onWishUnderstoodChange,
                    placeholder = "比如：我不是在无理取闹...",
                )

                InputField(
                    label = "我希望接下来可以怎样",
                    value = uiState.wishNext,
                    onValueChange = viewModel::onWishNextChange,
                    placeholder = "比如：下次我们可以先冷静一下再聊...",
                )

                AppPrimaryButton(
                    text = "提交",
                    onClick = viewModel::submitInput,
                    enabled = uiState.feeling.isNotBlank() && uiState.trigger.isNotBlank() && !uiState.isLoading,
                )
            }

            if (uiState.error.isNotEmpty()) {
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 等待态卡片：调解流程里所有「等对方 / 等 AI」的页面共用同一套视觉。 */
@Composable
private fun WaitingCard(title: String, subtitle: String) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = AppAccent)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 整改 B4.1-4：生成失败的**独立卡片**。
 *
 * 为什么不能沿用 [WaitingCard] 再换行字：那会得到一个转圈图标配「失败了」的
 * 自相矛盾的卡片，用户读到的仍是「它在忙」。失败必须是**另一种形态**——
 * 没有转圈、有原因、有出路。
 *
 * @param onRetry 允许手动重试时给的回调；null 表示这一步的重试在别处（如确认页）
 * @param onRestart 重新发起一场调解（永远给：只给重试的失败页会把用户堵死）
 */
@Composable
private fun GenerationFailureCard(
    state: MediationGenerationState,
    fallbackMessage: String,
    onRetry: (() -> Unit)?,
    onRestart: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = waitHeadlineOf(state, summarizing = false).ifBlank { "这次没生成成功" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.failureMessage?.takeIf { it.isNotBlank() } ?: fallbackMessage,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Text(
                text = "双方写下的内容都还在，不会被这次失败清掉",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            if (onRetry != null && state.canRetry) {
                AppPrimaryButton(text = "重试", onClick = onRetry)
                Spacer(modifier = Modifier.height(8.dp))
            }
            AppSecondaryButton(text = "重新发起", onClick = onRestart)
        }
    }
}

/**
 * 整改 B4.1-5：等「人」的窗口用尽 → **暂停**（不是失败）。
 *
 * 对方可能在上班、在睡觉；等不到不等于坏了。这里给一个明确的「继续等待」
 * 按钮，让用户自己决定要不要再等一轮——而不是让页面无限轮询打接口，
 * 也不是把「对方还没回应」说成「出错了」。
 */
@Composable
private fun WaitingPausedCard(
    onResume: () -> Unit,
    title: String = "还在等对方",
    notice: String = MediationPolling.HUMAN_WAIT_PAUSED_NOTICE,
    actionText: String = "继续等待",
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            AppPrimaryButton(text = actionText, onClick = onResume)
        }
    }
}

@Composable
fun MediationConfirmScreen(
    sessionId: Long,
    onMoveToStep: (Long, MediationStep) -> Unit,
    onNavigateBack: () -> Unit,
    statusFrames: Flow<String> = emptyFlow(),
    /**
     * 整改 B4.1-4：失败后的「重新发起」出口（去说明页新建一场）。
     *
     * 只给「重试」而没有「重新发起」时，一场彻底失败的调解会把用户堵死在
     * 这一页上——重试若还失败，他就只能退出 App。旧会话保留在调解回看里，
     * 双方写下过的内容不会因为重新发起而消失。
     */
    onRestartMediation: () -> Unit = {},
    viewModel: MediationConfirmViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.setSessionId(sessionId)
        viewModel.loadRewrite()
    }

    // §8.5-8：生成中（rewriting/summarizing）时就地轮询等结果，别让用户对着空白页。
    // 整改 B4.1-4：**失败态不轮询**——isGenerating 只在 GENERATING/RETRYING 时为真，
    // 终态失败进不来，于是失败卡片上的「重试」按钮是唯一的出路，不会被
    // 一轮又一轮的空轮询掩盖。
    LaunchedEffect(uiState.isGenerating, uiState.status) {
        if (uiState.isGenerating) {
            viewModel.pollUntilReady()
        }
    }

    LaunchedEffect(sessionId) {
        statusFrames.collect { viewModel.onStatusFrame(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                // §8.5-5：只有双方确认齐了才会发 Confirmed
                is MediationConfirmUiEvent.Confirmed -> onMoveToStep(sessionId, MediationStep.RESULT)
                is MediationConfirmUiEvent.ShowError -> Unit
            }
        }
    }

    DisposableEffect(sessionId) {
        onDispose { viewModel.stopPolling() }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "确认改写")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "AI 将你的表达改写为对方更容易接受的版本",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )

            if (uiState.isLoading || uiState.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = AppAccent,
                )
                if (uiState.isGenerating) {
                    Text(
                        text = waitHeadlineOf(uiState.generation, summarizing = false),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 整改 B4.1-4：失败是**另一种形态**，不是继续转圈的等待。
            // 注意顺序：失败卡片在改写卡片之前——失败时改写多半还是空的，
            // 让用户先看到「为什么没有」再看到已有内容，比反过来好读。
            if (uiState.generation.phase == MediationGenerationPhase.FAILED) {
                GenerationFailureCard(
                    state = uiState.generation,
                    fallbackMessage = "改写没生成成功",
                    onRetry = viewModel::retryGeneration,
                    onRestart = onRestartMediation,
                )
            } else if (uiState.generation.phase == MediationGenerationPhase.RETRYING) {
                // 自动重试中：给等待卡片而不是失败卡片——用户此刻不需要做任何事。
                WaitingCard(
                    title = waitHeadlineOf(uiState.generation, summarizing = false),
                    subtitle = "不用手动操作，下一秒会自动再试一次",
                )
            } else if (uiState.pollingBudgetExhausted) {
                // 窗口用尽**不是失败**：状态在服务端，重进一定看得到。
                WaitingPausedCard(
                    notice = MediationPolling.BUDGET_EXHAUSTED_NOTICE,
                    actionText = "刷新看看",
                    onResume = viewModel::loadRewrite,
                )
            }

            uiState.myRewrite?.let { rewrite ->
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.md),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "你的原话",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTextSecondary,
                        )
                        Text(
                            text = rewrite.original,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "AI 改写后",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppAccent,
                        )
                        Text(
                            text = rewrite.rewritten,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // §8.5-5：双方各自的确认进度。只我确认时必须显示「等对方确认」，
            // 而不是让按钮看起来还没点过（用户会反复点，也以为对方已经确认了）。
            ConfirmProgressRow(
                myConfirmed = uiState.myConfirmed,
                partnerConfirmed = uiState.partnerConfirmed,
            )

            if (uiState.waitingPartner) {
                WaitingCard(
                    title = "已确认，等对方确认",
                    subtitle = "双方都确认后，AI 会生成你们的沟通总结",
                )
            } else {
                TextInputField(
                    value = uiState.supplement,
                    onValueChange = viewModel::onSupplementChange,
                    label = "补充说明（可选）",
                    placeholder = "如果改写不够准确，可以补充...",
                    singleLine = false,
                )

                AppPrimaryButton(
                    text = "确认准确",
                    onClick = { viewModel.confirm(true) },
                    enabled = !uiState.isLoading && !uiState.isGenerating,
                )

                AppSecondaryButton(
                    text = "需要修改",
                    onClick = { viewModel.confirm(false) },
                    enabled = !uiState.isLoading && !uiState.isGenerating,
                )
                Text(
                    text = "需要修改只会重新改写你这一侧，对方的表达不会被改动",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                )
            }

            if (uiState.error.isNotEmpty()) {
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 双方确认进度（§8.5-5）：两行对称展示，谁确认了一目了然。 */
@Composable
private fun ConfirmProgressRow(myConfirmed: Boolean, partnerConfirmed: Boolean) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (myConfirmed) "✓ 你已确认" else "○ 等你确认",
                style = MaterialTheme.typography.bodyMedium,
                color = if (myConfirmed) AppAccent else AppTextSecondary,
            )
            Text(
                text = if (partnerConfirmed) "✓ 对方已确认" else "○ 对方还没确认",
                style = MaterialTheme.typography.bodyMedium,
                color = if (partnerConfirmed) AppAccent else AppTextSecondary,
            )
        }
    }
}

@Composable
fun MediationResultScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit,
    /** 整改 B4.1-4：总结失败的「重新发起」（去说明页新建一场；旧会话保留可回看）。 */
    onRestartMediation: () -> Unit = {},
    viewModel: MediationResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.setSessionId(sessionId)
        // §8.5-8：总结在服务端后台生成，本页负责等它（生成中显示进度、
        // 完成即自动出内容）。§8.5-6：回看历史时一次就拿到，不进入轮询。
        viewModel.startPollingUntilReady()
    }

    DisposableEffect(sessionId) {
        onDispose { viewModel.stopPolling() }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "调解结果")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.isLoading && !uiState.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = AppAccent,
                )
            }

            // §8.5-8：还在生成就明确说「在生成」，不要渲染成一片空白。
            if (uiState.isGenerating) {
                WaitingCard(
                    title = waitHeadlineOf(uiState.generation, summarizing = true)
                        .ifBlank { "AI 正在整理这场调解…" },
                    subtitle = "通常需要十几秒，好了这里会自动出现总结",
                )
            }

            // 整改 B4.1-4：总结失败**不是**「没有留下总结」——那是两件事，
            // 混成一件事会让用户以为「这场调解白谈了」，而实际上内容还在，
            // 只是这一步没跑成。失败卡片给重试，空态只给「确实没有」。
            if (uiState.generation.phase == MediationGenerationPhase.FAILED) {
                GenerationFailureCard(
                    state = uiState.generation,
                    fallbackMessage = "总结没生成成功",
                    onRetry = viewModel::retryGeneration,
                    onRestart = onRestartMediation,
                )
            } else if (uiState.pollingBudgetExhausted) {
                // 窗口用尽 ≠ 失败：服务端还在跑，状态在服务端，重进一定看得到。
                WaitingPausedCard(
                    title = "总结还在生成",
                    notice = MediationPolling.BUDGET_EXHAUSTED_NOTICE,
                    actionText = "刷新看看",
                    onResume = viewModel::loadResult,
                )
            }

            if (uiState.isEmpty) {
                AppEmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "这次调解没有留下总结",
                    subtitle = "可能是生成中断了，可以回到调解室重新发起",
                )
            }

            if (uiState.commonPoints.isNotEmpty()) {
                ResultSection(
                    title = "共同点",
                    items = uiState.commonPoints,
                    accentColor = AppAccent,
                )
            }

            if (uiState.diffPoints.isNotEmpty()) {
                ResultSection(
                    title = "分歧点",
                    items = uiState.diffPoints,
                    accentColor = AppTextSecondary,
                )
            }

            if (uiState.nextActions.isNotEmpty()) {
                ResultSection(
                    title = "下一步行动",
                    items = uiState.nextActions,
                    accentColor = AppAccent,
                )
            }

            // §8.5-6：回看历史时不再出现「选择下一步」——那是对刚谈完的这场
            // 做决定，翻出一个月前的总结还能点「结束调解」是错的。
            if (uiState.isCompleted) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "选择下一步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                AppPrimaryButton(
                    text = "继续沟通",
                    onClick = { viewModel.chooseNextAction("continue") },
                    enabled = !uiState.isLoading,
                )

                AppSecondaryButton(
                    text = "暂停一下",
                    onClick = { viewModel.chooseNextAction("pause") },
                    enabled = !uiState.isLoading,
                )

                AppSecondaryButton(
                    text = "结束调解",
                    onClick = { viewModel.chooseNextAction("end") },
                    enabled = !uiState.isLoading,
                )
            }

            if (uiState.error.isNotEmpty()) {
                Text(
                    text = uiState.error,
                    color = AppErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultSection(
    title: String,
    items: List<String>,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextInputField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            singleLine = false,
        )
    }
}
