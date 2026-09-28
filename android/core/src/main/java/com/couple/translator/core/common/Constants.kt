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
    /** 系统通知权限是否已询问过（问过一次就不再纠缠，拒绝也不影响主流程） */
    const val NOTIFICATION_ASKED_KEY = "notification_permission_asked"
    /** 触感反馈总开关（全局 UI/UX 方案 D5：默认开，设置页 P1 接入开关行） */
    const val HAPTICS_ENABLED_KEY = "haptics_enabled"
    /** 上次弹过「用量 80% 提示」的 sessionId（P-C3 §3.4：每段会话各一次） */
    const val USAGE_HINT_SESSION_KEY = "usage_hint_session_id"
    /** 上次成功获取的模式 couple/unbinding/single——情侣状态刷新失败时的本地兜底 */
    const val LAST_MODE_KEY = "last_app_mode"
    /** 页面级提示条「不再显示」开关的 key 前缀，实际 key = 前缀 + 提示条 id */
    const val UI_NOTICE_DISMISSED_PREFIX = "ui_notice_dismissed_"
    const val PASSWORD_MIN_LENGTH = 8
}
