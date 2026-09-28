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
 * 页面级提示条的「不再显示」状态（按提示条 id 各存一个布尔）。
 *
 * 使用方：给 AppInfoBanner 传稳定 id + 关闭回调；用户点 × 后跨启动记住，
 * 不再打扰阅读。复用全局唯一的 [Context.dataStore] 实例（DataStore 单实例红线），
 * key = [Constants.UI_NOTICE_DISMISSED_PREFIX] + id。
 */
@Singleton
class UiNoticeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun key(id: String) = booleanPreferencesKey(Constants.UI_NOTICE_DISMISSED_PREFIX + id)

    /** 该提示条是否已被用户关闭（冷流，collect 时读最新值） */
    fun dismissed(id: String): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[key(id)] ?: false }

    suspend fun setDismissed(id: String, value: Boolean) {
        context.dataStore.edit { prefs -> prefs[key(id)] = value }
    }
}
