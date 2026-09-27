package com.couple.translator.core.data.model

/**
 * 画像维度的中文名（客户端投影）。
 *
 * ⚠️ 必须与后端 `app/services/profile_service.DIMENSION_DEFINITIONS` 的 `label`
 * 逐字保持一致——新增维度时两边都要改。
 *
 * 为什么不做成接口下发：维度是**产品配置**，一版之内不会变，为它加一次网络
 * 往返（还要处理加载失败）不划算。权威仍在服务端：维度 key 不在白名单里时，
 * `POST /profiles/me/enrich` 会直接拒绝，客户端这张表顶多影响显示得好不好看。
 */
object ProfileDimensionLabels {

    private val LABELS = mapOf(
        "attachment_anxiety" to "依恋焦虑",
        "attachment_avoidance" to "依恋回避",
        "conflict_pursue" to "冲突追问倾向",
        "conflict_withdraw" to "冲突退缩倾向",
        "defensive_response" to "防御反驳倾向",
        "emotional_validation_need" to "情绪确认需求",
        "factual_explanation_need" to "事实解释需求",
        "personal_space_need" to "独处冷静需求",
        "reassurance_need" to "安全感确认需求",
        "directness_preference" to "直接表达偏好",
        "softness_preference" to "柔和表达偏好",
        // 仅由用户观点补充，问卷不产出（后端 from_questionnaire=False）
        "values_orientation" to "价值取向",
    )

    /** 取不到时回退到 key 本身——宁可显示英文，也不要显示空白。 */
    fun of(key: String): String = LABELS[key] ?: key
}
