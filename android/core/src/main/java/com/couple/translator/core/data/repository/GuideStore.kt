package com.couple.translator.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.couple.translator.core.common.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用指南的本地状态。
 *
 * 只负责一件事：记录"首次自动弹出使用指南"是否已经发生过。
 * 复用 [dataStore] 这一个全局 DataStore 实例（不能自己再建一个指向同名的实例），
 * 但 key 与 token 相互独立 —— 因此退出登录不会重置该标记，重新登录也不会重复弹窗。
 */
@Singleton
class GuideStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val seenKey = booleanPreferencesKey(Constants.GUIDE_SEEN_KEY)

    /** 是否已经自动展示过使用指南 */
    suspend fun isGuideSeen(): Boolean {
        return context.dataStore.data.map { prefs -> prefs[seenKey] == true }.first()
    }

    /** 标记使用指南已展示（首启自动弹出时调用，避免重复打扰） */
    suspend fun markGuideSeen() {
        context.dataStore.edit { prefs -> prefs[seenKey] = true }
    }
}
