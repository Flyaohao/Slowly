package com.couple.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.couple.translator.core.data.repository.TokenStore
import com.couple.translator.core.ui.theme.CoupleTranslatorTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoupleTranslatorTheme {
                NavGraph(
                    tokenStore = tokenStore,
                    coupleStateManager = coupleStateManager,
                    realtimeSocketManager = realtimeSocketManager,
                )
            }
        }
    }
}
