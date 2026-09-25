package com.couple.translator.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.couple.translator.core.common.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局唯一的 DataStore 实例（internal，供 TokenStore / GuideStore 等共用）。
 * 注意：DataStore 同一个文件只允许存在一个实例，否则运行时会抛
 * IllegalStateException("There are multiple DataStores active for the same file")。
 * 因此新增本地存储时请复用本委托，不要再写一份 preferencesDataStore(name = ...)。
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = Constants.DATASTORE_NAME
)

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tokenKey = stringPreferencesKey(Constants.TOKEN_KEY)
    private val refreshTokenKey = stringPreferencesKey(Constants.REFRESH_TOKEN_KEY)
    private val lastModeKey = stringPreferencesKey(Constants.LAST_MODE_KEY)

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.dataStore.edit { prefs ->
            prefs[tokenKey] = accessToken
            prefs[refreshTokenKey] = refreshToken
        }
    }

    suspend fun getToken(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[tokenKey]
        }.first()
    }

    suspend fun getRefreshToken(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[refreshTokenKey]
        }.first()
    }

    /**
     * 上次成功获取的模式（couple / unbinding / single）。
     * 随会话走：登录写入、登出清除——避免换账号后短暂显示上一个人的模式。
     * 用途只有一个：/couples/me 刷新失败时不当场退回单身模式。
     */
    suspend fun saveLastMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[lastModeKey] = mode }
    }

    suspend fun getLastMode(): String? {
        return context.dataStore.data.map { prefs -> prefs[lastModeKey] }.first()
    }

    suspend fun clearTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(tokenKey)
            prefs.remove(refreshTokenKey)
            prefs.remove(lastModeKey)
        }
    }

    suspend fun isLoggedIn(): Boolean {
        return getToken() != null
    }
}
