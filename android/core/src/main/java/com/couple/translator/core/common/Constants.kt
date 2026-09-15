package com.couple.translator.core.common

object Constants {
    const val API_PREFIX = "api/v1"
    const val AUTH_HEADER = "Authorization"
    const val BEARER_PREFIX = "Bearer "
    const val TOKEN_KEY = "jwt_token"
    const val REFRESH_TOKEN_KEY = "refresh_token"
    const val DATASTORE_NAME = "couple_prefs"
    /** 使用指南是否已自动展示过（只弹一次，之后从抽屉进入） */
    const val GUIDE_SEEN_KEY = "guide_seen"
    /** 外观主题模式：system / light / dark（存 ThemeMode.name） */
    const val THEME_MODE_KEY = "theme_mode"
    const val PASSWORD_MIN_LENGTH = 8
}
