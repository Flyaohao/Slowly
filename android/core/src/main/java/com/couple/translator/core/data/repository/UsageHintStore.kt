package com.couple.translator.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.couple.translator.core.common.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下文用量 80% 提示的展示状态（P-C3 §3.4）。
 *
 * 语义是「**每段会话各一次**」——记录最近提示过的 sessionId；
 * 换新会话（id 不同）会再弹，回到同一段不再打扰。
 * 复用 [dataStore] 这一个全局 DataStore 实例（同名第二份委托会崩，项目红线）。
 */
@Singleton
class UsageHintStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lastHintedKey = stringPreferencesKey(Constants.USAGE_HINT_SESSION_KEY)

    /** 该会话是否已经提示过 */
    suspend fun isHinted(sessionId: Long): Boolean {
        val last = context.dataStore.data.map { prefs -> prefs[lastHintedKey] }.first()
        return last == sessionId.toString()
    }

    /** 记录已对某会话提示过 */
    suspend fun markHinted(sessionId: Long) {
        context.dataStore.edit { prefs -> prefs[lastHintedKey] = sessionId.toString() }
    }
}
