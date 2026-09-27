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
    MemoryCard("memory_card?targetType={targetType}&targetId={targetId}&itemTitle={itemTitle}"),

    // 收敛期新增页（W4.3 画像三合一 / W4.4 军师设置；仅新增，既有路由字符串不动）
    Understanding("understanding"),
    AdvisorSettings("advisor_settings"),

    // 整改 §8.3：AI 建议采用后回填结果的待反馈页（首页 feedback_outcome 任务卡入口）
    // sessionId 可空：从首页任务卡进（只知会话）；带 messageId 时直接定位到那条建议
    FeedbackOutcome("feedback_outcome"),

    // 单身模式专属
    DiaryList("diary_list"),
    DiaryDetail("diary_detail"),
    ComposeDiary("compose_diary"),
    SelfPracticeList("self_practice_list"),
}
