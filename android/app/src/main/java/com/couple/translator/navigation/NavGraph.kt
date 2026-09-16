package com.couple.translator.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.couple.translator.core.data.repository.GuideStore
import com.couple.translator.core.data.repository.NotificationPermissionStore
import com.couple.translator.core.data.repository.ThemeStore
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.settings.SettingsViewModel
import com.couple.translator.core.ui.theme.ThemeMode
import com.couple.translator.feature.couple.data.repository.CoupleStateManager
import com.couple.translator.feature.couple.network.RealtimeSocketManager
import com.couple.translator.feature.couple.ai.AiSessionListScreen
import com.couple.translator.feature.couple.ai.ColdWarScreen
import com.couple.translator.feature.couple.ai.ReviewScreen
import com.couple.translator.feature.couple.anniversary.AddAnniversaryScreen
import com.couple.translator.feature.couple.anniversary.AnniversaryListScreen
import com.couple.translator.feature.couple.memorycard.MemoryCardScreen
import com.couple.translator.feature.couple.presence.PresenceScreen
import com.couple.translator.core.ui.auth.ForgotPasswordScreen
import com.couple.translator.core.ui.guide.GuideScreen
import com.couple.translator.core.ui.auth.LoginScreen
import com.couple.translator.core.ui.auth.RegisterScreen
import com.couple.translator.feature.couple.avatar.AvatarCustomizeScreen
import com.couple.translator.feature.couple.couplebind.CoupleBindScreen
import com.couple.translator.feature.couple.couplebind.CoupleInfoScreen
import com.couple.translator.feature.couple.dual.CreateDualEventScreen
import com.couple.translator.feature.couple.dual.DualPerspectiveDetailScreen
import com.couple.translator.feature.couple.dual.DualPerspectiveListScreen
import com.couple.translator.feature.couple.dual.SubmitRecordScreen
import com.couple.translator.feature.couple.letter.ComposeLetterScreen
import com.couple.translator.feature.couple.letter.LetterDetailScreen
import com.couple.translator.feature.couple.letter.LetterListScreen
import com.couple.translator.feature.couple.mediation.MediationExplanationScreen
import com.couple.translator.feature.couple.mediation.MediationConfirmScreen
import com.couple.translator.feature.couple.mediation.MediationInputScreen
import com.couple.translator.feature.couple.mediation.MediationInviteScreen
import com.couple.translator.feature.couple.mediation.MediationResultScreen
import com.couple.translator.feature.couple.museum.AddMuseumItemScreen
import com.couple.translator.feature.couple.museum.MuseumItemDetailScreen
import com.couple.translator.feature.couple.museum.MuseumScreen
import com.couple.translator.feature.couple.practice.PracticeDetailScreen
import com.couple.translator.feature.couple.practice.PracticeListScreen
import com.couple.translator.feature.couple.practice.PracticeResultScreen
import com.couple.translator.core.ui.profile.CoupleProfileScreen
import com.couple.translator.core.ui.profile.ProfileResultScreen
import com.couple.translator.core.ui.profile.ProfileScreen
import com.couple.translator.core.ui.questionnaire.QuestionnaireHistoryScreen
import com.couple.translator.core.ui.questionnaire.QuestionnaireIntroScreen
import com.couple.translator.core.ui.questionnaire.QuestionnaireResultScreen
import com.couple.translator.core.ui.questionnaire.QuestionnaireScreen
import com.couple.translator.feature.couple.wishlist.AddWishlistScreen
import com.couple.translator.feature.couple.wishlist.WishlistScreen
import com.couple.translator.feature.single.diary.DiaryListScreen
import com.couple.translator.feature.single.diary.DiaryDetailScreen
import com.couple.translator.feature.single.diary.ComposeDiaryScreen
import com.couple.translator.feature.single.practice.SelfPracticeListScreen
import com.couple.translator.feature.single.SingleShell
import com.couple.translator.feature.couple.CoupleShell
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    tokenStore: TokenStore? = null,
    coupleStateManager: CoupleStateManager? = null,
    realtimeSocketManager: RealtimeSocketManager? = null,
    guideStore: GuideStore? = null,
    themeStore: ThemeStore? = null,
    notificationPermissionStore: NotificationPermissionStore? = null,
    deepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    var startDest by remember { mutableStateOf<String?>(null) }
    val coupleState = coupleStateManager?.state?.collectAsState()?.value
    val isCoupleMode = coupleState?.mode != com.couple.translator.feature.couple.data.repository.AppMode.SINGLE

    LaunchedEffect(Unit) {
        val hasToken = tokenStore?.isLoggedIn() == true
        if (hasToken) {
            // 启动时立即刷新情侣状态，确保显示正确的模式
            coupleStateManager?.refresh()
        }
        startDest = if (hasToken) Screen.Main.route else Screen.Login.route
    }

    if (startDest == null) return

    // 动画参数：300ms。
    // 用 Material 的 emphasized 缓动曲线而不是 FastOutSlowIn —— 前者起步更快、收尾更匀，
    // 观感上就是"页面滑进来然后稳稳停住"，FastOutSlowIn 会显得尾巴拖沓。
    val animDuration = 300
    val enterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val exitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    NavHost(
        navController = navController,
        startDestination = startDest!!,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(animDuration, easing = enterEasing)
            ) + fadeIn(animationSpec = tween(animDuration / 2))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(animDuration, easing = exitEasing)
            ) + fadeOut(animationSpec = tween(animDuration / 3))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(animDuration, easing = enterEasing)
            ) + fadeIn(animationSpec = tween(animDuration / 2))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(animDuration, easing = exitEasing)
            ) + fadeOut(animationSpec = tween(animDuration / 3))
        },
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onNavigateToForgotPassword = { navController.navigate(Screen.ForgotPassword.route) },
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onNavigateBack = { navController.popBackStack() },
                onRegisterSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.ForgotPassword.route) {
            ForgotPasswordScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Main.route) {
            if (isCoupleMode) {
                CoupleShell(
                    onNavigateToRoute = { route -> navController.navigate(route) },
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    tokenStore = tokenStore,
                    coupleStateManager = coupleStateManager,
                    realtimeSocketManager = realtimeSocketManager,
                    notificationPermissionStore = notificationPermissionStore,
                )
            } else {
                SingleShell(
                    onNavigateToRoute = { route -> navController.navigate(route) },
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    tokenStore = tokenStore,
                )
            }
        }

        composable(Screen.Guide.route) {
            GuideScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRoute = { route -> navController.navigate(route) },
                isCoupleMode = isCoupleMode,
            )
        }

        composable(Screen.Profile.route) {
            ProfileScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.CoupleBind.route) {
            CoupleBindScreen(
                onNavigateBack = { navController.popBackStack() },
                onBindSuccess = {
                    navController.navigate(Screen.CoupleInfo.route) {
                        popUpTo(Screen.CoupleBind.route) { inclusive = true }
                    }
                },
                coupleStateManager = coupleStateManager!!,
            )
        }

        composable(Screen.CoupleInfo.route) {
            CoupleInfoScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.CoupleInfo.route) { inclusive = true }
                    }
                },
                coupleStateManager = coupleStateManager!!,
            )
        }

        composable(Screen.QuestionnaireIntro.route) {
            QuestionnaireIntroScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToQuestionnaire = { questionnaireId ->
                    navController.navigate("${Screen.Questionnaire.route}/$questionnaireId")
                },
                onNavigateToHistory = {
                    navController.navigate(Screen.QuestionnaireHistory.route)
                },
            )
        }

        composable(
            route = "${Screen.Questionnaire.route}/{questionnaireId}",
            arguments = listOf(navArgument("questionnaireId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val questionnaireId = backStackEntry.arguments?.getLong("questionnaireId") ?: return@composable
            QuestionnaireScreen(
                questionnaireId = questionnaireId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProfile = { coupleReady ->
                    navController.navigate("${Screen.QuestionnaireResult.route}/$questionnaireId?coupleReady=$coupleReady") {
                        popUpTo(Screen.Main.route) { inclusive = false }
                    }
                },
            )
        }

        composable(
            route = "${Screen.QuestionnaireResult.route}/{questionnaireId}?coupleReady={coupleReady}&submissionId={submissionId}",
            arguments = listOf(
                navArgument("questionnaireId") { type = NavType.LongType },
                navArgument("coupleReady") { type = NavType.BoolType; defaultValue = false },
                navArgument("submissionId") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { backStackEntry ->
            val questionnaireId = backStackEntry.arguments?.getLong("questionnaireId") ?: return@composable
            val coupleReady = backStackEntry.arguments?.getBoolean("coupleReady") ?: false
            val submissionId = backStackEntry.arguments?.getLong("submissionId") ?: 0L
            QuestionnaireResultScreen(
                questionnaireId = questionnaireId,
                submissionId = submissionId,
                coupleProfileReady = coupleReady,
                onNavigateBack = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.ProfileResult.route)
                },
                onNavigateToCoupleProfile = {
                    navController.navigate(Screen.CoupleProfile.route)
                },
            )
        }

        composable(Screen.QuestionnaireHistory.route) {
            QuestionnaireHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToResult = { submissionId ->
                    navController.navigate("${Screen.QuestionnaireResult.route}/0?coupleReady=false&submissionId=$submissionId")
                },
            )
        }

        composable(Screen.ProfileResult.route) {
            ProfileResultScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCoupleProfile = {
                    navController.navigate(Screen.CoupleProfile.route)
                },
                isCoupleMode = isCoupleMode,
            )
        }

        composable(Screen.CoupleProfile.route) {
            CoupleProfileScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.AiSessionList.route) {
            AiSessionListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { sessionId, sceneKey ->
                    navController.popBackStack()
                },
            )
        }

        composable(Screen.LetterList.route) {
            LetterListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToLetterDetail = { letterId ->
                    navController.navigate("${Screen.LetterDetail.route}/$letterId")
                },
                isCoupleMode = isCoupleMode,
            )
        }

        composable(
            route = "${Screen.LetterDetail.route}/{letterId}",
            arguments = listOf(navArgument("letterId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val letterId = backStackEntry.arguments?.getLong("letterId") ?: return@composable
            LetterDetailScreen(
                letterId = letterId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToComposeReply = { replyToId ->
                    navController.navigate("${Screen.ComposeLetter.route}?draftId=$replyToId")
                },
                onNavigateToEdit = { editId ->
                    navController.navigate("${Screen.ComposeLetter.route}?draftId=$editId")
                },
            )
        }

        composable(
            route = "${Screen.ComposeLetter.route}?draftId={draftId}",
            arguments = listOf(
                navArgument("draftId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { backStackEntry ->
            val draftId = backStackEntry.arguments?.getLong("draftId") ?: 0L
            ComposeLetterScreen(
                draftId = if (draftId > 0) draftId else null,
                onNavigateBack = { navController.popBackStack() },
                onLetterSent = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.ComposeLetter.route) { inclusive = true }
                    }
                },
                isCoupleMode = isCoupleMode,
            )
        }

        composable(Screen.ColdWar.route) {
            ColdWarScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToComposeLetter = { content ->
                    navController.navigate("${Screen.ComposeLetter.route}?draftId=0")
                },
            )
        }

        composable(Screen.MediationExplanation.route) {
            MediationExplanationScreen(
                onStartMediation = { navController.navigate(Screen.MediationInvite.route) },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.RelationshipReview.route) {
            ReviewScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "${Screen.MediationInvite.route}?sessionId={sessionId}&isInviter={isInviter}",
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument("isInviter") {
                    type = NavType.BoolType
                    defaultValue = true
                },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            val isInviter = backStackEntry.arguments?.getBoolean("isInviter") ?: true
            MediationInviteScreen(
                sessionId = sessionId,
                isInviter = isInviter,
                onNavigateToInput = { id ->
                    navController.navigate("${Screen.MediationInput.route}?sessionId=$id") {
                        popUpTo(Screen.MediationInvite.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Screen.MediationInput.route}?sessionId={sessionId}",
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            MediationInputScreen(
                sessionId = sessionId,
                onSubmitSuccess = { id ->
                    navController.navigate("${Screen.MediationConfirm.route}?sessionId=$id") {
                        popUpTo(Screen.MediationInput.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Screen.MediationConfirm.route}?sessionId={sessionId}",
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            MediationConfirmScreen(
                sessionId = sessionId,
                onConfirmed = { id ->
                    navController.navigate("${Screen.MediationResult.route}?sessionId=$id") {
                        popUpTo(Screen.MediationConfirm.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Screen.MediationResult.route}?sessionId={sessionId}",
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            MediationResultScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Dual Perspective
        composable(Screen.DualPerspectiveList.route) {
            DualPerspectiveListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { eventId ->
                    navController.navigate("${Screen.DualPerspectiveDetail.route}/$eventId")
                },
                onNavigateToCreate = {
                    navController.navigate(Screen.CreateDualEvent.route)
                },
            )
        }

        composable(
            route = "${Screen.DualPerspectiveDetail.route}/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getLong("eventId") ?: return@composable
            DualPerspectiveDetailScreen(
                eventId = eventId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSubmitRecord = { id ->
                    navController.navigate("${Screen.SubmitDualRecord.route}/$id")
                },
            )
        }

        composable(Screen.CreateDualEvent.route) {
            CreateDualEventScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSubmitRecord = { eventId ->
                    navController.navigate("${Screen.SubmitDualRecord.route}/$eventId") {
                        popUpTo(Screen.CreateDualEvent.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = "${Screen.SubmitDualRecord.route}/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getLong("eventId") ?: return@composable
            SubmitRecordScreen(
                eventId = eventId,
                onNavigateBack = { navController.popBackStack() },
                onSubmitSuccess = {
                    navController.navigate("${Screen.DualPerspectiveDetail.route}/$eventId") {
                        popUpTo(Screen.SubmitDualRecord.route) { inclusive = true }
                    }
                },
            )
        }

        // Museum
        composable(Screen.Museum.route) {
            MuseumScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { itemId ->
                    navController.navigate("${Screen.MuseumItemDetail.route}/$itemId")
                },
                onNavigateToAdd = {
                    navController.navigate(Screen.AddMuseumItem.route)
                },
            )
        }

        composable(
            route = "${Screen.MuseumItemDetail.route}/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getLong("itemId") ?: return@composable
            MuseumItemDetailScreen(
                itemId = itemId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.AddMuseumItem.route) {
            AddMuseumItemScreen(
                onNavigateBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }

        // Practice
        composable(Screen.PracticeList.route) {
            PracticeListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { recordId ->
                    navController.navigate("${Screen.PracticeDetail.route}/$recordId")
                },
                onNavigateToResult = { recordId ->
                    navController.navigate("${Screen.PracticeResult.route}/$recordId")
                },
            )
        }

        composable(
            route = "${Screen.PracticeDetail.route}/{recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getLong("recordId") ?: return@composable
            PracticeDetailScreen(
                practiceId = 0L,
                recordId = recordId,
                onNavigateBack = { navController.popBackStack() },
                onSubmitSuccess = { id ->
                    navController.navigate("${Screen.PracticeResult.route}/$id") {
                        popUpTo(Screen.PracticeList.route) { inclusive = false }
                    }
                },
            )
        }

        composable(
            route = "${Screen.PracticeResult.route}/{recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getLong("recordId") ?: return@composable
            PracticeResultScreen(
                recordId = recordId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAddMuseum = {
                    navController.navigate(Screen.AddMuseumItem.route)
                },
            )
        }

        // Anniversary
        composable(Screen.AnniversaryList.route) {
            AnniversaryListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAdd = {
                    navController.navigate(Screen.AddAnniversary.route)
                },
                onNavigateToMemoryCard = { targetType, targetId, itemTitle ->
                    navController.navigate(
                        "memory_card?targetType=$targetType&targetId=$targetId&itemTitle=" +
                            android.net.Uri.encode(itemTitle)
                    )
                },
            )
        }

        composable(
            route = Screen.MemoryCard.route,
            arguments = listOf(
                navArgument("targetType") { defaultValue = "anniversary" },
                navArgument("targetId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("itemTitle") { defaultValue = "" },
            ),
        ) { entry ->
            MemoryCardScreen(
                targetType = entry.arguments?.getString("targetType") ?: "anniversary",
                targetId = entry.arguments?.getLong("targetId") ?: 0L,
                itemTitle = entry.arguments?.getString("itemTitle").orEmpty(),
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.AddAnniversary.route) {
            AddAnniversaryScreen(
                onNavigateBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }

        // Wishlist
        composable(Screen.Wishlist.route) {
            WishlistScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAdd = {
                    navController.navigate(Screen.AddWishlist.route)
                },
                onNavigateToMemoryCard = { targetType, targetId, itemTitle ->
                    navController.navigate(
                        "memory_card?targetType=$targetType&targetId=$targetId&itemTitle=" +
                            android.net.Uri.encode(itemTitle)
                    )
                },
            )
        }

        composable(Screen.AddWishlist.route) {
            AddWishlistScreen(
                onNavigateBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }

        // 异地陪伴
        composable(Screen.Presence.route) {
            PresenceScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Avatar Customize
        composable(Screen.AvatarCustomize.route) {
            AvatarCustomizeScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Diary (单身模式专属)
        composable(Screen.DiaryList.route) {
            DiaryListScreen(
                onOpenDrawer = {},
                onNavigateToDetail = { diaryId ->
                    navController.navigate("${Screen.DiaryDetail.route}/$diaryId")
                },
                onNavigateToCompose = {
                    navController.navigate(Screen.ComposeDiary.route)
                },
            )
        }

        composable(
            route = "${Screen.DiaryDetail.route}/{diaryId}",
            arguments = listOf(navArgument("diaryId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val diaryId = backStackEntry.arguments?.getLong("diaryId") ?: return@composable
            DiaryDetailScreen(
                diaryId = diaryId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { editId ->
                    navController.navigate("${Screen.ComposeDiary.route}?editId=$editId")
                },
            )
        }

        composable(
            route = "${Screen.ComposeDiary.route}?editId={editId}",
            arguments = listOf(navArgument("editId") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) { backStackEntry ->
            val editId = backStackEntry.arguments?.getLong("editId") ?: -1L
            ComposeDiaryScreen(
                onNavigateBack = { navController.popBackStack() },
                diaryId = if (editId > 0) editId else null,
            )
        }

        // Self Practice (单身模式专属)
        composable(Screen.SelfPracticeList.route) {
            SelfPracticeListScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Settings
        composable("settings") {
            val scope = rememberCoroutineScope()
            // 读当前外观偏好；写入后 MainActivity 那层的 Flow 会立刻收到并整体换肤
            val themeMode by (themeStore?.themeMode ?: flowOf(ThemeMode.DEFAULT))
                .collectAsState(initial = ThemeMode.DEFAULT)
            // 通知偏好来自后端（用户级设置，换设备也要跟着走），所以走 ViewModel 而不是本地存储
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val notificationPref by settingsViewModel.state.collectAsState()
            com.couple.translator.core.ui.settings.SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route)
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                isCoupleMode = isCoupleMode,
                themeMode = themeMode,
                onThemeModeChange = { mode ->
                    scope.launch { themeStore?.setThemeMode(mode) }
                },
                notificationPref = notificationPref,
                onEmailNotifyChange = { enabled ->
                    settingsViewModel.setEmailNotify(enabled)
                },
                onNotificationPrefErrorShown = { settingsViewModel.clearError() },
            )
        }
    }

    // 首次进入主界面时自动展示一次使用指南：看完即走，不占底部栏也不占 Tab 位；
    // 之后再从左上角菜单随时打开（GuideStore 记标记，不会重复打扰）。
    LaunchedEffect(startDest) {
        if (startDest != Screen.Main.route) return@LaunchedEffect
        // 这次冷启动是用户点通知进来的：直奔目标页面，别再拿使用指南挡一层
        if (deepLinkRoute != null) return@LaunchedEffect
        val store = guideStore ?: return@LaunchedEffect
        if (store.isGuideSeen()) return@LaunchedEffect
        store.markGuideSeen()
        navController.navigate(Screen.Guide.route)
    }

    // 通知栏点击带来的深链。
    // 放在 NavHost 之后：NavHost 得先完成组合，路由才注册得上，
    // 否则 navigate 到一个"还不存在"的目的地会被静默丢弃。
    // 未登录时直接丢弃——用户会落在登录页，登录完自己走过去，
    // 比跳到一个需要鉴权的页面然后报 401 体面得多。
    LaunchedEffect(deepLinkRoute) {
        val target = deepLinkRoute ?: return@LaunchedEffect
        if (startDest == Screen.Main.route) {
            navController.navigate(target) { launchSingleTop = true }
        }
        onDeepLinkConsumed()
    }
}
