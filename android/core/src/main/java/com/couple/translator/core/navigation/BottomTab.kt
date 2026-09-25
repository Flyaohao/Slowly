package com.couple.translator.core.navigation

enum class BottomTab(val route: String, val label: String) {
    // 情侣模式：军师为首页放最左，中间信箱，最右空间（原「我们」）
    AiChat("tab_ai", "军师"),
    Mailbox("tab_mailbox", "信箱"),
    Home("tab_home", "空间"),
    // 单身模式
    SingleHome("tab_single_home", "我"),
    Diary("tab_diary", "日记"),
}
