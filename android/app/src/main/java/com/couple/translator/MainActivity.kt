package com.couple.translator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.couple.translator.core.data.repository.GuideStore
import com.couple.translator.core.data.repository.HapticsStore
import com.couple.translator.core.data.repository.NotificationPermissionStore
import com.couple.translator.core.data.repository.ThemeStore
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.notification.AppNotifications
import com.couple.translator.core.ui.components.LocalHapticsEnabled
import com.couple.translator.core.ui.theme.CoupleTranslatorTheme
import com.couple.translator.core.ui.theme.ThemeMode
import com.couple.translator.core.ui.theme.resolveDarkTheme
import com.couple.translator.feature.couple.data.repository.CoupleStateManager
import com.couple.translator.feature.couple.network.RealtimeSocketManager
import com.couple.translator.navigation.NavGraph
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenStore: TokenStore

    @Inject
    lateinit var coupleStateManager: CoupleStateManager

    @Inject
    lateinit var realtimeSocketManager: RealtimeSocketManager

    @Inject
    lateinit var guideStore: GuideStore

    @Inject
    lateinit var themeStore: ThemeStore

    @Inject
    lateinit var notificationPermissionStore: NotificationPermissionStore

    // 全局 UI/UX 方案 G2/D5：触感总开关（设置页开关行读写的就是它）
    @Inject
    lateinit var hapticsStore: HapticsStore

    /**
     * 点击通知栏带来的目标路由。Activity 只有这一个，通知点开时走 onNewIntent
     * （Intent 里带了 SINGLE_TOP），所以用 StateFlow 承接而不是只在 onCreate 读一次。
     */
    private val deepLinkRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 冷启动主题（启动图）只用于系统绘制首帧，进 Compose 前切回正常主题，
        // 否则启动图会一直留在 windowBackground 里，转场/过滚动时可能透出
        setTheme(R.style.Theme_CoupleTranslator)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkRoute.value = intent?.getStringExtra(AppNotifications.EXTRA_ROUTE)

        setContent {
            // 外观模式由本地偏好驱动：改完设置页立刻换肤，无需重启。
            // SYSTEM 时才去问系统；LIGHT / DARK 直接覆盖系统设置。
            val themeMode by themeStore.themeMode.collectAsState(initial = ThemeMode.DEFAULT)
            val systemInDarkTheme = isSystemInDarkTheme()
            val pendingRoute by deepLinkRoute.collectAsState()
            // 触感总开关：冷流首帧取 true（与 store 默认值一致），读到位后跟随
            val hapticsEnabled by hapticsStore.enabled.collectAsState(initial = true)
            CoupleTranslatorTheme(darkTheme = themeMode.resolveDarkTheme(systemInDarkTheme)) {
                CompositionLocalProvider(LocalHapticsEnabled provides hapticsEnabled) {
                    NavGraph(
                        tokenStore = tokenStore,
                        coupleStateManager = coupleStateManager,
                        realtimeSocketManager = realtimeSocketManager,
                        guideStore = guideStore,
                        themeStore = themeStore,
                        notificationPermissionStore = notificationPermissionStore,
                        hapticsStore = hapticsStore,
                        deepLinkRoute = pendingRoute,
                        onDeepLinkConsumed = { deepLinkRoute.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须 setIntent：否则后续 getIntent() 拿到的还是旧的启动 Intent
        setIntent(intent)
        val route = intent.getStringExtra(AppNotifications.EXTRA_ROUTE)
        if (route != null) {
            deepLinkRoute.value = route
        }
    }
}
