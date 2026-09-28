package com.couple.translator.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.couple.translator.core.common.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 触感反馈总开关（全局 UI/UX 方案 G2 / D5）。
 *
 * 默认开启；设置页的开关行（P1，R 系列）读写本 store。
 * 复用全局唯一的 [Context.dataStore] 实例（DataStore 单实例红线），key 与 token 独立。
 */
@Singleton
class HapticsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey(Constants.HAPTICS_ENABLED_KEY)

    /** 触感开关（冷流，collect 时读最新值） */
    val enabled: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[enabledKey] ?: true }

    suspend fun setEnabled(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[enabledKey] = value }
    }
}
