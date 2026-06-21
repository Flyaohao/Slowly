package com.couple.translator.core.navigation

enum class BottomTab(val route: String, val label: String) {
    // 情侣模式
    Home("tab_home", "我们"),
    Mailbox("tab_mailbox", "信箱"),
    AiChat("tab_ai", "翻译官"),
    // 单身模式
    SingleHome("tab_single_home", "我"),
    Diary("tab_diary", "日记"),
}
