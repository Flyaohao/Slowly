package com.couple.translator.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.couple.translator.core.common.Constants
import com.couple.translator.core.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外观主题偏好的本地存储。
 *
 * 复用 [dataStore] 这个全局唯一的 DataStore 实例（不能再建一个指向同名文件的实例，
 * 否则运行时会抛 `IllegalStateException: There are multiple DataStores active for the same file`），
 * key 与 token / guide 标记相互独立。
 *
 * 暴露的是 [Flow] 而非一次性读取 —— 设置页改完要能立刻让界面换肤。
 */
@Singleton
class ThemeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val themeModeKey = stringPreferencesKey(Constants.THEME_MODE_KEY)

    /** 当前主题模式，未设置过时为 [ThemeMode.DEFAULT]（跟随系统） */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.fromStorage(prefs[themeModeKey])
    }

    /** 写入主题模式（设置页三选一时调用） */
    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[themeModeKey] = mode.storageValue }
    }
}
