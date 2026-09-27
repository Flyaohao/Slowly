package com.couple.translator.navigation

import com.couple.translator.core.navigation.BottomTab
import com.couple.translator.core.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导航可达性回归（整改 §8.0 验收口径 / §8.9-7「防『注册了但进不去』复发」）。
 *
 * 为什么要有这条测试：收敛期的做法是「切断入口、保留路由」，
 * 于是最典型的回归不是编译失败，而是**代码全对、用户进不去**——
 * composable 老老实实注册着，入口却被注释掉、文案改错、或换了个路由字符串。
 * 这种缺陷不会让任何构建或单测变红，只会让产品走查失败。所以这里用一条
 * 能自己变红的测试把守：**凡是注册在导航图里的页面，必须有真实入口引用**。
 *
 * 规则（与 android/CLAUDE.md 的描述一致）：
 * 1. 扫描 4 个模块的 main 源码，先剥掉注释，再按括号配对抠出每个
 *    `composable(...)` / `navigation(...)` 的**注册头**，注册头里出现的路由就是「已注册」；
 * 2. 「入口引用」= 注册头之外出现 `Screen.X.route` / `BottomTab.X.route` 标识符，
 *    或者一个恰好等于该路由（含 `?query` / `/path` 形式）的字符串字面量；
 * 3. 已注册但无入口 → 必须落在 [HIDDEN_WITHOUT_ENTRY] 豁免表里并写明理由。
 *    豁免表用**断言固定**：新增豁免必须改测试，改不动就说明真的断了。
 *
 * 豁免表刻意做小，而且只放契约 §1 明确裁决为冻结/隐藏、或被 W4.3/W4.4 合并掉的页面；
 * 「保留能力」一个都不许进（§8.0：保留能力必须有用户真实可走的入口路径）。
 *
 * 检测纯度已按当前代码实测校准：57 条注册路由、0 条漏网，豁免正好命中 4 条。
 */
class RouteReachabilityTest {

    private companion object {
        /** 声明文件本身只是「名字 ↔ 路由」映射表，不能算入口。 */
        val DECLARATION_FILES = setOf("Screen.kt", "BottomTab.kt")

        val MODULE_SOURCE_ROOTS = listOf(
            "app/src/main/java",
            "core/src/main/java",
            "feature/couple/src/main/java",
            "feature/single/src/main/java",
        )

        /**
         * 已注册但（按契约）可以没有用户入口的页面。
         * 每一条都要能指着契约条文说清为什么——它们是「隐藏」的对象，不是被遗忘的页面。
         */
        val HIDDEN_WITHOUT_ENTRY = mapOf(
            // §1 冻结表中的模块：纪念馆（museum 系列）/ 愿望清单 / 自我练习。
            "couple_profile" to "W4.3：关系画像已并入「人格画像」，路由保留备查",
            // W4.3 合并：我的画像入口随遗留壳 MainScreen 的删除消失，路由保留备查
            "profile_result" to "W4.3：我的画像已并入「人格画像」，路由保留备查",
            "self_practice_list" to "§1：自我练习冻结 10006，入口按收敛期裁决切断",
        )

        /**
         * 「保留能力」的入口期望表（§8.0 六步走查的第一、二步：能发现 → 能发起）。
         *
         * 这里只放**本轮整改明确要求打通**的链路，一条链路至少一个入口：
         * 一旦某个入口被误删/误注释，测试立刻变红，且报错直接点出是哪个文件丢的。
         */
        val REQUIRED_ENTRIES: Map<String, Set<String>> = mapOf(
            // §8.1 深度表达（信件）
            Screen.LetterList.route to setOf("CoupleShell.kt"),
            Screen.ComposeLetter.route to setOf("CoupleShell.kt"),
            Screen.LetterDetail.route to setOf("CoupleShell.kt"),
            // §8.2 AI 行动
            Screen.FeedbackOutcome.route to setOf("CoupleShell.kt", "NewAiChatScreen.kt"),
            Screen.RelationshipReview.route to setOf("CoupleShell.kt", "NavGraph.kt"),
            Screen.ReviewHistory.route to setOf("NavGraph.kt"),
            // §8.6 双视角
            Screen.DualPerspectiveList.route to setOf("RealtimeNotice.kt"),
            Screen.DualPerspectiveDetail.route to setOf("NavGraph.kt", "RealtimeNotice.kt"),
            Screen.CreateDualEvent.route to setOf("NavGraph.kt", "NewAiChatScreen.kt"),
            Screen.SubmitDualRecord.route to setOf("NavGraph.kt"),
            // §8.5 调解（正式入口受 FeatureGate 门控，但页面链路必须完整可达）
            // 2026-09-27 关系页改版：调解邀请的列表入口从 RelationScreen 迁到 TodoListScreen
            Screen.MediationExplanation.route to setOf("CoupleShell.kt", "NavGraph.kt"),
            Screen.MediationInvite.route to setOf("NavGraph.kt", "TodoListScreen.kt"),
            Screen.MediationInput.route to setOf("NavGraph.kt"),
            Screen.MediationConfirm.route to setOf("NavGraph.kt"),
            Screen.MediationResult.route to setOf("NavGraph.kt"),
            Screen.MediationHistory.route to setOf("RelationScreen.kt"),
            // §8.8 记忆与隐私 / 画像
            Screen.Memory.route to setOf("CoupleShell.kt", "UnderstandingScreen.kt", "DrawerContent.kt"),
            Screen.Understanding.route to setOf("DrawerContent.kt"),
            Screen.AdvisorSettings.route to setOf("DrawerContent.kt"),
            // 2026-09-27 关系页改版：待办是调解邀请 / 双视角 / 解绑确认的唯一列表入口，
            // 入口只在抽屉——误删抽屉条目就等于用户永远到不了待办。
            Screen.TodoList.route to setOf("DrawerContent.kt"),
            // §8.8 纪念日：抽屉一级入口按契约移除后，必须有别的路可走
            // （使用指南 + AI 里的「附上一个纪念日」），否则这条保留能力就断在第一步。
            // 注意 RelationScreen 用的是**根路由字符串**而不是 Screen 常量，
            // 所以它命中不了这个表——真正兜住它的是上面那条「已注册必须有入口」。
            Screen.AnniversaryList.route to setOf("GuideScreen.kt"),
            Screen.MemoryCard.route to setOf("NavGraph.kt"),
        )

        /** 位置：从测试工作目录（模块目录）向上找到 settings.gradle.kts 所在的仓库根。 */
        fun androidRoot(): File {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("找不到 settings.gradle.kts：测试工作目录是 ${File(".").absolutePath}")
        }
    }

    /** 一条「已注册」记录：路由字符串 + 在哪个文件的注册头里出现。 */
    private data class Registration(val route: String, val inFiles: Set<String>)

    private val root = androidRoot()

    // ------------------------------------------------------------------ #
    // 主断言
    // ------------------------------------------------------------------ #

    @Test
    fun `已注册的页面都必须有用户入口_除非在豁免表里`() {
        val registrations = scanRegistrations()
        assertTrue(
            "没扫到任何注册路由，说明扫描逻辑失效了（真实代码有 57 条）",
            registrations.size > 40,
        )

        val gaps = registrations.keys
            .filter { entries(it).isEmpty() }
            .filterNot { it in HIDDEN_WITHOUT_ENTRY }
            .sorted()

        assertEquals(
            "以下页面已注册在导航图里，但全仓库没有任何入口引用：" +
                "用户永远到不了（§8.0 可达性红线）。" +
                "要么补上真实入口，要么在 HIDDEN_WITHOUT_ENTRY 里写明契约依据。",
            emptyList<String>(),
            gaps,
        )
    }

    @Test
    fun `豁免表只包含确实是隐藏对象的页面`() {
        val registrations = scanRegistrations()
        val unneeded = HIDDEN_WITHOUT_ENTRY.keys
            .filter { entries(it).isNotEmpty() }
            .sorted()
        assertEquals(
            "这些路由**已经有入口了**，却还挂在豁免表里——" +
                "豁免表一旦比现实更宽，下一次「入口被误删」就会被它悄悄掩盖。请从表里移除。",
            emptyList<String>(),
            unneeded,
        )
        HIDDEN_WITHOUT_ENTRY.keys.forEach {
            assertTrue("豁免条目必须是真实注册过的路由：$it", it in registrations)
        }
    }

    @Test
    fun `保留能力的入口期望表全部命中`() {
        REQUIRED_ENTRIES.forEach { (route, expectedFiles) ->
            val found = entries(route)
            assertTrue(
                "「$route」没有入口引用——这条保留链路断在第一步（能发现）上",
                found.isNotEmpty(),
            )
            // entries() 返回的是「文件名:行号」（便于定位），期望表只写文件名。
            val foundFiles = found.mapTo(mutableSetOf()) { it.substringBefore(':') }
            val missed = expectedFiles - foundFiles
            assertEquals(
                "「$route」的入口引用不在预期文件里（实际命中：${found.sorted()}）",
                emptySet<String>(),
                missed,
            )
        }
    }

    @Test
    fun `被壳托管的 tab 路由不出现在根导航图注册头里`() {
        val registrations = scanRegistrations()
        // 三个 tab 路由由 CoupleShell / SingleShell 的内层 NavHost 提供，
        // 根导航图没有它们的目的地，自然也没入口——
        // 这里显式记录，避免以后有人把它们当成「漏注册」或者贴上豁免表。
        val tabRoutes: List<String> = listOf(
            BottomTab.AiChat.route,
            BottomTab.Relation.route,
            BottomTab.SingleHome.route,
            BottomTab.Diary.route,
        )
        listOf(Screen.Home.route, Screen.Mailbox.route, Screen.AiChat.route).forEach { route ->
            assertTrue("$route 不该出现在根导航图注册头里", !registrations.containsKey(route))
        }
        tabRoutes.forEach { route ->
            assertTrue("$route 应当被壳注册", registrations.containsKey(route))
        }
    }

    // ------------------------------------------------------------------ #
    // 扫描
    // ------------------------------------------------------------------ #

    /**
     * 抠出每个 `composable(...)` / `navigation(...)` 的注册头（含嵌套括号），
     * 从里面读路由。必须按括号配对切，**不能**用「行结尾有无逗号」之类的启发式：
     * 注册头里常常嵌着 `arguments = listOf(navArgument(...))`。
     */
    private fun headerSpans(code: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var searchFrom = 0
        val opener = Regex("""\b(?:composable|navigation)\s*\(""")
        while (true) {
            val m = opener.find(code, searchFrom) ?: break
            var depth = 1
            var i = m.range.last + 1
            while (i < code.length && depth > 0) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            spans += m.range.first until i
            searchFrom = i
        }
        return spans
    }

    /** 去注释但保留字符串内容（注释里写着「[W1 隐藏] …入口」正是我们要区分的对象）。 */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    var j = i + 1
                    while (j < text.length) {
                        if (text[j] == '\\') {
                            j += 2
                            continue
                        }
                        if (text[j] == '"') {
                            j++
                            break
                        }
                        j++
                    }
                    sb.append(text, i, minOf(j, text.length))
                    i = j
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    val j = text.indexOf('\n', i)
                    i = if (j < 0) text.length else j
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val j = text.indexOf("*/", i + 2)
                    i = if (j < 0) text.length else j + 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun scanRegistrations(): Map<String, Registration> {
        val out = mutableMapOf<String, Registration>()
        sourceFiles().forEach { file ->
            val code = stripComments(file.readText())
            headerSpans(code).forEach { span ->
                val header = code.substring(span)
                routeStringsIn(header).forEach { route ->
                    val existing = out[route]
                    val files = (existing?.inFiles ?: emptySet()) + file.name
                    out[route] = Registration(route, files)
                }
            }
        }
        // 枚举里用到、但注册头里走的不是字面量的（例如 `route = Screen.X.route`）
        return out
    }

    /**
     * 注册头 / 入口语料里的**路由字符串**：字符串字面量（模板前缀截到 `$` 之前），
     * 以及 `Screen.X.route` / `BottomTab.X.route` 这类标识符引用解析出的路由。
     */
    private fun routeStringsIn(code: String): Set<String> {
        val routes = mutableSetOf<String>()
        Regex("""Screen\.(\w+)\.route""").findAll(code).forEach { m ->
            runCatching { Screen.valueOf(m.groupValues[1]).route }.getOrNull()?.let(routes::add)
        }
        Regex("""BottomTab\.(\w+)\.route""").findAll(code).forEach { m ->
            runCatching { BottomTab.valueOf(m.groupValues[1]).route }.getOrNull()?.let(routes::add)
        }
        Regex("""BottomTab\.(\w+)\b""").findAll(code).forEach { m ->
            runCatching { BottomTab.valueOf(m.groupValues[1]) }.getOrNull()?.let { routes.add(it.route) }
        }
        Regex("\"([^\"\\\\]*)\"").findAll(code).forEach { m ->
            routes += m.groupValues[1].substringBefore('$')
        }
        return routes
    }

    /** 全仓库（除声明文件）里对 [route] 的入口引用，格式 `文件名:行号`。 */
    private fun entries(route: String): Set<String> {
        val hits = mutableSetOf<String>()
        sourceFiles()
            .filterNot { it.name in DECLARATION_FILES }
            .forEach { file ->
                val code = stripComments(file.readText())
                val body = buildString {
                    var pos = 0
                    headerSpans(code).forEach { span ->
                        append(code, pos, span.first)
                        pos = span.last + 1
                    }
                    append(code, pos, code.length)
                }
                body.lines().forEachIndexed { index, line ->
                    val byLiteral = Regex("\"([^\"\\\\]*)\"").findAll(line).any { m ->
                        literalMatchesRoute(m.groupValues[1].substringBefore('$'), route)
                    }
                    val byScreenRef = Regex("""\bScreen\.(\w+)\b""").findAll(line).any { m ->
                        runCatching { Screen.valueOf(m.groupValues[1]).route }.getOrNull() == route
                    }
                    val byTabRef = Regex("""\bBottomTab\.(\w+)\b""").findAll(line).any { m ->
                        runCatching { BottomTab.valueOf(m.groupValues[1]) }.getOrNull()?.route == route
                    }
                    if (byLiteral || byScreenRef || byTabRef) hits += "${file.name}:${index + 1}"
                }
            }
        return hits
    }

    /**
     * 字面量是否是 [route] 的入口。
     *
     * 三种真实写法都要认：
     * - 裸路由：`navigate(Screen.X.route)` 之外的 `navigate("letter_list")`
     * - 查询参数：`"feedback_outcome?sessionId=$id"` → 字面量截到 `$` 之前
     * - 路径段：`"letter_detail/$letterId"`
     *
     * 带占位符的路由（`Screen.MemoryCard.route`）额外用「静态前缀」比对，
     * 因为调用点写的是 `"memory_card?targetType=$targetType&…"`，两者永远不相等。
     * **不做**裸前缀比对（`startsWith(route)`）：那样 `"letter_list_old"` 也会算命中。
     */
    private fun literalMatchesRoute(literal: String, route: String): Boolean {
        if (literal.isEmpty()) return false
        val placeholderAt = route.indexOf('{')
        if (placeholderAt > 0) {
            return literal.startsWith(route.substring(0, placeholderAt))
        }
        return literal == route ||
            literal.startsWith("$route?") ||
            literal.startsWith("$route/")
    }

    /** 4 个模块的 main 源码文件。 */
    private fun sourceFiles(): List<File> =
        MODULE_SOURCE_ROOTS.flatMap { rel ->
            File(root, rel).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
}
