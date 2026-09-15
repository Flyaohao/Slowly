package com.couple.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.couple.translator.core.data.repository.GuideStore
import com.couple.translator.core.data.repository.ThemeStore
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.ui.theme.CoupleTranslatorTheme
import com.couple.translator.core.ui.theme.ThemeMode
import com.couple.translator.core.ui.theme.resolveDarkTheme
import com.couple.translator.feature.couple.data.repository.CoupleStateManager
import com.couple.translator.feature.couple.network.RealtimeSocketManager
import com.couple.translator.navigation.NavGraph
import dagger.hilt.android.AndroidEntryPoint
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 外观模式由本地偏好驱动：改完设置页立刻换肤，无需重启。
            // SYSTEM 时才去问系统；LIGHT / DARK 直接覆盖系统设置。
            val themeMode by themeStore.themeMode.collectAsState(initial = ThemeMode.DEFAULT)
            val systemInDarkTheme = isSystemInDarkTheme()
            CoupleTranslatorTheme(darkTheme = themeMode.resolveDarkTheme(systemInDarkTheme)) {
                NavGraph(
                    tokenStore = tokenStore,
                    coupleStateManager = coupleStateManager,
                    realtimeSocketManager = realtimeSocketManager,
                    guideStore = guideStore,
                    themeStore = themeStore,
                )
            }
        }
    }
}
