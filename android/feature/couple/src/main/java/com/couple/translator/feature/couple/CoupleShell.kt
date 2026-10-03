@file:OptIn(ExperimentalFoundationApi::class)

package com.couple.translator.feature.couple

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.feature.couple.data.repository.CoupleState
import com.couple.translator.feature.couple.data.repository.CoupleStateManager
import com.couple.translator.core.data.repository.NotificationPermissionStore
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.notification.AppNotifications
import com.couple.translator.feature.couple.ai.NewAiChatScreen
import com.couple.translator.feature.couple.home.NewHomeScreen
import com.couple.translator.feature.couple.navigation.DrawerContent
import com.couple.translator.feature.couple.navigation.ShellLandingHolder
import com.couple.translator.feature.couple.network.RealtimeSocketManager
import com.couple.translator.feature.couple.relation.ObservationViewModel
import com.couple.translator.feature.couple.relation.TodoViewModel
import com.couple.translator.feature.couple.network.toNotice
import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.ui.components.BottomTabBar
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppMotion
import kotlinx.coroutines.launch

/**
 * 情侣模式外壳
 * 管理情侣模式的 Tab、抽屉和导航
 */
@Composable
fun CoupleShell(
    onNavigateToRoute: (String) -> Unit,
    onLogout: () -> Unit,
    tokenStore: TokenStore? = null,
    coupleStateManager: CoupleStateManager? = null,
    realtimeSocketManager: RealtimeSocketManager? = null,
    notificationPermissionStore: NotificationPermissionStore? = null,
) {
    val scope = rememberCoroutineScope()

    // 2026-09-29 重构（用户拍板）：一级页收敛为「军师 + 空间」两个 tab——
    // pager 横条 [抽屉 0] [军师 1] [空间 2]。抽屉仍是推入式同层页（页 0，不占底栏位），
    // 一级页之间手指跟拖自然滑动；军师页右滑顺势把抽屉「推」出来。
    //
    // 页序即底栏顺序 +1：军师在首位（冷启动/登录后默认落在军师对话页），
    // 空间第 2 位（「我们的空间」顶替原「关系」tab 的位置）。
    // 原 [抽屉0][空间1][军师2][关系3] 的 4 页结构与 RelationScreen 一并删除。
    val pagerState = rememberPagerState(initialPage = 1, initialPageOffsetFraction = 0f) { 3 }

    // Pager 当前页映射回「路由字符串」：抽屉高亮与底栏高亮仍吃 route 语义，
    // DrawerContent / BottomTabBar 的入参契约一字不动。
    val currentRoute = when (pagerState.currentPage) {
        1 -> BottomTab.AiChat.route
        2 -> BottomTab.Home.route
        else -> null
    }

    // 顶栏叠头像的唯一数据源：三个 tab 共用，避免首页真头像 / 其他页"我"字的分叉
    val coupleState = coupleStateManager?.state?.collectAsState()?.value ?: remember { CoupleState() }
    val topBarIdentity = TopBarIdentity(
        userAvatarUrl = coupleState.userAvatarUrl,
        nickname = coupleState.userNickname,
        partnerAvatarUrl = coupleState.partnerAvatarUrl,
        partnerNickname = coupleState.partnerNickname,
    )

    // 2026-09-27 关系页改版：待办（调解邀请 / 双视角 / 解绑确认）角标的数据源。
    // 壳层持有，抽屉开几次都共用同一份；ON_RESUME 刷新保证回到前台角标不滞后。
    val todoViewModel: TodoViewModel = hiltViewModel()
    val todoState by todoViewModel.uiState.collectAsState()

    // 2026-09-28 军师主动观察（F-5 拍板：角标 = 新观察 + 待办合计）。
    // 2026-09-29：关系页删除后，角标由「关系 tab」迁移到「空间 tab」
    // （观察卡与待办入口现在都归属空间页 / 抽屉）。
    // Activity 作用域：与空间页的观察卡共用同一实例（见 ObservationViewModel 类注释），
    // ack 后角标经 StateFlow 同步清零。壳层必须自己拉一次（init + ON_RESUME）：
    // 用户停在军师 tab 时新观察也要亮角标——但只读不 ack，「已读」只能发生在
    // 用户打开空间页时。
    val observationViewModel: ObservationViewModel =
        hiltViewModel(LocalContext.current as ComponentActivity)
    val observationState by observationViewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { observationViewModel.load() }
    val homeBadgeCount = todoState.pendingCount +
        if (observationState.isNewForBadge) 1 else 0

    // 实时通道：进入情侣模式建立 WS 连接，退出时断开；事件转 Snackbar + 系统通知栏
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    // 通知权限：只在首次进入情侣空间时问一次。用户拒绝也不影响任何主流程，
    // 只是收不到通知栏提醒（邮件通道不受影响）。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 同意与拒绝的处理完全一致：什么都不做 */ }

    LaunchedEffect(Unit) {
        AppNotifications.ensureChannel(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val store = notificationPermissionStore
            if (store != null && !store.hasAsked()) {
                // 先落标记再申请：申请回调可能在进程重建后才回来，
                // 若等回调再写标记，冷启动 + 被杀会重复弹窗。
                store.markAsked()
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        realtimeSocketManager?.start()
    }
    DisposableEffect(Unit) {
        onDispose { realtimeSocketManager?.stop() }
    }
    LaunchedEffect(realtimeSocketManager) {
        realtimeSocketManager?.events?.collect { event ->
            val notice = event.toNotice()
            if (notice != null) {
                val notificationId = notice.notificationId
                if (notificationId != null) {
                    AppNotifications.notify(
                        context = appContext,
                        notificationId = notificationId,
                        title = notice.notificationTitle ?: notice.snackbarText,
                        text = notice.notificationText ?: notice.snackbarText,
                        route = notice.route,
                    )
                }
                snackbarHostState.showSnackbar(notice.snackbarText, duration = SnackbarDuration.Short)
                coupleStateManager?.refresh()
            }
        }
    }

    // 每次回到前台时刷新情侣状态 + 待办角标
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { coupleStateManager?.refresh() }
                todoViewModel.load()
                observationViewModel.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openDrawer: () -> Unit = { scope.launch { pagerState.animateScrollToPage(0) } }
    val closeDrawer: () -> Unit = { scope.launch { pagerState.animateScrollToPage(1) } }

    // 情侣模式 Tab（2026-09-29 重构，用户拍板）：军师 + 空间，共 2 个。
    // 军师在首位（默认首屏）；空间第 2 位（顶替已删除的「关系」tab）。
    // 信箱不在底栏（隐藏 ≠ 删除）：二级页 Screen.Mailbox 注册在根 NavGraph，
    // 从抽屉「信箱」/ 空间页的「共同记录」宫格进入。
    val tabs = listOf(BottomTab.AiChat, BottomTab.Home)

    // 军师沉浸模式：隐藏底部 Tab 栏换取更大对话空间（用户 2026-09-29 拍板保留该 chip）。
    // 只在军师页（pager 页 1）生效（切走自动恢复），入口是输入框下方 chip。
    var hideTabBar by rememberSaveable { mutableStateOf(false) }
    val isTabBarHidden = hideTabBar && pagerState.currentPage == 1

    // 抽屉页打开时，系统返回一律回到军师页（默认首屏）——等价旧内层 NavHost 的 pop 行为。
    BackHandler(enabled = pagerState.currentPage != 1) {
        scope.launch { pagerState.animateScrollToPage(1) }
    }

    // 跨组件一次性落页请求（2026-09-29）：「进入我们的空间」等外部入口在
    // rememberPagerState 保留了上次 tab 的情况下，仍要精确落到目标页。
    //
    // 2026-10-03 修 BUG：点「进入我们的空间」却落在军师页。
    // 原因是**动画竞态**——调用方（关系设置页）先 `request()` 再 `navigateToMain()`，
    // 壳层是被导航回来的，此时 Pager 还没完成布局，
    // `animateScrollToPage` 的动画在布局前发出会被静默丢弃，
    // 页面停在 `initialPage = 1`（军师）。而且 consume() 已经把标志清了，
    // 没有第二次机会，于是永久停在错误页。
    //
    // 修法两点：
    //   1) 用 `scrollToPage`（瞬时跳转）而不是 `animateScrollToPage`——
    //      瞬时跳转不依赖动画帧，布局未完成时也能正确设置目标页；
    //   2) 已经在目标页时直接返回，避免无谓的二次滚动。
    val landing by ShellLandingHolder.pending.collectAsState()
    LaunchedEffect(landing) {
        landing?.let { page ->
            ShellLandingHolder.consume()
            if (pagerState.currentPage != page.index) {
                pagerState.scrollToPage(page.index)
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // A3（全局 UI/UX 方案）：沉浸模式底栏滑出/滑入，不再瞬间消失/出现；
            // 抽屉页（pager 页 0）没有底栏。
            AnimatedVisibility(
                visible = pagerState.currentPage != 0 && !isTabBarHidden,
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = AppMotion.slow, easing = AppMotion.EaseOut),
                    initialOffsetY = { it },
                ) + fadeIn(animationSpec = tween(AppMotion.normal)),
                exit = slideOutVertically(
                    animationSpec = tween(durationMillis = AppMotion.slow, easing = AppMotion.EaseOut),
                    targetOffsetY = { it },
                ) + fadeOut(animationSpec = tween(AppMotion.fast)),
            ) {
                BottomTabBar(
                    currentRoute = currentRoute,
                    tabs = tabs,
                    badges = if (homeBadgeCount > 0) {
                        mapOf(BottomTab.Home to homeBadgeCount)
                    } else {
                        emptyMap()
                    },
                    onTabSelected = { tab ->
                        // 页序 = 底栏序 +1（页 0 是抽屉）
                        val index = tabs.indexOf(tab)
                        if (index >= 0) {
                            scope.launch { pagerState.animateScrollToPage(index + 1) }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                // 关键：把壳层已占用的 inset（tab 栏高度 + 系统栏）登记为「已消费」。
                // 页内 imePadding() 只会补足「键盘高 − 已占用高」的差值，
                // 底部实际预留 = max(innerPadding.bottom, 键盘高)，输入框在任意分辨率/
                // 输入法下都恰好贴住键盘（键盘盖住的 tab 栏不会再白占一份高度）。
                .consumeWindowInsets(innerPadding),
            // foundation 1.6.8 无 beyondViewportPageCount（1.7+ 才有）：滑出视口的页
            // 会被销毁重组，两页 Screen 的「进页副作用」由 pageActive 门控兜住
            // （观察卡不会滑到一半就被 ack）；rememberSaveable 经 pager 的
            // SaveableStateHolder 按页保留，与旧 NavHost saveState/restoreState 等价。
        ) { page ->
            when (page) {
                0 -> DrawerPage(
                    currentRoute = currentRoute,
                    onNavigateToRoute = onNavigateToRoute,
                    // 2026-10-03：抽屉「我们的空间」条目已移除（空间页是底栏一级页），
                    // 原 onNavigateToHome 回调链一并删掉。
                    // 登出清理（clearTokens / clearCouple / stop socket）统一由 NavGraph
                    // 根级 performLogout 负责——此前这里就地清理，导致绑定页/设置页两条
                    // 出口漏清 token（2026-09-29 BUG）。壳层不再重复实现，只透传。
                    onLogout = onLogout,
                    coupleStateManager = coupleStateManager,
                    pendingCount = todoState.pendingCount,
                    onClose = closeDrawer,
                )
                1 -> NewAiChatScreen(
                    onOpenDrawer = openDrawer,
                    onNavigateToSessionList = {
                        onNavigateToRoute("ai_session_list")
                    },
                    onNavigateToMemory = {
                        onNavigateToRoute("memory")
                    },
                    onNavigateToMediation = {
                        onNavigateToRoute("mediation_explanation")
                    },
                    onNavigateToReview = {
                        onNavigateToRoute("relationship_review")
                    },
                    onNavigateToRoute = onNavigateToRoute,
                    // 整改 §8.3：反馈行的「填写实际结果」→ 待反馈页（带 messageId）
                    onNavigateToFeedbackOutcome = { messageId ->
                        onNavigateToRoute(
                            "${Screen.FeedbackOutcome.route}?messageId=$messageId",
                        )
                    },
                    // 整改 §8.2：「整理成一封信」→ 写信页预填这段表达
                    onNavigateToComposeLetter = { text ->
                        onNavigateToRoute(
                            "${Screen.ComposeLetter.route}?content=${Uri.encode(text)}",
                        )
                    },
                    identity = topBarIdentity,
                    tabBarVisible = !hideTabBar,
                    onToggleTabBar = { hideTabBar = !hideTabBar },
                    pageActive = pagerState.currentPage == 1,
                )
                2 -> NewHomeScreen(
                    onOpenDrawer = openDrawer,
                    // 「我们的空间」按回忆/生活向重设计（2026-09-29）：
                    // 共同时间线 / 共同记录宫格 / 在一起天数 / 军师的观察。
                    // 全部二级页统一走根导航压栈（信箱、观点、纪念日、纪念事件等）。
                    onNavigateToRoute = onNavigateToRoute,
                    onNavigateToComposeLetter = {
                        onNavigateToRoute(Screen.ComposeLetter.route)
                    },
                    onNavigateToMailbox = {
                        onNavigateToRoute(Screen.Mailbox.route)
                    },
                    onNavigateToAiChat = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    },
                    onNavigateToLetterDetail = { letterId ->
                        onNavigateToRoute("${Screen.LetterDetail.route}/$letterId")
                    },
                    identity = topBarIdentity,
                    pageActive = pagerState.currentPage == 2,
                )
            }
        }
    }
}

/**
 * 抽屉页（pager 页 0）：推入式抽屉。
 *
 * 2026-09-28 跟手滑动重构：抽屉从 ModalNavigationDrawer（覆盖式、手势被壳内
 * NavHost 抢占）改为 HorizontalPager 的第 0 页——三页横条 [抽屉][军师][空间]，
 * 军师页右滑整页跟手把抽屉「推」出来。布局 = 左侧抽屉面板 + 右侧半透明遮罩区
 * （点一下回军师页），保留旧抽屉的遮罩视觉语义。
 *
 * 宽度沿用 ModalDrawerSheet 同款策略：360dp 封顶、最宽占屏 85%。
 */
@Composable
private fun DrawerPage(
    currentRoute: String?,
    onNavigateToRoute: (String) -> Unit,
    onLogout: () -> Unit,
    coupleStateManager: CoupleStateManager?,
    pendingCount: Int,
    onClose: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val drawerWidth = minOf(360.dp, (configuration.screenWidthDp * 0.85f).dp)
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .background(AppBackground)
                .statusBarsPadding(),
        ) {
            DrawerContent(
                // R 系列：抽屉当前项高亮（route 对得上才亮）
                currentRoute = currentRoute,
                onNavigateToRoute = { route ->
                    onClose()
                    // 信箱等全部路由统一走根导航：二级页压栈全屏可返回，
                    // 无需壳内拦截。
                    onNavigateToRoute(route)
                },
                onLogout = onLogout,
                coupleStateManager = coupleStateManager,
                pendingCount = pendingCount,
            )
        }
        // 右侧遮罩区：点一下收抽屉（等价旧 ModalNavigationDrawer 的 scrim 点击）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        )
    }
}
