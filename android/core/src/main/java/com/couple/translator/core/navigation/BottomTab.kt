package com.couple.translator.core.navigation

enum class BottomTab(val route: String, val label: String) {
    // 2026-09-29 重构（用户拍板）：底栏收敛为「军师 + 空间」两个 tab，
    // 页序 = 军师（默认首屏）+ 空间（顶替原「关系」tab 的位置）。
    //
    // 原 Relation("tab_relation","关系") 已删除：关系页功能与空间页严重重合，
    // 用户裁决「直接把关系页面删掉」，其内容按归属拆分——天数/观察卡并入空间页，
    // 画像/纪念日/信箱/调解历史/待办由抽屉与空间页宫格承接（无功能丢失）。
    AiChat("tab_ai", "军师"),
    Mailbox("tab_mailbox", "信箱"),
    Home("tab_home", "空间"),
    // 2026-09-29：SingleHome / Diary 两个单身模式 tab 已删除（单身模式整体移除）。
    // 注意「观点」功能本身保留——它现在是情侣模式抽屉里的条目，走 Screen.DiaryList 路由。
}
