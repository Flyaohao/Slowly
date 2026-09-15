package com.couple.translator.core.ui.theme

/**
 * 外观主题模式（设置页「外观」分组的三选一）。
 *
 * 只描述"用户想要什么"，不关心系统当前设置 —— 解析成实际深浅色的过程在
 * `resolveDarkTheme()` 里完成。
 */
enum class ThemeMode(val storageValue: String, val label: String) {
    /** 跟随系统（默认） */
    SYSTEM("system", "跟随系统"),

    /** 始终浅色，忽略系统设置 */
    LIGHT("light", "始终浅色"),

    /** 始终深色，忽略系统设置 */
    DARK("dark", "始终深色"),
    ;

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        /** 把本地存储里的原始字符串还原成枚举，非法值一律回落到默认 */
        fun fromStorage(raw: String?): ThemeMode =
            values().firstOrNull { it.storageValue == raw } ?: DEFAULT
    }
}

/**
 * 结合系统当前是否为深色，算出最终应该用哪套色板。
 *
 * @param systemInDarkTheme 系统当前的深色状态（即 `isSystemInDarkTheme()`）
 */
fun ThemeMode.resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
