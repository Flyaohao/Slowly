package com.couple.translator.core.navigation

enum class BottomTab(val route: String, val label: String) {
    // 情侣模式（S2 两 tab 壳）：军师 + 关系。信箱/空间 tab 从底栏移除但
    // 枚举值与路由全部保留（隐藏 ≠ 删除，仍可从关系 tab / 任务卡 / 深链进入）
    AiChat("tab_ai", "军师"),
    Relation("tab_relation", "关系"),
    Mailbox("tab_mailbox", "信箱"),
    Home("tab_home", "空间"),
    // 单身模式
    SingleHome("tab_single_home", "我"),
    Diary("tab_diary", "观点"),
}
