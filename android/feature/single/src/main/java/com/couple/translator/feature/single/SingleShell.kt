package com.couple.translator.feature.single

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.ui.components.BottomTabBar
import com.couple.translator.core.ui.theme.Background
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
) {
    val tabNavController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    // 单身模式 Tab
    val tabs = listOf(BottomTab.SingleHome, BottomTab.Diary)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Background) {
                SingleDrawerContent(
                    onNavigateToRoute = { route ->
                        closeDrawer()
                        onNavigateToRoute(route)
                    },
                    onNavigateToBind = {
                        closeDrawer()
                        onNavigateToRoute(Screen.CoupleBind.route)
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
                startDestination = BottomTab.SingleHome.route,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) },
                exitTransition = { fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) },
                popExitTransition = { fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) },
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
                        onNavigateToPractice = {
                            onNavigateToRoute(Screen.SelfPracticeList.route)
                        },
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
                    )
                }
            }
        }
    }
}
