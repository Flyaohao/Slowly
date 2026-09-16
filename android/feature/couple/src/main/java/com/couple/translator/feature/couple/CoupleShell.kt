package com.couple.translator.feature.couple

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.couple.translator.feature.couple.data.repository.CoupleState
import com.couple.translator.feature.couple.data.repository.CoupleStateManager
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.navigation.Screen
import com.couple.translator.feature.couple.ai.NewAiChatScreen
import com.couple.translator.feature.couple.home.NewHomeScreen
import com.couple.translator.feature.couple.letter.NewMailboxScreen
import com.couple.translator.feature.couple.navigation.DrawerContent
import com.couple.translator.feature.couple.network.RealtimeSocketManager
import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.ui.components.BottomTabBar
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.theme.AppBackground
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
) {
    val tabNavController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 顶栏叠头像的唯一数据源：三个 tab 共用，避免首页真头像 / 其他页"我"字的分叉
    val coupleState = coupleStateManager?.state?.collectAsState()?.value ?: remember { CoupleState() }
    val topBarIdentity = TopBarIdentity(
        userAvatarUrl = coupleState.userAvatarUrl,
        nickname = coupleState.userNickname,
        partnerAvatarUrl = coupleState.partnerAvatarUrl,
        partnerNickname = coupleState.partnerNickname,
    )

    // 实时通道：进入情侣模式建立 WS 连接，退出时断开；事件转 Snackbar 提示
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        realtimeSocketManager?.start()
    }
    DisposableEffect(Unit) {
        onDispose { realtimeSocketManager?.stop() }
    }
    LaunchedEffect(realtimeSocketManager) {
        realtimeSocketManager?.events?.collect { event ->
            val text = when (event.notificationType) {
                "companion_request" -> "对方发来了陪伴请求" + (event.content?.let { "：$it" } ?: "")
                "partner_moment" -> "对方分享了此刻状态" + (event.content?.let { "：$it" } ?: "")
                "unbind_requested" -> "对方发起了关系解绑请求"
                "unbind_confirmed" -> "关系解绑已完成"
                "unbind_cancelled" -> "对方取消了解绑申请"
                "letter_received" -> "收到一封新信件"
                "mediation_invite" -> "对方邀请你进行冷静沟通"
                else -> null
            }
            if (text != null) {
                snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Short)
                coupleStateManager?.refresh()
            }
        }
    }

    // 每次回到前台时刷新情侣状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { coupleStateManager?.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    // 情侣模式 Tab
    val tabs = BottomTab.entries.filter { it != BottomTab.SingleHome && it != BottomTab.Diary }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = AppBackground) {
                DrawerContent(
                    onNavigateToRoute = { route ->
                        closeDrawer()
                        onNavigateToRoute(route)
                    },
                    onLogout = {
                        scope.launch {
                            tokenStore?.clearTokens()
                            realtimeSocketManager?.stop()
                            closeDrawer()
                            onLogout()
                        }
                    },
                    coupleStateManager = coupleStateManager,
                )
            }
        },
        gesturesEnabled = drawerState.isOpen,
    ) {
        Scaffold(
            containerColor = AppBackground,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                BottomTabBar(
                    currentRoute = currentRoute,
                    tabs = tabs,
                    onTabSelected = { tab ->
                        if (currentRoute != tab.route) {
                            tabNavController.navigate(tab.route) {
                                popUpTo(tabNavController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            NavHost(
                navController = tabNavController,
                startDestination = BottomTab.Home.route,
                modifier = Modifier
                    .padding(innerPadding)
                    // 关键：把壳层已占用的 inset（tab 栏高度 + 系统栏）登记为「已消费」。
                    // 页内 imePadding() 只会补足「键盘高 − 已占用高」的差值，
                    // 底部实际预留 = max(innerPadding.bottom, 键盘高)，输入框在任意分辨率/
                    // 输入法下都恰好贴住键盘（键盘盖住的 tab 栏不会再白占一份高度）。
                    .consumeWindowInsets(innerPadding),
                // Tab 之间是「平级切换」而不是「推入新页面」，所以不做整屏横移：
                // 只给 1/5 屏的横向位移 + 淡入，方向由 tab 在底栏里的先后顺序决定。
                // 旧版是纯 fadeIn/fadeOut，没有方向感，切换时像画面"闪"了一下。
                enterTransition = {
                    val forward = tabIndexOf(tabs, targetState.destination.route) >=
                        tabIndexOf(tabs, initialState.destination.route)
                    slideInHorizontally(
                        animationSpec = tween(durationMillis = 300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                        initialOffsetX = { width -> if (forward) width / 5 else -width / 5 },
                    ) + fadeIn(animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing))
                },
                exitTransition = {
                    val forward = tabIndexOf(tabs, targetState.destination.route) >=
                        tabIndexOf(tabs, initialState.destination.route)
                    slideOutHorizontally(
                        animationSpec = tween(durationMillis = 300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                        targetOffsetX = { width -> if (forward) -width / 5 else width / 5 },
                    ) + fadeOut(animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing))
                },
                popEnterTransition = {
                    val forward = tabIndexOf(tabs, targetState.destination.route) >=
                        tabIndexOf(tabs, initialState.destination.route)
                    slideInHorizontally(
                        animationSpec = tween(durationMillis = 300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                        initialOffsetX = { width -> if (forward) width / 5 else -width / 5 },
                    ) + fadeIn(animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing))
                },
                popExitTransition = {
                    val forward = tabIndexOf(tabs, targetState.destination.route) >=
                        tabIndexOf(tabs, initialState.destination.route)
                    slideOutHorizontally(
                        animationSpec = tween(durationMillis = 300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                        targetOffsetX = { width -> if (forward) -width / 5 else width / 5 },
                    ) + fadeOut(animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing))
                },
            ) {
                composable(BottomTab.Home.route) {
                    NewHomeScreen(
                        onOpenDrawer = openDrawer,
                        onNavigateToComposeLetter = {
                            onNavigateToRoute("compose_letter")
                        },
                        onNavigateToMailbox = {
                            tabNavController.navigate(BottomTab.Mailbox.route) {
                                popUpTo(tabNavController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToAiChat = {
                            tabNavController.navigate(BottomTab.AiChat.route) {
                                popUpTo(tabNavController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToLetterDetail = { letterId ->
                            onNavigateToRoute("letter_detail/$letterId")
                        },
                        onNavigateToBind = {
                            onNavigateToRoute(Screen.CoupleBind.route)
                        },
                        isCoupleMode = true,
                        identity = topBarIdentity,
                    )
                }

                composable(BottomTab.Mailbox.route) {
                    NewMailboxScreen(
                        onOpenDrawer = openDrawer,
                        onNavigateToCompose = {
                            onNavigateToRoute("compose_letter")
                        },
                        onNavigateToLetterList = {
                            onNavigateToRoute("letter_list")
                        },
                        onNavigateToLetterDetail = { letterId ->
                            onNavigateToRoute("letter_detail/$letterId")
                        },
                        isCoupleMode = true,
                        identity = topBarIdentity,
                    )
                }

                composable(BottomTab.AiChat.route) {
                    NewAiChatScreen(
                        onOpenDrawer = openDrawer,
                        onNavigateToSessionList = {
                            onNavigateToRoute("ai_session_list")
                        },
                        onNavigateToMediation = {
                            onNavigateToRoute("mediation_explanation")
                        },
                        onNavigateToReview = {
                            onNavigateToRoute("relationship_review")
                        },
                        identity = topBarIdentity,
                    )
                }
            }
        }
    }
}

/** tab 在底栏里的先后位置，用来决定转场方向；未知路由按最左处理。 */
private fun tabIndexOf(tabs: List<BottomTab>, route: String?): Int =
    tabs.indexOfFirst { it.route == route }.coerceAtLeast(0)
