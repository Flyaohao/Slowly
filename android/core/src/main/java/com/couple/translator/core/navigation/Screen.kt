package com.couple.translator.core.navigation

enum class Screen(val route: String) {
    Login("login"),
    Register("register"),
    ForgotPassword("forgot_password"),

    Main("main"),

    /** 使用指南：全屏根路由，压在主界面之上，因此既不占底部栏也不占任何 Tab 位 */
    Guide("guide"),

    Home("home"),
    Mailbox("mailbox"),
    AiChat("ai_chat"),

    Profile("profile"),
    CoupleBind("couple_bind"),
    CoupleInfo("couple_info"),
    QuestionnaireIntro("questionnaire_intro"),
    Questionnaire("questionnaire"),
    QuestionnaireResult("questionnaire_result"),
    QuestionnaireHistory("questionnaire_history"),
    ProfileResult("profile_result"),
    CoupleProfile("couple_profile"),
    AiSessionList("ai_session_list"),
    LetterList("letter_list"),
    LetterDetail("letter_detail"),
    ComposeLetter("compose_letter"),
    RelationshipReview("relationship_review"),
    /** 整改 §8.7：复盘历史回看（所有留档，不再只有最新一条）。 */
    ReviewHistory("review_history"),
    MediationInvite("mediation_invite"),
    MediationInput("mediation_input"),
    MediationConfirm("mediation_confirm"),
    MediationResult("mediation_result"),
    MediationExplanation("mediation_explanation"),
    /** 整改 §8.5-6：已完成的调解回看列表（「能回看」的用户入口）。 */
    MediationHistory("mediation_history"),

    // 2026-09-28 共同调解室（设计文档裁决）：军师在场的三人房间（1v2）。
    // 旧 B4.3 链路改名「各自的看法」，入口替换、数据保留（D-LEGACY）。
    MediationRoomList("mediation_room_list"),
    MediationRoomCreate("mediation_room_create"),
    MediationRoomChat("mediation_room_chat/{roomId}"),
    Memory("memory"),
    DualPerspectiveList("dual_perspective_list"),
    DualPerspectiveDetail("dual_perspective_detail"),
    CreateDualEvent("create_dual_event"),
    SubmitDualRecord("submit_dual_record"),
    Museum("museum"),
    MuseumItemDetail("museum_item_detail"),
    AddMuseumItem("add_museum_item"),
    AnniversaryList("anniversary_list"),
    AddAnniversary("add_anniversary"),
    Wishlist("wishlist"),
    AddWishlist("add_wishlist"),
    RelationshipEvent("relationship_event"),
    AddRelationshipEvent("add_relationship_event"),
    MemoryCard("memory_card?targetType={targetType}&targetId={targetId}&itemTitle={itemTitle}"),

    // 收敛期新增页（W4.3 画像三合一 / W4.4 军师设置；仅新增，既有路由字符串不动）
    Understanding("understanding"),
    /** 画像历史版本：看 / 比 / 撤回（用户需求 #5）。 */
    ProfileVersions("profile_versions"),
    AdvisorSettings("advisor_settings"),
    /** v5.0：用户级 AI 服务配置（api-key/模型/端点，强制配置 D4）。 */
    AiConfig("ai_config"),

    // 整改 §8.3：AI 建议采用后回填结果的待反馈页（首页 feedback_outcome 任务卡入口）
    // sessionId 可空：从首页任务卡进（只知会话）；带 messageId 时直接定位到那条建议
    FeedbackOutcome("feedback_outcome"),

    // 2026-09-27 关系页改版（用户裁决）：调解邀请 / 双视角 / 解绑确认三项待办
    // 从关系页迁出，收敛为侧边栏「待办」条目 + 独立列表页（角标显示待办数）
    TodoList("todo_list"),

    // 观点（内部数据仍是 diary_entry）。2026-09-29 单身模式删除后，
    // 这三个路由归属情侣模式——抽屉「观点」条目与 GuideScreen 都指向 DiaryList。
    DiaryList("diary_list"),
    DiaryDetail("diary_detail"),
    ComposeDiary("compose_diary"),
}
