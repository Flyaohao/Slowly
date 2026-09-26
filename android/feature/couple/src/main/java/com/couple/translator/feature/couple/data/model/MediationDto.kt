package com.couple.translator.feature.couple.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object MediationDto {

    @JsonClass(generateAdapter = true)
    data class MediationStartRequest(
        @Json(name = "partner_user_id") val partnerUserId: Long,
    )

    @JsonClass(generateAdapter = true)
    data class MediationSessionResponse(
        @Json(name = "session_id") val sessionId: Long = 0,
        @Json(name = "partner_user_id") val partnerUserId: Long? = null,
        @Json(name = "mediation_status") val mediationStatus: String = "inviting",
        // 契约 §2.3-1：start 响应带 my_role（"inviter"）。后端未落地前为 null，FE 降级。
        @Json(name = "my_role") val myRole: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationInputRequest(
        @Json(name = "content") val content: String,
    )

    @JsonClass(generateAdapter = true)
    data class MediationConfirmRequest(
        @Json(name = "confirmed") val confirmed: Boolean,
        @Json(name = "supplement") val supplement: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationRewrite(
        @Json(name = "original") val original: String = "",
        @Json(name = "rewritten") val rewritten: String = "",
    )

    @JsonClass(generateAdapter = true)
    data class MediationStructuredOutput(
        @Json(name = "rewrite_a") val rewriteA: String? = null,
        @Json(name = "rewrite_b") val rewriteB: String? = null,
        @Json(name = "common_points") val commonPoints: List<String> = emptyList(),
        @Json(name = "differences") val differences: List<String> = emptyList(),
        @Json(name = "next_actions") val nextActions: List<String> = emptyList(),
        @Json(name = "risk_level") val riskLevel: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationMessageItem(
        @Json(name = "id") val id: Long = 0,
        @Json(name = "role") val role: String = "user",
        @Json(name = "content") val content: String = "",
        @Json(name = "structured_output") val structuredOutput: MediationStructuredOutput? = null,
        @Json(name = "risk_level") val riskLevel: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class MediationDetailResponse(
        @Json(name = "session_id") val sessionId: Long = 0,
        @Json(name = "mediation_status") val mediationStatus: String = "inviting",
        @Json(name = "session_type") val sessionType: String? = null,
        @Json(name = "user_id") val userId: Long? = null,
        @Json(name = "partner_user_id") val partnerUserId: Long? = null,
        @Json(name = "messages") val messages: List<MediationMessageItem> = emptyList(),
        // 契约 §2.3-2：GET {id} 是唯一状态真源，带服务端推导的 my_role；
        // 后端未落地前为 null，FE 用 userId==我 降级推导（隐藏 ≠ 删除：导航参数仍作兜底）
        @Json(name = "my_role") val myRole: String? = null,
        @Json(name = "partner_submitted") val partnerSubmitted: Boolean? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    /** 契约 §2.3-3 列表条目（DEV 确认字段）。全默认值，形状漂移时整表降级为空。 */
    @JsonClass(generateAdapter = true)
    data class MediationListItem(
        @Json(name = "session_id") val sessionId: Long = 0L,
        @Json(name = "mediation_status") val mediationStatus: String = "inviting",
        @Json(name = "session_type") val sessionType: String? = null,
        @Json(name = "user_id") val userId: Long? = null,
        @Json(name = "partner_user_id") val partnerUserId: Long? = null,
        @Json(name = "my_role") val myRole: String? = null,
        @Json(name = "title") val title: String? = null,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
    )

    /** 调解列表响应（DEV 确认：`{total, items}` 包装对象，非裸数组）。 */
    @JsonClass(generateAdapter = true)
    data class MediationListResponse(
        @Json(name = "total") val total: Int = 0,
        @Json(name = "items") val items: List<MediationListItem> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    data class MediationNextRequest(
        @Json(name = "action") val action: String,
    )

    @JsonClass(generateAdapter = true)
    data class MediationMessage(
        @Json(name = "type") val type: String,
        @Json(name = "session_id") val sessionId: Long? = null,
        @Json(name = "status") val status: String? = null,
        @Json(name = "data") val data: MediationDetailResponse? = null,
    )
}

// ---------- 派生视图（不是线路字段，故意定义在 object 之外） ----------
//
// 为什么用扩展属性：Moshi 的 kapt 代码生成器会扫描 @JsonClass data class **类体内**
// 定义的所有属性，并尝试为它们生成序列化代码；类体内放的派生属性会让编译直接失败
// （"property xxx is not visible"）。扩展属性不产生成员，Moshi 完全看不到。

/** 后端改写消息只区分 rewrite_a/rewrite_b，无法得知当前用户是哪一侧，取 a 侧作为「我的改写」 */
val MediationDto.MediationDetailResponse.myRewrite: MediationDto.MediationRewrite?
    get() = messages.lastOrNull { it.structuredOutput?.rewriteA != null }
        ?.structuredOutput
        ?.rewriteA
        ?.let { MediationDto.MediationRewrite(rewritten = it) }

val MediationDto.MediationDetailResponse.partnerRewrite: MediationDto.MediationRewrite?
    get() = messages.lastOrNull { it.structuredOutput?.rewriteB != null }
        ?.structuredOutput
        ?.rewriteB
        ?.let { MediationDto.MediationRewrite(rewritten = it) }

private val MediationDto.MediationDetailResponse.summaryOutput: MediationDto.MediationStructuredOutput?
    get() = messages.lastOrNull {
        val s = it.structuredOutput
        s != null && (s.commonPoints.isNotEmpty() || s.nextActions.isNotEmpty())
    }?.structuredOutput

val MediationDto.MediationDetailResponse.commonPoints: List<String>
    get() = summaryOutput?.commonPoints ?: emptyList()

val MediationDto.MediationDetailResponse.diffPoints: List<String>
    get() = summaryOutput?.differences ?: emptyList()

val MediationDto.MediationDetailResponse.nextActions: List<String>
    get() = summaryOutput?.nextActions ?: emptyList()
