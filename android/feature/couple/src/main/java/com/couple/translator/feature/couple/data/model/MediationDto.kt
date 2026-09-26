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
        // 整改 §8.5-5：confirm / input 的响应带回「双方各自」的确认与提交状态，
        // 页面据此渲染「等对方确认」等待态，不必再补一次 GET。
        @Json(name = "my_confirmed") val myConfirmed: Boolean? = null,
        @Json(name = "partner_confirmed") val partnerConfirmed: Boolean? = null,
        @Json(name = "partner_submitted") val partnerSubmitted: Boolean? = null,
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
        // §8.5-7：我是否已提交。断线重进时「输入框 or 等待态」的判定依据——
        // 我是第一方时会话状态一直停在 inputting，只看状态分不出「还没写」和「写了在等」。
        @Json(name = "my_submitted") val mySubmitted: Boolean? = null,
        @Json(name = "updated_at") val updatedAt: String? = null,
        // ---- 整改 §8.5-4：改写由**服务端按身份**解析好再下发 ----
        // `my_rewrite` 是「我」那一侧（服务端按 my_role 从 rewrite_a/b 取），
        // 客户端不许再自己按 a/b 位置取——它的语义是「发起方/参与方」而不是
        // 「发言顺序」，本地硬编码读 rewrite_a 会把角色搞反，还会在未公开时
        // 把对方那一侧读出来（§8.5-3 由服务端过滤，这里只是消费过滤后的结果）。
        @Json(name = "my_rewrite") val myRewrite: MediationRewrite? = null,
        @Json(name = "partner_rewrite") val partnerRewrite: MediationRewrite? = null,
        // §8.5-5：双方各自的确认状态（断线重进按它恢复步骤）
        @Json(name = "my_confirmed") val myConfirmed: Boolean? = null,
        @Json(name = "partner_confirmed") val partnerConfirmed: Boolean? = null,
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
//
// 整改 §8.5-4：`myRewrite` / `partnerRewrite` **不再是派生属性**——改由服务端
// 按身份解析后直出（见 [MediationDto.MediationDetailResponse] 的字段注释）。
// 此前这里用「取最后一条含 rewrite_a 的消息」拼 myRewrite，等于把发起方那一侧
// 硬编码成「我的改写」：参与方看到的会是对方的话。禁止再按 a/b 位置本地取值。

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
