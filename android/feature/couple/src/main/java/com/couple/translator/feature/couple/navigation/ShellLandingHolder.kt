package com.couple.translator.feature.couple.navigation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 「请求壳层落到指定页」的跨组件一次性信号（2026-09-29 新增）。
 *
 * 为什么需要它：`CoupleShell` 的一级页由 HorizontalPager 托管，`rememberPagerState`
 * 会随 NavBackStackEntry 保留状态。因此「关系管理页 → 点『进入我们的空间』」这条
 * 路径只靠 `navigate(Main)` 回到栈底的壳，**会停在上次停留的 tab**（用户切过军师页
 * 就回军师页），与按钮「进入我们的空间」的字面语义不符。
 *
 * 形态与 [com.couple.translator.feature.couple.ai.PendingSessionHolder] 一致：
 * 单例 + StateFlow +「消费即清空」，避免同一意图被切页重组时重复消费。
 *
 * 用法：发起方 [request]；`CoupleShell` 用 LaunchedEffect 监听并 [consume]，
 * 拿到即滑到该页。
 *
 * 2026-09-29 重构：一级页收敛为 [抽屉0][军师1][空间2]，原 Relation(3) 随关系页删除。
 */
enum class ShellPage(val index: Int) {
    /** 抽屉页（不占底栏） */
    Drawer(0),

    /** 军师（默认首屏） */
    AiChat(1),

    /** 我们的空间（顶替原关系 tab 位置） */
    Home(2),
}

object ShellLandingHolder {

    private val _pending = MutableStateFlow<ShellPage?>(null)

    /** 值变化即触发壳层落页；同一页连续请求两次不会重复消费（consume 已清空）。 */
    val pending: MutableStateFlow<ShellPage?> = _pending

    fun request(page: ShellPage) {
        _pending.value = page
    }

    /** 取出并清空；无待消费意图返回 null。 */
    fun consume(): ShellPage? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}
