package com.couple.translator.feature.couple.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.AiDto
import com.couple.translator.core.data.repository.UsageHintStore
import com.couple.translator.core.service.AiStreamKeepAlive
import com.couple.translator.feature.couple.data.model.AnniversaryDto
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.repository.AiRepository
import com.couple.translator.feature.couple.data.repository.AnniversaryRepository
import com.couple.translator.feature.couple.data.repository.LetterRepository
import com.couple.translator.core.network.GenerationStreamEvent
import com.couple.translator.core.ui.components.AiRiskLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P0-8 引用 chip：同一时刻只允许一个，再选即替换。
 * [body] 是引用正文原文（可能很长），发送时按 §2.3 截断拼进 message。
 */
data class QuoteChip(
    val sourceLabel: String,
    val body: String,
    val originId: Long,
    val originRole: String? = null,
)

/** 「＋」二级引用选择器的三种来源 */
enum class QuotePickerType { MESSAGE, LETTER, ANNIVERSARY }

/**
 * 整改 §8.2：一条 AI 回复上「可发起的行动」。
 *
 * 刻意不在 UI 里写死按钮：同一行按钮在不同场景/语境下既不该全都在，也不该都没有。
 * [actionPlanFor] 是唯一的裁决点，可单测；调解按钮受 [FeatureGate.MEDIATION] 门控
 * （§8.5 未过验收前不许暴露残缺流程）。
 */
enum class AiAction(val label: String) {
    COPY_REPLY("复制这段表达"),
    SHARE_REPLY("分享这段表达"),
    MAKE_LETTER("整理成一封信"),
    INVITE_DUAL("邀请 TA 补充双视角"),
    START_MEDIATION("发起双人调解"),
    SAVE_REVIEW("记录为关系复盘"),
    // 整改 B4.1-P1：这里**没有** FEEDBACK。反馈不是一个"动作 chip"，
    // 它是同一屏上的次级控件，由 AiFeedbackRow 独立渲染（三态：未表态 /
    // 已采纳待结果 / 已填结果）。此前那枚 chip 的分支体是 `-> Unit`——
    // 点了没有任何反应，正是「装饰按钮」。删掉它而不是留着，
    // 是为了让编译器守住：任何再想从行动行里"顺手加一个反馈按钮"的改动都会失败。
}

object FeatureGate {
    /**
     * 调解门控（§8.2 / §8.5）：正式入口继续隐藏，直到状态机·隐私·异步九条全过。
     * 置 true 前必须完成 §8.5 全部验收——这是产品级红线，不是开关偏好。
     *
     * 2026-09-27 收口：真机 UI 验收的临时 `true` 已回改。产品收敛方案第 4 条明确
     * 「当前实现未闭环，应暂时隐藏正式入口，而不是带缺陷开放」——门控保持 false，
     * 直到双人调解走完完整验收。
     */
    const val MEDIATION = false
}

/**
 * 一条回答的**意图**（后端 `ActionIntent`；整改 B4.3 P1-7）。
 *
 * 为什么必须是一个独立字段而不是「按 sceneKey 推断」：同一个 sceneKey 下意图
 * 可以不同。`private_advisor` 既可能在做**冲突分析**（该给「发起双人调解」），
 * 也可能只是**情绪倾诉**（该只让用户继续说下去）。按 sceneKey 一刀切会把这两种
 * 完全相反的处置混成一件事——那正是整改前行动行的病根：动作只由「有哪些字段非空」
 * 决定，于是任何一次带 suggested_reply 的回答都会长出一排复制/分享/写信/双视角。
 *
 * `UNKNOWN` 是 fail closed 的落点：模型没给、给了非法值、或结构缺失时只保留
 * 「复制原回答」这一条最无害的出路。
 */
enum class AiIntent {
    EXPRESSION_REWRITE,
    PARTNER_TRANSLATE,
    PRIVATE_ADVISOR,
    EMOTION_SUPPORT,
    COLD_WAR,
    RELATIONSHIP_REVIEW,
    UNKNOWN;

    companion object {
        /**
         * 后端 `ActionIntent` 枚举里**会**出现在 wire 上的全部字面量。
         *
         * 被 `AiIntentContractTest` 直接断言：后端一旦新增意图取值，两侧集合
         * 就对不上，测试立刻失败——而不是等某个新意图在客户端静默回落到兜底。
         */
        val WIRE_VALUES = listOf(
            "expression_rewrite",
            "partner_translate",
            "private_advisor",
            "emotion_support",
            "cold_war",
            "relationship_review",
            "unknown",
        )

        /**
         * wire 值 → 意图。除大小写/首尾空格外**不做任何模糊匹配**：
         * 认错方向的代价不对称（把一次倾诉认成冲突 → 给用户一个会把伴侣
         * 拉进同一场会话的按钮）。
         */
        fun fromWire(raw: String?): AiIntent = when (raw?.trim()?.lowercase()) {
            "expression_rewrite" -> EXPRESSION_REWRITE
            "partner_translate" -> PARTNER_TRANSLATE
            "private_advisor" -> PRIVATE_ADVISOR
            "emotion_support" -> EMOTION_SUPPORT
            "cold_war" -> COLD_WAR
            "relationship_review" -> RELATIONSHIP_REVIEW
            // 后端 P0-4 起会把「无法归类」显式下发成 `unknown`。它与
            // 「客户端自己认不出来」落到同一个枚举值，处置也相同（只留复制）。
            // 单列出来而不是并进 `else`，是为了让契约测试能比对两端字面量：
            // 一个显式契约值和一个解析失败，在 wire 上是两件事。
            "unknown" -> UNKNOWN
            else -> UNKNOWN
        }
    }
}

/**
 * 场景 → 意图的**兜底映射**（整改 B4.3 P1-7）。
 *
 * 只在模型没给 `intent` 时使用。它不是「猜」：`sceneKey` 是用户自己选的、
 * 或服务端会话上记着的**结构化**字段，不是从正文里做关键词匹配——
 * 拿正文当路由依据才是被禁止的那件事。
 *
 * 兜底存在的理由：意图由模型产出，模型少填一次字段不该让整行动作凭空消失
 * （那是静默的能力回退）。兜底值取该场景最常见的意图，且**不引入任何
 * 该场景原本没有的动作**——即：兜底后的行为与整改前逐字节一致，
 * 模型的意图只可能让它更准，不可能让它更危险。
 */
fun intentOfScene(sceneKey: String): AiIntent = when (sceneKey) {
    "expression_rewrite" -> AiIntent.EXPRESSION_REWRITE
    "partner_translate" -> AiIntent.PARTNER_TRANSLATE
    "cold_war" -> AiIntent.COLD_WAR
    "relationship_review" -> AiIntent.RELATIONSHIP_REVIEW
    "private_advisor" -> AiIntent.PRIVATE_ADVISOR
    else -> AiIntent.UNKNOWN
}

/**
 * 一条回复的行动行分组（整改 B4.1-P1）。
 *
 * 为什么必须分组：`actionsFor` 此前是**无条件堆叠**——只要有可复制内容，
 * 复制/分享/写信/邀请/复盘/反馈六枚 chip 会同时铺满一行，用户面对一堵墙，
 * 真正该点的那个（比如"你们在吵，要不要一起谈谈"）被淹在最下面。
 *
 * 现在：主动作最多 [ActionLimits.PRIMARY_MAX] 个（按优先级取前 N），
 * 其余进 [more]，由 UI 折进「更多」。**反馈不在其中**——它是次级控件，
 * 由 [AiFeedbackRow] 单独渲染（此前那枚 FEEDBACK chip 点了没有任何反应，
 * 是典型的装饰按钮）。
 */
data class AiActionPlan(
    val primary: List<AiAction>,
    val more: List<AiAction>,
) {
    val all: List<AiAction> get() = primary + more

    val isEmpty: Boolean get() = primary.isEmpty() && more.isEmpty()
}

object ActionLimits {
    /**
     * 同屏主动作上限。
     *
     * 2 的依据：一个动作行同时承载「眼下最该做的一件事」和「备选的第二件」
     * 已经是认知上限；第三枚开始用户就不再读了（改文案也不会有人点）。
     */
    const val PRIMARY_MAX = 2
}

/** 「这段表达」的可复制文本：优先可直接发送的建议，其次破冰开场白，最后没有。 */
fun copyableReplyOf(structured: AiDto.StructuredOutput?, content: String): String? {
    structured?.suggestedReply?.takeIf { it.isNotBlank() }?.let { return it }
    structured?.openingLines?.firstOrNull { it.isNotBlank() }?.let { return it }
    structured?.rewrites?.firstOrNull()?.content?.takeIf { it.isNotBlank() }?.let { return it }
    return content.takeIf { it.isNotBlank() }
}

/**
 * 场景/语境 → 行动行（§8.2「按场景给上下文动作，非永久全按钮」+ B4.1-P1 收敛
 * + B4.3 P1-7 按意图路由）。
 *
 * 路由表在 [orderedActionsFor]（它是唯一裁决点，可单测）。这里只做两件事：
 *
 * 1. 把「门控」和「意图」两个已裁决的输入喂进去——门控只在这一处求值，
 *    模型说「你们在吵」不够，产品开关没开就不许出现；
 * 2. 把有序全集切成 [AiActionPlan]：前 [ActionLimits.PRIMARY_MAX] 个是主动作，
 *    其余折进「更多」。
 *
 * 反馈**不在本函数里**：它由 [AiFeedbackRow] 作为次级控件渲染（见 [AiActionPlan]）。
 */
fun actionPlanFor(
    sceneKey: String,
    structured: AiDto.StructuredOutput?,
    riskLevel: AiRiskLevel? = null,
): AiActionPlan {
    val ordered = orderedActionsFor(
        sceneKey = sceneKey,
        structured = structured,
        // 门控只在这一处裁决：模型说「你们在吵」不够，产品开关没开就不许出现。
        suggestMediation = FeatureGate.MEDIATION && structured?.suggestMediation == true,
        riskLevel = riskLevel,
        intent = resolveIntent(sceneKey, structured),
    )
    return AiActionPlan(
        primary = ordered.take(ActionLimits.PRIMARY_MAX),
        more = ordered.drop(ActionLimits.PRIMARY_MAX),
    )
}

/**
 * 取本次回答的意图（整改 B4.3 P1-7）。
 *
 * 优先用模型显式给出的 `intent`；模型没给 / 给了无法识别的值时，退到
 * [intentOfScene] 按场景兜底。**两层都不是从正文里做关键词猜测**——
 * 正文是模型自由生成的文本，拿它当路由依据等于把「该不该把两个人关进
 * 同一场会话」交给一次字符串匹配。
 *
 * 兜底的存在理由：意图由模型产出，模型少填一次字段不该让整行动作凭空消失
 * （那是静默的能力回退）。兜底值取该场景最常见的意图，且**不引入该场景原本
 * 没有的动作**——所以「模型没给」的最坏结果是行为与整改前一致，
 * 模型给了才可能更准。
 */
fun resolveIntent(sceneKey: String, structured: AiDto.StructuredOutput?): AiIntent {
    val wire = AiIntent.fromWire(structured?.intent)
    return if (wire != AiIntent.UNKNOWN) wire else intentOfScene(sceneKey)
}

/**
 * 安全门控：这些风险等级下**不得**给出「把人拉进同一场会话」的动作。
 *
 * 为什么客户端也要挡一道（后端已经会阻断产物）：行动行是在**离开页之前**
 * 就渲染好的。若只靠后端，用户会先看到一个「发起双人调解」按钮，点进去才
 * 发现流程走不通——那是一段残缺流程。控制/暴力/自伤语境下正确的事是把人
 * 引向安全资源，而不是把双方再关进一间屋子。
 *
 * **fail closed（整改 B4.3 P0-4）**：只有两件事可以放行——
 * - `null`：后端**明确说了** `normal`（见 [AiRiskLevel.fromWire]）；
 * - `HEATED_CONFLICT`：双方情绪激动正是调解要处理的场景（仍会渲染降温提示）。
 *
 * 其余一律挡：三个高危档位自不必说，[AiRiskLevel.UNKNOWN] 也要挡——
 * 「解析不出等级」不是「没有风险」。旧实现把 UNKNOWN 当放行，等于后端某天
 * 新增一个更危险的档位、或响应字段被改名时，客户端会把高风险当正常放行，
 * 还一声不响。
 */
fun mediationBlockedByRisk(riskLevel: AiRiskLevel?): Boolean = when (riskLevel) {
    null, AiRiskLevel.HEATED_CONFLICT -> false
    AiRiskLevel.MANIPULATION_RISK,
    AiRiskLevel.ABUSE_RISK,
    AiRiskLevel.SELF_HARM_RISK,
    AiRiskLevel.UNKNOWN,
    -> true
}

/**
 * 候选动作的**有序全集**（整改 B4.3 P1-7：按意图的确定性路由表）。
 *
 * 单独把这层抽出来是为了让「路由表」可被直接测试：如果它只藏在
 * [actionPlanFor] 里，那么「调解排第一」这条规则在门控关闭时**永远测不到**
 * ——测试只能退化成「门控关着时它不存在」，等于没测。把已裁决的
 * `suggestMediation` 作为参数传进来，测试就能在不改产品开关的前提下
 * 断言真实的路由行为。
 *
 * ## 为什么是「按意图」而不是「按有哪些字段非空」
 *
 * 整改前的规则是：只要有可复制内容就给复制/分享/写信/双视角，只要有结论就给
 * 复盘。于是**任何**一次带 `suggested_reply` 的回答都会长出一整排动作，
 * 不管用户其实是在倾诉、在问「TA 这句话什么意思」、还是真的在吵。
 * 用户面对一堵墙，真正该点的那个被淹在最下面。
 *
 * 现在每个意图有一张**确定性的**表，见下面 `when` 的每个分支。风险门控
 * （[mediationBlockedByRisk]）**优先于所有意图路由**：`wantsMediation` /
 * `wantsDual` 在任何分支里都先与 `canPullPartnerIn` 求与。
 */
fun orderedActionsFor(
    sceneKey: String,
    structured: AiDto.StructuredOutput?,
    suggestMediation: Boolean,
    riskLevel: AiRiskLevel? = null,
    intent: AiIntent = intentOfScene(sceneKey),
): List<AiAction> {
    val hasReply = copyableReplyOf(structured, "") != null
    // 有实质结论才谈得上「记录为复盘」：整屏空字段点一下只会得到一张空复盘
    val hasConclusion =
        !structured?.summary.isNullOrBlank() || !structured?.nextStep.isNullOrBlank()
    // 风险门控优先：高风险下「把人拉进同一场会话」的动作一律不给。
    // 复制/分享/写信不挡：那是让当事人**自己**把话说好，与风险处置不冲突。
    val canPullPartnerIn = !mediationBlockedByRisk(riskLevel)
    val wantsMediation = suggestMediation && canPullPartnerIn
    // 双视角必须是模型**明确建议**的：把伴侣拉进一次不必要的双人作业是打扰，
    // 而「缺省给」意味着任何一次解析失败都会打扰到对方。
    val wantsDual = structured?.suggestDualPerspective == true && canPullPartnerIn
    // 复盘同理：只有模型判定「有可复用的模式/触发点」才给存档入口。
    val wantsReview = structured?.reviewWorthy == true

    return when (intent) {
        // 帮我表达：复制 / 分享是主动作；写信折进「更多」——它是同一段表达的
        // 另一种载体，不是眼下更该做的一件事。不默认给双视角。
        AiIntent.EXPRESSION_REWRITE -> buildList {
            if (hasReply) {
                add(AiAction.COPY_REPLY)
                add(AiAction.SHARE_REPLY)
                add(AiAction.MAKE_LETTER)
            }
            if (wantsReview) add(AiAction.SAVE_REVIEW)
        }

        // 听懂 TA：只有「复制建议回复」这一件事。双视角仅在模型明确建议时给。
        AiIntent.PARTNER_TRANSLATE -> buildList {
            if (hasReply) add(AiAction.COPY_REPLY)
            if (wantsDual) add(AiAction.INVITE_DUAL)
        }

        // 冲突分析：调解排第一（「把两个人都拉进来说」比「换句话再说一遍」更治本），
        // 其次复制，有明确复盘价值时才给存档。
        AiIntent.PRIVATE_ADVISOR -> buildList {
            if (wantsMediation) add(AiAction.START_MEDIATION)
            if (hasReply) add(AiAction.COPY_REPLY)
            if (wantsReview) add(AiAction.SAVE_REVIEW)
        }

        // 情绪倾诉：**一个动作都不给**。用户此刻要的是被听见，不是被安排。
        // 空计划 = 页面不渲染行动行（见 `AiActionRow` 的空判断），
        // 剩下的唯一出路就是继续把话说完——这正是这一档该有的样子。
        AiIntent.EMOTION_SUPPORT -> emptyList()

        // 冷战：优先复制破冰表达（那才是用户点开这个场景要的东西），
        // 只有在**安全且模型明确建议**时才把调解放到它后面。
        AiIntent.COLD_WAR -> buildList {
            if (hasReply) add(AiAction.COPY_REPLY)
            if (wantsMediation) add(AiAction.START_MEDIATION)
        }

        // 关系复盘：看/存复盘。**不显示写信与双视角**——复盘页自己有完整入口，
        // 而「邀请伴侣补充双视角」在一次复盘里是把私密反思变成双人作业。
        AiIntent.RELATIONSHIP_REVIEW -> buildList {
            if (wantsReview || hasConclusion) add(AiAction.SAVE_REVIEW)
            if (hasReply) add(AiAction.COPY_REPLY)
        }

        // 无法归类（fail closed）：最多复制原回答。不猜、不给双人动作。
        AiIntent.UNKNOWN -> buildList {
            if (hasReply) add(AiAction.COPY_REPLY)
        }
    }
}

/**
 * 兼容入口：只关心「这一行有哪些动作」的调用方（旧测试、未来可能的埋点）。
 * 新代码请直接用 [actionPlanFor] —— 主动作上限是在分组里保证的。
 */
fun actionsFor(
    sceneKey: String,
    structured: AiDto.StructuredOutput?,
    riskLevel: AiRiskLevel? = null,
): List<AiAction> = actionPlanFor(sceneKey, structured, riskLevel).all

/** 待回访条目在会话语境下的最小形态（§8.3 反馈页与任务卡共用） */
data class PendingFeedback(
    val sessionId: Long,
    val messageId: Long,
    val sceneKey: String,
    val title: String?,
    val adopted: Boolean?,
    val createdAt: String?,
)

/**
 * §8.3：一次 AI 回复上「已落库的反馈形态」，供行动行与反馈行自处。
 * 与 [AiAction] 一起决定一行按钮怎么渲染（见 [FeedbackRowMode]）。
 */
enum class FeedbackRowMode {
    /** 还没表态：有帮助 / 没帮助 */
    ASK,

    /** 已采纳但没结果：提醒可补填结果 + 立即填写 */
    OUTCOME_DUE,

    /** 已填结果：回看已填内容，不再提供按钮 */
    DONE,
}

fun feedbackRowModeOf(feedback: AiDto.FeedbackOut?): FeedbackRowMode = when {
    feedback?.outcome?.isNotBlank() == true -> FeedbackRowMode.DONE
    feedback?.adopted == true -> FeedbackRowMode.OUTCOME_DUE
    else -> FeedbackRowMode.ASK
}

data class AiChatUiState(
    val sessionId: Long? = null,
    val sceneKey: String = "private_advisor",
    val messages: List<AiDto.MessageResponse> = emptyList(),
    /** 流式回答的增量累积；非空时 UI 应把它渲染成"正在输入"的气泡 */
    val streamingContent: String = "",
    /**
     * 推理模型思考过程的增量累积。
     *
     * 为什么要单独留一个字段：推理模型（qwen3.7-flash）的正文首帧实测要 18s+，
     * 而思考首帧约 0.5s。如果只累积流式正文，用户在这 18 秒里看到的是一片空白。
     * 把思考过程也收下来，UI 就能立刻渲染「正在深度思考」的可展开面板。
     * 它**不参与正文拼接**，只用于过程展示。
     */
    val thinkingContent: String = "",
    /** 思考是否已结束（正文已开始或流已终止）。UI 据此把面板从展开改为折叠 */
    val thinkingFinished: Boolean = false,
    /** 从发问到出正文之间的等待秒数，折叠后作为副标题展示（如「已深度思考 18 秒」） */
    val thinkingSeconds: Int = 0,
    val isStreaming: Boolean = false,
    val inputText: String = "",
    /** P-B §1.6：回答深度档位（quick/deep/expert）——只存 UiState，不进 DataStore */
    val chatMode: String = "deep",
    val isLoading: Boolean = false,
    val isLoadingMessages: Boolean = false,
    val showRewriteSheet: Boolean = false,
    val rewriteVersions: List<AiDto.RewriteVersion> = emptyList(),
    val rewriteOriginal: String = "",
    /** 表达改写流式生成中（v2.2 起走 ai_generation 流式端点） */
    val isRewriteStreaming: Boolean = false,
    /** 表达改写的流式正文（5 个风格逐段打字机） */
    val rewriteStreamContent: String = "",
    /** 表达改写模型的思考过程，喂「深度思考」面板 */
    val rewriteThinking: String = "",
    /** P0-5：最近一次回答的判断依据（画像/记忆/理论）；新提问时清空 */
    val evidence: AiDto.EvidencePayload? = null,
    /** P0-8：当前引用 chip（发送/切场景后清空） */
    val quoteChip: QuoteChip? = null,
    /** P0-8：信件选择器数据（打开「引用一封信」时加载）——TA 寄给我的（收件） */
    val quoteLetters: List<LetterDto.LetterResponse> = emptyList(),
    /** P-C4：我寄出的信（发件）——引用自己的信与引用 TA 的信作用不同，两源都要能引 */
    val quoteSentLetters: List<LetterDto.LetterResponse> = emptyList(),
    /** P0-8：纪念日选择器数据 */
    val quoteAnniversaries: List<AnniversaryDto.AnniversaryResponse> = emptyList(),
    val quotePickerLoading: Boolean = false,
    /** P0-8：加载失败必须可见，不许静默（项目红线） */
    val quotePickerError: String = "",
    // ---- P0-10B 会话边界（起/续/止/名）----
    /** 「正在继续 · {title}」；null 时顶部显示「新的对话」 */
    val sessionTitle: String? = null,
    /** 服务端判定当前 active 是否可直接续接 */
    val resumable: Boolean = true,
    /** 不可续接的「最近一段」（timeout/budget），只展示不自动加载 */
    val staleSessionId: Long? = null,
    val staleSessionTitle: String? = null,
    /** 本次发送发生过分段 → 消息列表尾部插「—— 新的对话 ——」 */
    val segmentNotice: Boolean = false,
    /** 补丁 A2：当前展示的是已归档会话 → 状态条「已结束的对话 · {title}」 */
    val sessionArchived: Boolean = false,
    // ---- P-C3 §3：上下文用量可见化 ----
    /** 当前会话已用 token（服务端口径：token_count 优先，否则字符数近似） */
    val tokenTotal: Int = 0,
    /** 预算上限，由 sessions/active 下发（客户端不硬编码 6000） */
    val budget: Int = 6000,
    /** 分段原因 timeout/budget/user_ended/scene_switch；null → 分隔行显示「新的对话」 */
    val segmentReason: String? = null,
    /** 80% 一次性提示（每段会话各一次，落库于 UsageHintStore） */
    val usageHintVisible: Boolean = false,
    val error: String = "",
) {
    /** 输入框是否应禁用：请求中或流式输出中都禁用，避免同会话并发（含表达改写流式） */
    val isBusy: Boolean get() = isLoading || isStreaming || isRewriteStreaming

    /** 是否正在思考（收到了思考增量但正文还没来） */
    val isThinking: Boolean get() = isStreaming && streamingContent.isEmpty()
}

sealed class AiChatUiEvent {
    data class ShowError(val message: String) : AiChatUiEvent()

    /** §8.3：轻量操作回执（已标记采用 / 已提交反馈），成功路径不该弹错误框 */
    data class ShowToast(val message: String) : AiChatUiEvent()
}

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    // P0-8：引用一封信 / 附上纪念日 —— 两者均已存在、Hilt 可注入，禁止新建 Repository
    private val letterRepository: LetterRepository,
    private val anniversaryRepository: AnniversaryRepository,
    // P-C3 §3.4：80% 提示的「每段会话各一次」标记（复用全局 DataStore）
    private val usageHintStore: UsageHintStore,
    // 流式回答期间挂前台服务保活，退后台不被系统掐断
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    /** 本次流的起始时刻，用于算出「已深度思考 N 秒」 */
    private var streamStartedAt = 0L

    /** 表达改写流式协程与取消上下文 */
    private var rewriteStreamJob: Job? = null
    private var rewriteGenerationId: Long = 0
    private var rewriteStopRequested = false

    init {
        loadScenes()
    }

    /**
     * 拉取后端场景清单并写进 [AiSceneCatalog]。
     *
     * 失败不提示用户：目录里已有一份与后端种子一致的内置清单，
     * 拉取只是把它换成服务端的权威版本。为这点差异弹错误框不划算。
     */
    private fun loadScenes() {
        viewModelScope.launch {
            aiRepository.getScenes().onSuccess { AiSceneCatalog.refresh(it) }
        }
    }

    /** 关闭错误弹窗（B-05：此前 Screen 传空的 onDismiss，弹窗无法关闭） */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }


    private val _event = MutableSharedFlow<AiChatUiEvent>()
    val event: SharedFlow<AiChatUiEvent> = _event.asSharedFlow()

    fun setSceneKey(sceneKey: String) {
        // P0-8：切场景清引用（§2.1 清空时机）
        _uiState.update { it.copy(sceneKey = sceneKey, quoteChip = null) }
    }

    /** P0-10B：从会话列表进入时同步状态条标题（loadSession 不带 title）。 */
    fun setSessionTitle(title: String?) {
        if (title.isNullOrBlank()) return
        _uiState.update { it.copy(sessionTitle = title) }
    }

    /** 补丁 A2：标记当前展示的是已归档会话（状态条显示「已结束的对话」）。 */
    fun setSessionArchived(archived: Boolean) {
        _uiState.update { it.copy(sessionArchived = archived) }
    }

    /**
     * 切换聊天场景。
     *
     * 只接受目录里的场景（[AiSceneCatalog]），而不是任意字符串——
     * 这正是此前 `ModeDrawerSheet` 能把 `reply` / `apologize` 这类
     * 后端不存在的 key 塞进请求的原因。
     *
     * P0-10B（§2.4）：切场景**不**调 closeSession——服务端按 scene_key
     * 分别维护 active，切回来还能续；分段由服务端规则/显式新对话触发。
     */
    fun selectScene(scene: AiScene) {
        setSceneKey(scene.key)
        // 清本地会话状态后按新场景向服务端要 active
        _uiState.update {
            it.copy(
                sessionId = null,
                messages = emptyList(),
                sessionTitle = null,
                segmentNotice = false,
                staleSessionId = null,
                staleSessionTitle = null,
                sessionArchived = false,
                evidence = null,
                quoteChip = null,
                // P-C3 §3.5：换场景后下一次分段的归因
                segmentReason = "scene_switch",
            )
        }
        refreshActiveSession()
    }

    // ------------------------------------------------------------------ #
    // P0-10B 会话边界：起 / 续 / 止 / 名
    // ------------------------------------------------------------------ //

    /**
     * 服务端权威刷新当前 active 会话——**唯一入口**（§2.3 规则）。
     *
     * 不放 init：init 只在 VM 首次创建跑一次，进程存活时再进页面不会刷新；
     * 正确挂点是 NewAiChatScreen 的 LaunchedEffect(Unit)（切 tab 重组即重跑）。
     *
     * 规则：isBusy 绝不覆盖；失败静默当新会话；resumable=false 只记 stale 不自动加载。
     */
    fun refreshActiveSession() {
        if (_uiState.value.isBusy) return
        val sceneKey = _uiState.value.sceneKey
        val currentId = _uiState.value.sessionId
        viewModelScope.launch {
            aiRepository.getActiveSession(sceneKey).fold(
                onSuccess = { info ->
                    // 跨模块 data class 属性不能 smart cast，先落到局部 val
                    val activeId = info.sessionId
                    // P-C3 §3.1：预算/用量以服务端下发为准（进页面是刷新点之一）
                    if (activeId == null) {
                        // 该 scope 无 active：保持本地现状（新会话）
                        _uiState.update {
                            it.copy(
                                resumable = false,
                                staleSessionId = null,
                                staleSessionTitle = null,
                                tokenTotal = 0,
                                budget = info.budget,
                            )
                        }
                    } else if (info.resumable) {
                        if (activeId == currentId) {
                            // 同一会话：只刷新标题，绝不覆盖正在展示的消息。
                            // 已确证是活跃会话 → 清 sessionArchived（补丁 A3）
                            _uiState.update {
                                it.copy(
                                    sessionTitle = info.title,
                                    resumable = true,
                                    sessionArchived = false,
                                    tokenTotal = info.tokenTotal,
                                    budget = info.budget,
                                )
                            }
                        } else {
                            // 换成活跃会话（loadSession 换消息）→ 屏幕内容已换，清标记
                            _uiState.update {
                                it.copy(
                                    sessionId = activeId,
                                    sessionTitle = info.title,
                                    resumable = true,
                                    staleSessionId = null,
                                    staleSessionTitle = null,
                                    sessionArchived = false,
                                    tokenTotal = info.tokenTotal,
                                    budget = info.budget,
                                )
                            }
                            loadSession(activeId)
                        }
                        maybeShowUsageHint()
                    } else {
                        // 不可续接（timeout/budget）：只记「最近一段」，等用户点继续。
                        // P-C3 §3.5：分段原因就取这次 active 的 archive_reason
                        _uiState.update {
                            it.copy(
                                resumable = false,
                                staleSessionId = activeId,
                                staleSessionTitle = info.title,
                                tokenTotal = info.tokenTotal,
                                budget = info.budget,
                                segmentReason = info.archiveReason ?: it.segmentReason,
                            )
                        }
                    }
                },
                onFailure = {
                    // 失败 → 静默当新会话（不弹错、不卡页面）
                },
            )
        }
    }

    /**
     * 「新对话」：先服务端归档旧会话（方案甲 POST close），失败也继续清本地。
     * 不清 sceneKey。
     */
    fun startNewChat() {
        val prevId = _uiState.value.sessionId
        viewModelScope.launch {
            if (prevId != null) {
                aiRepository.closeSession(prevId) // 失败也继续
            }
            _uiState.update {
                it.copy(
                    sessionId = null,
                    messages = emptyList(),
                    sessionTitle = null,
                    segmentNotice = false,
                    staleSessionId = null,
                    staleSessionTitle = null,
                    sessionArchived = false,
                    evidence = null,
                    quoteChip = null,
                    streamingContent = "",
                    thinkingContent = "",
                    isStreaming = false,
                    // P-C3 §3.5：手动开新段的归因（分隔行文案）
                    segmentReason = "user_ended",
                )
            }
        }
    }

    /** 「继续」上次不可续接的最近一段：显式带 id 加载，下一句也带 id 稳定续接。 */
    fun resumeStaleSession() {
        val staleId = _uiState.value.staleSessionId ?: return
        _uiState.update {
            it.copy(
                sessionId = staleId,
                staleSessionId = null,
                staleSessionTitle = null,
                resumable = true,
            )
        }
        loadSession(staleId)
    }

    // ------------------------------------------------------------------ #
    // P0-8 引用：chip 状态 + 二级选择器数据加载（复用既有 Repository）
    // ------------------------------------------------------------------ //

    fun setQuoteChip(chip: QuoteChip) {
        _uiState.update { it.copy(quoteChip = chip) }
    }

    fun clearQuoteChip() {
        _uiState.update { it.copy(quoteChip = null) }
    }

    /** 打开对应类型的引用选择器前调用：消息走本地 state，信/纪念日拉网。 */
    fun loadQuotePickerData(type: QuotePickerType) {
        when (type) {
            QuotePickerType.MESSAGE -> _uiState.update {
                it.copy(quotePickerLoading = false, quotePickerError = "")
            }
            QuotePickerType.LETTER -> {
                _uiState.update { it.copy(quotePickerLoading = true, quotePickerError = "") }
                viewModelScope.launch {
                    // P-C4：两种来源都拉——TA 寄来的（理解 TA）+ 我寄出的（改进表达），
                    // 作用不同必须都可引用；direction=sent/received 服务端均已排除草稿
                    val receivedRes = letterRepository.getInbox()
                    val sentRes = letterRepository.getLetters(direction = "sent")
                    val failure = receivedRes.exceptionOrNull() ?: sentRes.exceptionOrNull()
                    if (failure != null) {
                        // 红线：加载失败不许静默
                        _uiState.update {
                            it.copy(
                                quotePickerLoading = false,
                                quotePickerError = failure.message ?: "加载信件失败",
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                quoteLetters = receivedRes.getOrNull()?.items.orEmpty(),
                                quoteSentLetters = sentRes.getOrNull()?.items.orEmpty(),
                                quotePickerLoading = false,
                            )
                        }
                    }
                }
            }
            QuotePickerType.ANNIVERSARY -> {
                _uiState.update { it.copy(quotePickerLoading = true, quotePickerError = "") }
                viewModelScope.launch {
                    anniversaryRepository.getAnniversaries().fold(
                        onSuccess = { resp ->
                            _uiState.update {
                                it.copy(
                                    quoteAnniversaries = resp?.items.orEmpty(),
                                    quotePickerLoading = false,
                                )
                            }
                        },
                        onFailure = { e ->
                            _uiState.update {
                                it.copy(
                                    quotePickerLoading = false,
                                    quotePickerError = e.message ?: "加载纪念日失败",
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        // §2.3 长度红线：后端 ChatRequest.message max_length=2000，超了直接 422
        const val QUOTE_BODY_MAX = 600
        const val MESSAGE_SOFT_MAX = 1900
        const val MESSAGE_HARD_MAX = 2000

        /**
         * §2.2 拼接 + §2.3 截断。返回 (最终 message, 是否发生截断)。
         *
         * 顺序：引用正文先截 600 → 拼接 → 总长 >1900 则压引用正文预算
         * `1900 - header - 输入` → 仍 >2000 硬截到 2000。绝不发出 >2000 的 message。
         */
        fun assembleMessage(userInput: String, quote: QuoteChip?): Pair<String, Boolean> {
            if (quote == null) return userInput to false
            var body = quote.body
            var truncated = false
            if (body.length > QUOTE_BODY_MAX) {
                body = body.take(QUOTE_BODY_MAX) + "…"
                truncated = true
            }
            val prefix = "【引用·${quote.sourceLabel}】"
            val sep = "\n——\n"
            var msg = prefix + body + sep + userInput
            if (msg.length > MESSAGE_SOFT_MAX) {
                // 先把引用正文压到 1900 - 用户输入（再扣 header/sep）
                val bodyBudget = MESSAGE_SOFT_MAX - prefix.length - sep.length - userInput.length
                body = if (bodyBudget <= 0) {
                    ""
                } else {
                    val cut = quote.body.take(bodyBudget)
                    if (quote.body.length > bodyBudget) cut + "…" else cut
                }
                truncated = true
                msg = prefix + body + sep + userInput
            }
            if (msg.length > MESSAGE_HARD_MAX) {
                msg = msg.take(MESSAGE_HARD_MAX)
                truncated = true
            }
            return msg to truncated
        }

        /** chip 是否会触发截断（供 UI 显示「引用已自动精简」） */
        fun willTruncate(userInput: String, quote: QuoteChip?): Boolean {
            return assembleMessage(userInput, quote).second
        }
    }

    fun loadSession(sessionId: Long) {
        _uiState.update { it.copy(sessionId = sessionId, isLoadingMessages = true) }
        viewModelScope.launch {
            aiRepository.getSessionMessages(sessionId).fold(
                onSuccess = { messages ->
                    _uiState.update {
                        it.copy(messages = messages, isLoadingMessages = false)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoadingMessages = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /** P-B §1.6：切换回答深度档位（quick/deep/expert），仅存 UiState 不持久化 */
    fun setChatMode(mode: String) {
        _uiState.update { it.copy(chatMode = mode) }
    }

    fun dismissRewriteSheet() {
        stopRewrite()
        _uiState.update {
            it.copy(
                showRewriteSheet = false,
                rewriteVersions = emptyList(),
                rewriteOriginal = "",
                rewriteStreamContent = "",
                rewriteThinking = "",
                isRewriteStreaming = false,
            )
        }
    }

    fun rewriteExpression() {
        val state = _uiState.value
        val content = state.inputText.trim()
        if (content.isEmpty() || state.isBusy) return

        rewriteStopRequested = false
        rewriteGenerationId = 0

        // 弹层立刻打开：正文流式期间就能逐字看到，不再空等十几秒
        _uiState.update {
            it.copy(
                isRewriteStreaming = true,
                rewriteStreamContent = "",
                rewriteThinking = "",
                showRewriteSheet = true,
                rewriteVersions = emptyList(),
                rewriteOriginal = content,
                inputText = "",
                error = "",
            )
        }
        AiStreamKeepAlive.start(appContext)

        rewriteStreamJob = viewModelScope.launch {
            aiRepository.rewriteExpressionStream(content).collect { ev ->
                when (ev) {
                    is GenerationStreamEvent.Started -> rewriteGenerationId = ev.generationId

                    is GenerationStreamEvent.Thinking -> _uiState.update {
                        it.copy(rewriteThinking = it.rewriteThinking + ev.content)
                    }

                    is GenerationStreamEvent.Delta -> _uiState.update {
                        it.copy(rewriteStreamContent = it.rewriteStreamContent + ev.content)
                    }

                    is GenerationStreamEvent.Structuring -> Unit

                    is GenerationStreamEvent.Finished -> {
                        _uiState.update {
                            it.copy(
                                isRewriteStreaming = false,
                                rewriteVersions = parseRewriteVersions(ev.structured),
                                rewriteStreamContent = ev.content.ifBlank { it.rewriteStreamContent },
                            )
                        }
                        AiStreamKeepAlive.stop(appContext)
                    }

                    is GenerationStreamEvent.Failure -> if (!rewriteStopRequested) {
                        _uiState.update { it.copy(isRewriteStreaming = false, error = ev.message) }
                        AiStreamKeepAlive.stop(appContext)
                    }
                }
            }

            // 兜底：服务端没发 done 就断开时收敛状态
            if (!rewriteStopRequested && _uiState.value.isRewriteStreaming) {
                _uiState.update { it.copy(isRewriteStreaming = false) }
                AiStreamKeepAlive.stop(appContext)
            }
        }
    }

    /** 用户点「停止生成」。半成品保留在弹层里，服务端也存了 interrupted。 */
    fun stopRewrite() {
        if (!_uiState.value.isRewriteStreaming) return
        rewriteStopRequested = true
        _uiState.update { it.copy(isRewriteStreaming = false) }
        val id = rewriteGenerationId
        viewModelScope.launch {
            if (id != 0L) aiRepository.cancelGeneration(id)
        }
        rewriteStreamJob?.cancel()
        AiStreamKeepAlive.stop(appContext)
    }

    /** `Finished.structured` 的 `rewrites` → 弹层版本卡片 */
    private fun parseRewriteVersions(structured: Map<String, Any?>?): List<AiDto.RewriteVersion> {
        val list = (structured?.get("rewrites") as? List<*>) ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val style = m["style"] as? String ?: return@mapNotNull null
            val text = m["content"] as? String ?: return@mapNotNull null
            AiDto.RewriteVersion(style, text)
        }
    }

    fun applyRewrite(content: String) {
        _uiState.update {
            it.copy(
                inputText = content,
                showRewriteSheet = false,
                rewriteVersions = emptyList(),
                rewriteStreamContent = "",
                rewriteThinking = "",
                isRewriteStreaming = false,
            )
        }
    }


    /**
     * 发送消息，走 SSE 流式。
     *
     * 与旧实现的区别：不再等整段回答生成完才显示。
     * 收到 `meta` 立即绑定会话、收到 `delta` 立即追加文本、收到 `done` 才固化成正式消息。
     * 中途失败时**保留已收到的部分内容**，避免用户看到的内容整段消失。
     */
    fun sendMessage() {
        val state = _uiState.value
        val rawInput = state.inputText.trim()
        if (rawInput.isEmpty() || state.isBusy) return

        // P0-8 §2：有引用时按规范拼进 message（唯一能进模型的通道）
        val text = assembleMessage(rawInput, state.quoteChip).first

        val userMessage = AiDto.MessageResponse(
            id = System.currentTimeMillis(),
            sessionId = state.sessionId,
            role = "user",
            content = text,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                // §2.1：发送成功（消息已入列表）后清空引用
                quoteChip = null,
                isStreaming = true,
                streamingContent = "",
                thinkingContent = "",
                thinkingFinished = false,
                thinkingSeconds = 0,
                evidence = null,
                // 新一次发送：上一轮的分段分隔行随新消息上下文重置（Meta 若再分段会重新置 true）
                segmentNotice = false,
                // P-C3 §3.5：上一轮归因已随分隔行展示过 → 消费掉，
                // 防止后来无关的分段复用旧文案；还没展示过的（首句未分段）保留
                segmentReason = if (it.segmentNotice) null else it.segmentReason,
                error = "",
            )
        }
        streamStartedAt = System.currentTimeMillis()

        viewModelScope.launch {
            val request = AiDto.ChatRequest(
                sessionId = state.sessionId,
                sceneKey = state.sceneKey,
                message = text,
                chatMode = state.chatMode,
            )

            // 发出时的 sessionId：Meta 回来若不同 = 服务端发生了分段（§2.5/改动二）
            val sentSessionId = state.sessionId

            aiRepository.chatStream(request).collect { ev ->
                when (ev) {
                    is AiDto.ChatStreamEvent.Meta -> {
                        _uiState.update {
                            it.copy(
                                sessionId = ev.sessionId,
                                // 补丁 A1：拿到新会话即视为可续接的新对话——
                                // stale 提示必须消失，否则状态条优先级更高的
                                // 「上次聊到 X」会残留，点「继续」跳进已归档会话
                                staleSessionId = null,
                                staleSessionTitle = null,
                                sessionArchived = false,
                                // 服务端新建了会话（分段）→ 列表尾插分隔行。
                                // P-C3 §3.5：sentSessionId == null 且有归因
                                // （user_ended / scene_switch / stale 直接发言）也算分段，
                                // 否则这三类原因永远没机会展示；无归因的首句不插行
                                segmentNotice = if (
                                    (sentSessionId != null && ev.sessionId != sentSessionId) ||
                                    (sentSessionId == null && it.segmentReason != null)
                                ) {
                                    true
                                } else {
                                    it.segmentNotice
                                },
                                // 首轮：标题用用户输入前 12 字兜底（不轮询抢服务端异步标题）
                                sessionTitle = it.sessionTitle ?: rawInput.take(12),
                                // P-C3 §3.2：meta 是刷新点之一（不逐帧）
                                tokenTotal = ev.tokenTotal,
                            )
                        }
                        maybeShowUsageHint()
                    }

                    // 思考增量：只喂给「深度思考」面板，不拼进正文
                    is AiDto.ChatStreamEvent.Thinking -> _uiState.update {
                        it.copy(thinkingContent = it.thinkingContent + ev.content)
                    }

                    is AiDto.ChatStreamEvent.Delta -> _uiState.update {
                        it.copy(
                            streamingContent = it.streamingContent + ev.content,
                            // 正文一开始，思考就结束了：面板转为折叠态并标注耗时
                            thinkingFinished = true,
                            thinkingSeconds = it.thinkingSeconds
                                .takeIf { s -> s > 0 } ?: elapsedSeconds(),
                        )
                    }

                    // P0-5：evidence 帧在 done 之前到达，只存不拼正文
                    is AiDto.ChatStreamEvent.Evidence -> _uiState.update {
                        it.copy(evidence = ev.payload)
                    }

                    is AiDto.ChatStreamEvent.Done -> finishStream(ev)

                    is AiDto.ChatStreamEvent.Failure -> {
                        keepPartialThenFail(ev.message)
                        _event.emit(AiChatUiEvent.ShowError(ev.message))
                    }
                }
            }

            // 兜底：服务端若没发 done 就断开，这里把已收到的内容固化。
            // 否则 isStreaming 会一直是 true，输入框永久禁用。
            if (_uiState.value.isStreaming) {
                keepPartialThenFail("回答被中断，请重试")
            }
        }
    }

    /** 从发问算起的等待秒数，至少 1 秒（避免显示「已深度思考 0 秒」） */
    private fun elapsedSeconds(): Int {
        if (streamStartedAt == 0L) return 0
        val seconds = ((System.currentTimeMillis() - streamStartedAt) / 1000).toInt()
        return seconds.coerceAtLeast(1)
    }

    private fun finishStream(ev: AiDto.ChatStreamEvent.Done) {
        val state = _uiState.value
        val finalText = ev.content.ifBlank { state.streamingContent }
        val assistantMessage = AiDto.MessageResponse(
            id = if (ev.messageId != 0L) ev.messageId else System.currentTimeMillis(),
            sessionId = ev.sessionId.takeIf { it != 0L } ?: state.sessionId,
            role = "assistant",
            content = finalText,
            riskLevel = ev.riskLevel,
            // 整改 §8.2：done 帧带来的场景结构化字段直接挂到消息上——
            // 不这样做的话卡片要等重新拉消息列表才出现（用户看不到刚拿到的行动建议）。
            structuredOutput = aiRepository.parseStructured(ev.structured),
        )
        _uiState.update {
            it.copy(
                messages = it.messages + assistantMessage,
                streamingContent = "",
                isStreaming = false,
                // P-C3 §3.2：done 是落库后的权威值；0 = 服务端未带（如 blocked），
                // 不覆盖 meta 刚给的数
                tokenTotal = if (ev.tokenTotal > 0) ev.tokenTotal else it.tokenTotal,
            )
        }
        maybeShowUsageHint()
    }

    // ------------------------------------------------------------------ #
    // P-C3 §3.4：上下文用量 80% 一次性提示
    // ------------------------------------------------------------------ //

    /**
     * 用量过 80% 时弹一次提示——语义是「**每段会话各一次**」，
     * 用 [UsageHintStore] 记最近提示过的 sessionId，换新段（id 不同）才会再弹。
     * 不在场（无 sessionId / budget<=0）或已提示过则静默。
     */
    private fun maybeShowUsageHint() {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        val budget = state.budget
        if (budget <= 0) return
        if (state.tokenTotal < budget * 0.8) return
        if (state.usageHintVisible) return
        viewModelScope.launch {
            if (usageHintStore.isHinted(sessionId)) return@launch
            usageHintStore.markHinted(sessionId)
            _uiState.update { it.copy(usageHintVisible = true) }
        }
    }

    /** 用户关掉 80% 提示（已 markHinted，本段不再弹）。 */
    fun dismissUsageHint() {
        _uiState.update { it.copy(usageHintVisible = false) }
    }

    // ------------------------------------------------------------------ #
    // 整改 §8.3：采用与结果反馈闭环
    // ------------------------------------------------------------------ #

    /**
     * 标记「这条建议我采用了」（可带 1-5 星，或不带评分）。
     *
     * 服务端是 upsert（同一消息同一用户原地更新），所以「先采纳、稍后回填结果」
     * 两次调用落在同一行；这里也把回执写回列表，用户不必重新进会话才看到。
     */
    fun markAdopted(messageId: Long, adopted: Boolean, rating: Int? = null) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            aiRepository.submitFeedback(
                sessionId,
                AiDto.FeedbackRequest(
                    rating = rating,
                    adopted = adopted,
                    messageId = messageId,
                ),
            ).fold(
                onSuccess = { fb ->
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id != messageId) msg
                                else msg.copy(feedback = fb)
                            },
                        )
                    }
                    _event.emit(
                        AiChatUiEvent.ShowToast(if (adopted) "已记为采用" else "已记为没用上"),
                    )
                },
                onFailure = { e ->
                    _event.emit(AiChatUiEvent.ShowError(e.message ?: "反馈提交失败"))
                },
            )
        }
    }

    /** §8.3：立即回填结果（也可稍后在「待反馈」页补填，同一行原地更新）。 */
    fun submitOutcome(messageId: Long, outcome: String) {
        val sessionId = _uiState.value.sessionId ?: return
        val text = outcome.trim()
        if (text.isEmpty()) {
            // 非 suspend 函数里不能直接 emit；空文本只提示、不发请求，丢进 scope 即可
            viewModelScope.launch {
                _event.emit(AiChatUiEvent.ShowError("说说后来怎么样了"))
            }
            return
        }
        viewModelScope.launch {
            aiRepository.submitFeedback(
                sessionId,
                AiDto.FeedbackRequest(messageId = messageId, outcome = text),
            ).fold(
                onSuccess = { fb ->
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id != messageId) msg else msg.copy(feedback = fb)
                            },
                        )
                    }
                    _event.emit(AiChatUiEvent.ShowToast("已记录结果，谢谢反馈"))
                },
                onFailure = { e ->
                    _event.emit(AiChatUiEvent.ShowError(e.message ?: "结果提交失败"))
                },
            )
        }
    }

    private fun keepPartialThenFail(message: String) {
        val state = _uiState.value
        val partial = state.streamingContent.trim()
        val extra = if (partial.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AiDto.MessageResponse(
                    id = System.currentTimeMillis(),
                    sessionId = state.sessionId,
                    role = "assistant",
                    content = partial,
                )
            )
        }
        _uiState.update {
            it.copy(
                messages = it.messages + extra,
                streamingContent = "",
                isStreaming = false,
                error = message,
            )
        }
        AiStreamKeepAlive.stop(appContext)
    }

    override fun onCleared() {
        super.onCleared()
        // 页面销毁时流式协程会随之取消，保活服务没有存在的必要了
        AiStreamKeepAlive.stop(appContext)
    }
}
