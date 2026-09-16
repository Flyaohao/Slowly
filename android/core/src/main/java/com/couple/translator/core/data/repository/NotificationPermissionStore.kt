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
 * 系统通知权限的本地状态。
 *
 * 只记录一件事：**我们是否已经向用户要过通知权限**。
 *
 * 为什么需要它：Android 的运行时权限可以反复申请，但用户明确拒绝之后
 * 每次冷启动再弹一次就是骚扰。系统的 `shouldShowRequestPermissionRationale`
 * 在"拒绝过一次"后返回 true、在"拒绝并勾选不再询问"后返回 false，
 * 语义不够直接，所以这里自己记一个一次性标记，语义明确：
 * 问过一次就再也不自动问，用户想开自己去系统设置（设置页有入口）。
 *
 * 复用全局那一个 [dataStore] 实例（禁止再建同名实例），key 与 token 独立，
 * 因此退出登录不会重置该标记。
 */
@Singleton
class NotificationPermissionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val askedKey = booleanPreferencesKey(Constants.NOTIFICATION_ASKED_KEY)

    /** 是否已经问过用户通知权限 */
    suspend fun hasAsked(): Boolean {
        return context.dataStore.data.map { prefs -> prefs[askedKey] == true }.first()
    }

    /** 标记已问过（无论用户同意还是拒绝，都只问这一次） */
    suspend fun markAsked() {
        context.dataStore.edit { prefs -> prefs[askedKey] = true }
    }
}
