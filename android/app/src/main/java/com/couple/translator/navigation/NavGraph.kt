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
import com.couple.translator.feature.couple.ai.FeedbackOutcomeScreen
import com.couple.translator.feature.couple.ai.MemoryScreen
import com.couple.translator.feature.couple.ai.PendingSessionHolder
import com.couple.translator.feature.couple.ai.ReviewHistoryScreen
import com.couple.translator.feature.couple.ai.ReviewScreen
import com.couple.translator.feature.couple.anniversary.AddAnniversaryScreen
import com.couple.translator.feature.couple.anniversary.AnniversaryListScreen
import com.couple.translator.feature.couple.memorycard.MemoryCardScreen
import com.couple.translator.core.ui.advisor.AdvisorSettingsScreen
import com.couple.translator.core.ui.auth.ForgotPasswordScreen
import com.couple.translator.core.ui.guide.GuideScreen
import com.couple.translator.core.ui.auth.LoginScreen
import com.couple.translator.core.ui.auth.RegisterScreen
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
import com.couple.translator.feature.couple.mediation.MediationHistoryScreen
import com.couple.translator.feature.couple.mediation.MediationInputScreen
import com.couple.translator.feature.couple.mediation.MediationInviteScreen
import com.couple.translator.feature.couple.mediation.MediationResultScreen
import com.couple.translator.feature.couple.mediation.MediationStep
import com.couple.translator.feature.couple.museum.AddMuseumItemScreen
import com.couple.translator.feature.couple.museum.MuseumItemDetailScreen
import com.couple.translator.feature.couple.museum.MuseumScreen
import com.couple.translator.core.ui.profile.CoupleProfileScreen
import com.couple.translator.core.ui.profile.ProfileResultScreen
import com.couple.translator.core.ui.profile.ProfileScreen
import com.couple.translator.core.ui.profile.UnderstandingScreen
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
                // [W4.3 合并] 问卷结果 → 画像两跳全部改指「军师如何理解我们」
                // （ProfileResult / CoupleProfile 路由与页面保留）
                onNavigateToProfile = {
                    navController.navigate(Screen.Understanding.route)
                },
                onNavigateToCoupleProfile = {
                    navController.navigate(Screen.Understanding.route)
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
                // [W4.3 合并] 「看关系画像」改指「军师如何理解我们」（CoupleProfile 路由保留）
                onNavigateToCoupleProfile = {
                    navController.navigate(Screen.Understanding.route)
                },
                isCoupleMode = isCoupleMode,
            )
        }

        composable(Screen.CoupleProfile.route) {
            CoupleProfileScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // [W4.3] 画像三合一页（我的画像 + 了解自己 + 关系画像 → 单一入口）
        composable(Screen.Understanding.route) {
            UnderstandingScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRoute = { route -> navController.navigate(route) },
            )
        }

        // [W4.4] 军师设置页（契约 §3.3）；抽屉「军师设置」入口指向此处
        composable(Screen.AdvisorSettings.route) {
            AdvisorSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.AiSessionList.route) {
            AiSessionListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { sessionId, sceneKey, title, archived ->
                    // P0-10B 改动三：跨导航图传参（列表在根图、军师页在 CoupleShell）。
                    // 此前只 popBackStack，参数收下即丢 → 历史记录点不进去。
                    // title/archived 一并带给状态条：loadSession 不同步这两个字段。
                    PendingSessionHolder.set(sessionId, sceneKey, title, archived)
                    navController.popBackStack()
                },
            )
        }

        // P-C3 §4.3：记忆管理页（此前无任何 destination 接线）
        composable(Screen.Memory.route) {
            MemoryScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.LetterList.route) {
            LetterListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToLetterDetail = { letterId ->
                    navController.navigate("${Screen.LetterDetail.route}/$letterId")
                },
                isCoupleMode = isCoupleMode,
                // 整改 §8.1：列表页右下角「写一封信」FAB 的真实去处——
                // 不接线则空态承诺的「右下角按钮」是死的
                onNavigateToCompose = {
                    navController.navigate(Screen.ComposeLetter.route)
                },
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
            // 整改 §8.1：content = 「整理成一封信」等入口带来的正文预填（Uri.encode）；
            // draftId 与 content 互斥语义在页内处理：有草稿只 loadDraft，无草稿才 prefill。
            route = "${Screen.ComposeLetter.route}?draftId={draftId}&content={content}",
            arguments = listOf(
                navArgument("draftId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument("content") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val draftId = backStackEntry.arguments?.getLong("draftId") ?: 0L
            val prefillContent = backStackEntry.arguments?.getString("content").orEmpty()
            ComposeLetterScreen(
                draftId = if (draftId > 0) draftId else null,
                content = prefillContent,
                onNavigateBack = { navController.popBackStack() },
                onLetterSent = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.ComposeLetter.route) { inclusive = true }
                    }
                },
                isCoupleMode = isCoupleMode,
            )
        }

        composable(Screen.MediationExplanation.route) {
            // 契约 §2.3-1：API 优先——说明页先 POST /mediation/start 拿真实 session_id，
            // 再带 sessionId 导航（废除 sessionId=0 默认导航）。isInviter 走默认 true（发起方）。
            MediationExplanationScreen(
                onStartMediation = { sessionId ->
                    // M5：发起成功后把说明页移出回退栈——否则返回键回到说明页可再次 POST /start，
                    // 造成重复发起。会话页成为该栈顶，返回直接回上一层。
                    navController.navigate("${Screen.MediationInvite.route}?sessionId=$sessionId") {
                        popUpTo(Screen.MediationExplanation.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // 关系复盘（整改 §8.7）：`reviewId` 走**必填路径段**而不是可选 query——
        // 同一 composable 的多个目的地共享同一个 SavedStateHandle，带 query 的那次
        // 导航会复用旧 handle 里的值（首页回访卡点进来看到上一条复盘），
        // 用不同 route 字符串区分才能保证参数从 back stack entry 真读对。
        composable(Screen.RelationshipReview.route) {
            ReviewScreen(
                onBack = { navController.popBackStack() },
                onOpenHistory = { navController.navigate(Screen.ReviewHistory.route) },
                onOpenReview = { reviewId ->
                    navController.navigate("${Screen.RelationshipReview.route}/$reviewId")
                },
            )
        }

        composable(
            route = "${Screen.RelationshipReview.route}/{reviewId}",
            arguments = listOf(navArgument("reviewId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val reviewId = backStackEntry.arguments?.getLong("reviewId") ?: 0L
            ReviewScreen(
                reviewId = reviewId,
                onBack = { navController.popBackStack() },
                onOpenHistory = { navController.navigate(Screen.ReviewHistory.route) },
                onOpenReview = { id ->
                    navController.navigate("${Screen.RelationshipReview.route}/$id")
                },
            )
        }

        // 复盘历史列表：注册在根导航上，凡是能选复盘的地方都点得进来（§8.0 可达性）
        composable(Screen.ReviewHistory.route) {
            ReviewHistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenReview = { reviewId ->
                    navController.navigate("${Screen.RelationshipReview.route}/$reviewId")
                },
                onCreateReview = {
                    navController.navigate(Screen.RelationshipReview.route)
                },
            )
        }

        // 整改 §8.3：待反馈结果页。两种进入方式：
        // - 首页 feedback_outcome 任务卡 → sessionId（后端约定卡的 id 即会话 id）
        // - 会话内反馈行「填写实际结果」→ messageId（直接落到该条编辑态）
        composable(
            route = "${Screen.FeedbackOutcome.route}?sessionId={sessionId}&messageId={messageId}",
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument("messageId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { backStackEntry ->
            FeedbackOutcomeScreen(
                onNavigateBack = { navController.popBackStack() },
                sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L,
                messageId = backStackEntry.arguments?.getLong("messageId") ?: 0L,
            )
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
                onNavigateToInput = { id -> navigateToMediationStep(navController, id, MediationStep.INPUT) },
                onNavigateBack = { navController.popBackStack() },
                // §8.5-1：对方接受后由服务端状态裁决落点（不再假设「接受 = 双方都去输入页」）
                onNavigateToStep = { id, step -> navigateToMediationStep(navController, id, step) },
                statusFrames = realtimeSocketManager?.statusFrames
                    ?.filter { it.sessionId == sessionId }
                    ?.map { it.status }
                    ?: emptyFlow(),
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
                // §8.5-2：提交后去哪由 MediationFlow 按服务端状态裁决，
                // 第一方落等待态（此前直接跳确认页 → 看到空白改写）。
                onMoveToStep = { id, step -> navigateToMediationStep(navController, id, step) },
                onNavigateBack = { navController.popBackStack() },
                statusFrames = realtimeSocketManager?.statusFrames
                    ?.filter { it.sessionId == sessionId }
                    ?.map { it.status }
                    ?: emptyFlow(),
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
                // §8.5-5：只有双方都确认（或总结已生成）才会发 RESULT
                onMoveToStep = { id, step -> navigateToMediationStep(navController, id, step) },
                onNavigateBack = { navController.popBackStack() },
                // 整改 B4.1-4：失败态的「重新发起」——去说明页新建一场（真实 id 由后端给）
                onRestartMediation = { navController.navigate(Screen.MediationExplanation.route) },
                statusFrames = realtimeSocketManager?.statusFrames
                    ?.filter { it.sessionId == sessionId }
                    ?.map { it.status }
                    ?: emptyFlow(),
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
                // 整改 B4.1-4：总结失败态的「重新发起」（旧会话保留在调解回看里）
                onRestartMediation = { navController.navigate(Screen.MediationExplanation.route) },
            )
        }

        // 整改 §8.5-6：已完成的调解回看列表（关系页「调解回看」的落点）。
        // 详情直接复用结果页——它按 sessionId 读服务端总结，不区分「刚谈完」还是「一个月前」。
        composable(Screen.MediationHistory.route) {
            MediationHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { sessionId ->
                    navController.navigate("${Screen.MediationResult.route}?sessionId=$sessionId")
                },
                // 整改 B4.1-6：「重新发起」走说明页新建一场（真实 session_id 由后端给），
                // 不复用旧会话——旧会话的总结是那次沟通的记录，不该被新一轮覆盖。
                onRestartMediation = { navController.navigate(Screen.MediationExplanation.route) },
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

        // 创建双视角事件（整改 §8.6）。
        //
        // `invite` 走**可选路径段**而不是 query：与 `/pair` 的两个目的地同理，
        // NavHost 按 route 字符串匹配，`create_dual_event` 与
        // `create_dual_event/invite` 是两个不同目的地，参数不会互相串。
        // 不这样拆的话，「从军师行动行带邀请进来」会在下一次从列表页进创建页时
        // 被复用（SavedStateHandle 是 per-destination 的），把普通创建也说成邀请。
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

        composable("${Screen.CreateDualEvent.route}/invite") {
            CreateDualEventScreen(
                invite = true,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSubmitRecord = { eventId ->
                    navController.navigate("${Screen.SubmitDualRecord.route}/$eventId") {
                        popUpTo("${Screen.CreateDualEvent.route}/invite") { inclusive = true }
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

/**
 * 调解流程里「下一步该去哪个页面」的唯一映射（整改 §8.5-2 / §8.5-5 / §8.5-7）。
 *
 * 落点由 [MediationStep] 给出（[MediationFlow] 按服务端状态裁决），这里只负责
 * 把 step 变成一次导航。三条硬规则写在注释里，改的时候别走散：
 *
 * 1. **每一步都 popUpTo 当前步**：调解是一条直线流程，用户不该用返回键
 *    退回到「已提交的输入页」再提交一次（§8.5 的重复提交）。
 * 2. **往前的落点才 pop**：[MediationStep.STAY]（停在原页继续等）与
 *    「从结果页往回看」都不动回退栈。
 * 3. **等待态是同一个页面的另一种形态**，不是新目的地：`WAITING_PARTNER`
 *    回到输入页（它自己渲染等待卡片），其余等待态回确认页——
 *    这样「等对方确认」不会因为多注册一个路由而需要单独维护一套状态。
 */
private fun navigateToMediationStep(
    navController: NavHostController,
    sessionId: Long,
    step: MediationStep,
) {
    when (step) {
        MediationStep.STAY -> Unit

        MediationStep.INPUT -> navigateSingleStep(
            navController,
            from = Screen.MediationInvite.route,
            to = "${Screen.MediationInput.route}?sessionId=$sessionId",
        )

        MediationStep.WAITING_PARTNER -> navigateSingleStep(
            navController,
            from = Screen.MediationInput.route,
            to = "${Screen.MediationInput.route}?sessionId=$sessionId",
        )

        MediationStep.WAITING_REWRITE,
        MediationStep.WAITING_PARTNER_CONFIRM,
        // 整改 B4.1-4：改写失败也落在确认页——那一页有失败态与「重试」按钮。
        // 失败不是流程里的新一步，是同一步的另一种形态。
        MediationStep.FAILED_REWRITE,
        -> navigateSingleStep(
            navController,
            from = Screen.MediationConfirm.route,
            to = "${Screen.MediationConfirm.route}?sessionId=$sessionId",
        )

        MediationStep.CONFIRM -> navigateSingleStep(
            navController,
            from = Screen.MediationInput.route,
            to = "${Screen.MediationConfirm.route}?sessionId=$sessionId",
        )

        // 整改 B4.1-4：总结失败落在结果页（那一页有失败态与「重试」）。
        // 用 from = 结果页自身：从确认页过来时清掉确认页，从历史/结果页重进时
        // popUpTo 一个不在回退栈里的路由是**空操作**（Navigation 的行为），
        // 于是不会把用户已有的回退栈意外清空。
        MediationStep.FAILED_SUMMARY -> navigateSingleStep(
            navController,
            from = Screen.MediationResult.route,
            to = "${Screen.MediationResult.route}?sessionId=$sessionId",
        )

        MediationStep.RESULT -> navController.navigate(
            "${Screen.MediationResult.route}?sessionId=$sessionId"
        ) {
            // 结果页是这条线的终点：把前面几步一起清掉，返回即离开调解。
            // 不清的话用户能从结果页退回到确认页再点一次确认（§8.5-5）。
            popUpTo(Screen.MediationInvite.route) { inclusive = true }
        }
    }
}

private fun navigateSingleStep(navController: NavHostController, from: String, to: String) {
    navController.navigate(to) {
        popUpTo(from) { inclusive = true }
    }
}
