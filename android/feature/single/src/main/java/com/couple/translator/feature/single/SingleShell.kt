package com.couple.translator.feature.single

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.ui.components.BottomTabBar
import com.couple.translator.core.ui.components.TopBarIdentity
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.feature.single.diary.DiaryListScreen
import kotlinx.coroutines.launch

/**
 * 单身模式外壳
 * 管理单身模式的 Tab、抽屉和导航
 */
@Composable
fun SingleShell(
    onNavigateToRoute: (String) -> Unit,
    onLogout: () -> Unit,
    tokenStore: TokenStore? = null,
    viewModel: SingleHomeViewModel = hiltViewModel(),
) {
    val tabNavController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val uiState by viewModel.uiState.collectAsState()

    // 顶栏叠头像来源，与情侣模式同一套语义
    val topBarIdentity = TopBarIdentity(
        userAvatarUrl = uiState.avatarUrl,
        nickname = uiState.nickname,
    )

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    // 单身模式 Tab
    val tabs = listOf(BottomTab.SingleHome, BottomTab.Diary)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = AppBackground) {
                SingleDrawerContent(
                    nickname = uiState.nickname,
                    onNavigateToRoute = { route ->
                        closeDrawer()
                        onNavigateToRoute(route)
                    },
                    onNavigateToBind = {
                        closeDrawer()
                        onNavigateToRoute(Screen.CoupleBind.route)
                    },
                    onNavigateToProfile = {
                        closeDrawer()
                        onNavigateToRoute(Screen.Profile.route)
                    },
                    onLogout = {
                        scope.launch {
                            tokenStore?.clearTokens()
                            closeDrawer()
                            onLogout()
                        }
                    },
                )
            }
        },
        gesturesEnabled = drawerState.isOpen,
    ) {
        Scaffold(
            containerColor = AppBackground,
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
                startDestination = BottomTab.SingleHome.route,
                modifier = Modifier
                    .padding(innerPadding)
                    // 同情侣侧：登记已占用 inset，页内 imePadding 只补差值，输入框永远贴键盘
                    .consumeWindowInsets(innerPadding),
                // 与情侣模式外壳同款：1/5 屏方向感横移 + 淡入，取代纯 fade 的"闪一下"
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
                composable(BottomTab.SingleHome.route) {
                    SingleHomeScreen(
                        onOpenDrawer = openDrawer,
                        onNavigateToQuestionnaire = {
                            onNavigateToRoute(Screen.QuestionnaireIntro.route)
                        },
                        onNavigateToProfile = {
                            onNavigateToRoute(Screen.ProfileResult.route)
                        },
                        onNavigateToBind = {
                            onNavigateToRoute(Screen.CoupleBind.route)
                        },
                        onNavigateToDiary = {
                            tabNavController.navigate(BottomTab.Diary.route) {
                                popUpTo(tabNavController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        identity = topBarIdentity,
                    )
                }

                composable(BottomTab.Diary.route) {
                    DiaryListScreen(
                        onOpenDrawer = openDrawer,
                        onNavigateToDetail = { diaryId ->
                            onNavigateToRoute("diary_detail/$diaryId")
                        },
                        onNavigateToCompose = {
                            onNavigateToRoute(Screen.ComposeDiary.route)
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
