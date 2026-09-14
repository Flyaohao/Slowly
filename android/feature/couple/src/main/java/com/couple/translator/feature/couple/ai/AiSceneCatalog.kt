package com.couple.translator.feature.couple.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Icecream
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.ui.graphics.vector.ImageVector
import com.couple.translator.core.data.model.AiDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 场景被选中后应该去哪里。
 *
 * `mediation` 有独立的调解工作流页面（`/api/v1/couple/ai/mediation` 下的子路由），
 * **不是聊天场景**：后端 `SCENE_OUTPUT_MODELS` 里根本没有 `mediation` 这个 key，
 * 把它当聊天场景发给 `/ai/chat` 只会静默回退到 `TranslateOutput`——用户选
 * "双人调解"，拿到的却是一段通用的翻译腔回答。所以这里显式区分
 * "切换聊天场景"和"跳转专属页面"。
 *
 * `cold_war` 反过来是合法的聊天场景（`ColdWarOutput` 已登记，`AiMessageCard`
 * 也渲染了它的专属字段），所以保持 `CHAT`，不跳转。
 */
enum class AiSceneTarget { CHAT, MEDIATION }

/**
 * 一个 AI 场景在 UI 里的完整描述。
 *
 * [label] / [description] 以**后端返回为准**；[chipLabel] / [icon] / [showInQuickChips] /
 * [target] 是后端不存的展示信息，由 [AiSceneCatalog] 本地补充。
 */
data class AiScene(
    val key: String,
    val label: String,
    /** 快捷 chip 上的口语化文案，与顶栏的正式 [label] 刻意不同 */
    val chipLabel: String,
    val description: String,
    val icon: ImageVector?,
    val showInQuickChips: Boolean,
    /** 是否出现在「选择模式」抽屉里 */
    val showInDrawer: Boolean,
    /**
     * 能否作为聊天场景发给 `/ai/chat`。
     *
     * 为 false 说明它不是聊天场景：要么有专属页面（见 [AiSceneTarget]），
     * 要么是别处的功能入口（信件改写只从信件页进）。
     * 后端遇到未知场景会静默回退到私人军师的 prompt——选错不会报错，只会答偏。
     */
    val selectableAsChatScene: Boolean,
    val target: AiSceneTarget,
)

/**
 * 客户端**唯一**的 AI 场景清单来源。
 *
 * 为什么要有这个类：此前场景清单散落在 5 处硬编码里，彼此不同步——
 * `AiChatScreen` 5 个、`AiSessionListScreen` 3 个、`SceneSelector` 5 个、
 * `NewAiChatScreen.quickScenes` 4 个、`ModeDrawerSheet.aiModes` 6 个
 * （其中 `reply` / `apologize` 根本不是后端合法 scene_key，选中即静默回退）。
 * 后端新增场景客户端永远不会知道，`letter_understand` 就是这样变成"后端能力齐备、
 * 产品里进不去"的。
 *
 * 现在：**场景集合以后端 `GET /ai/scenes` 为准**（[refresh]），本地只维护
 * 「口语化文案 + 图标 + 跳转目标」这类后端没有的展示信息（[Presentation]）。
 * 后端不在返回里的场景会被丢弃，返回里新增的场景会以 `name` 兜底展示——
 * 两端再也不会静默漂移。
 *
 * 用 [StateFlow] 而非普通 Map：Compose 侧 `collectAsState()` 后，
 * 远端场景拉回来后 UI 会自动重组，不需要额外的刷新回调。
 */
object AiSceneCatalog {

    /**
     * 后端不存的展示信息。**键必须与后端 `ai_scene.scene_key` 一致**。
     * 没有条目的场景会用后端 `name` 兜底（图标为空、不进快捷 chip、进聊天）。
     */
    private data class Presentation(
        val label: String,
        val chipLabel: String,
        val icon: ImageVector,
        val showInQuickChips: Boolean = false,
        /** 是否出现在「选择模式」抽屉里 */
        val showInDrawer: Boolean = true,
        /**
         * 能否作为聊天场景发给 `/ai/chat`。
         *
         * 置 false 的场景必须满足其一：有专属页面（见 [AiSceneTarget]），
         * 或是别处的功能入口（信件改写只从信件页进，聊天链路里没有它需要的"改写风格"入参）。
         * 后端对未知场景会**静默回退**到私人军师的 prompt，选错场景不会报错只会答偏，
         * 所以这里的每一项都要有真实依据。
         */
        val selectableAsChatScene: Boolean = true,
        val target: AiSceneTarget = AiSceneTarget.CHAT,
    )

    private val presentation: Map<String, Presentation> = mapOf(
        "private_advisor" to Presentation("日常", "帮我理清", Icons.Outlined.AutoAwesome, showInQuickChips = true),
        "partner_translate" to Presentation("听懂 TA", "听懂 TA", Icons.Outlined.Hearing, showInQuickChips = true),
        "expression_rewrite" to Presentation("帮我表达", "帮我表达", Icons.Outlined.AutoAwesome, showInQuickChips = true),
        "cold_war" to Presentation("冷静一下", "冷静一下", Icons.Outlined.Icecream, showInQuickChips = true),
        "mediation" to Presentation(
            "双人调解", "双人调解", Icons.Outlined.People,
            selectableAsChatScene = false,          // 没有输出模型，靠专属调解工作流
            target = AiSceneTarget.MEDIATION,
        ),
        "letter_understand" to Presentation("信件解读", "信件解读", Icons.Outlined.MailOutline),
        // 信件改写只有信件页能进：它需要"改写风格"入参，聊天 UI 收不到；
        // 而后端 `SYSTEM_PROMPTS` 里也没有它，`build_prompt` 会静默回退成私人军师。
        "letter_rewrite" to Presentation(
            "信件改写", "信件改写", Icons.Outlined.Edit,
            showInDrawer = false,
            selectableAsChatScene = false,
        ),
    )

    /** 后端 `ai_scene` 种子里的 7 个场景。仅在 [refresh] 成功前作为占位，避免首屏空白 */
    private val seed: List<AiDto.SceneResponse> = listOf(
        AiDto.SceneResponse("private_advisor", "私人军师", "私密模式，AI 先安抚情绪，再结合画像给出建议。"),
        AiDto.SceneResponse("partner_translate", "对方翻译", "输入伴侣的一句话，AI 帮你理解背后含义。"),
        AiDto.SceneResponse("expression_rewrite", "表达改写", "输入你想说的话，AI 帮你改写成更柔和的版本。"),
        AiDto.SceneResponse("cold_war", "冷战调解", "冷战状态下的沟通指导，帮助打破僵局。"),
        AiDto.SceneResponse("mediation", "矛盾调解", "中立的矛盾调解和沟通建议。"),
        AiDto.SceneResponse("letter_understand", "信件解读", "深入理解一段文字的含义，逐句分析。"),
        AiDto.SceneResponse("letter_rewrite", "信件改写", "根据风格要求改写信件，保留核心诉求。"),
    )

    private val _scenes = MutableStateFlow(seed.map(::toScene))

    /** 当前可用场景，顺序即后端返回顺序（种子顺序与后端一致） */
    val scenes: StateFlow<List<AiScene>> = _scenes.asStateFlow()

    /** 快捷 chip 只放最常用的几个，避免首屏被场景列表占满 */
    val quickChips: List<AiScene> get() = _scenes.value.filter { it.showInQuickChips }

    /** 用后端返回覆盖本地清单。空列表视为无效响应，保持现状以免把 UI 清空 */
    fun refresh(remote: List<AiDto.SceneResponse>) {
        if (remote.isEmpty()) return
        _scenes.value = remote.map(::toScene)
    }

    /** 取展示名。未知 key 原样返回，便于在界面上直接暴露契约问题而不是显示"未知" */
    fun labelOf(key: String): String =
        _scenes.value.firstOrNull { it.key == key }?.label ?: key

    fun byKey(key: String): AiScene? = _scenes.value.firstOrNull { it.key == key }

    private fun toScene(dto: AiDto.SceneResponse): AiScene {
        val p = presentation[dto.sceneKey]
        val label = p?.label ?: dto.name
        return AiScene(
            key = dto.sceneKey,
            label = label,
            chipLabel = p?.chipLabel ?: label,
            description = dto.description.orEmpty(),
            icon = p?.icon,
            showInQuickChips = p?.showInQuickChips ?: false,
            showInDrawer = p?.showInDrawer ?: true,
            selectableAsChatScene = p?.selectableAsChatScene ?: true,
            target = p?.target ?: AiSceneTarget.CHAT,
        )
    }
}
