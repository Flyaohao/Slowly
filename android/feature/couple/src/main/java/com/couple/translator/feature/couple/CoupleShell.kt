package com.couple.translator.feature.couple

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.ui.components.BottomTabBar
import com.couple.translator.core.ui.theme.Background
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
) {
    val tabNavController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val defaultCoupleState = androidx.compose.runtime.remember { CoupleState() }
    val coupleState = coupleStateManager?.state?.collectAsState()?.value ?: defaultCoupleState

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
            ModalDrawerSheet(drawerContainerColor = Background) {
                DrawerContent(
                    onNavigateToRoute = { route ->
                        closeDrawer()
                        onNavigateToRoute(route)
                    },
                    onLogout = {
                        scope.launch {
                            tokenStore?.clearTokens()
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
            containerColor = Background,
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
                modifier = Modifier.padding(innerPadding),
                enterTransition = { fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) },
                exitTransition = { fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) },
                popExitTransition = { fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) },
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
                    )
                }
            }
        }
    }
}
