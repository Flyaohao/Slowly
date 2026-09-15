package com.couple.translator.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 设计尺寸令牌。
 *
 * 页面不要再散写 dp 字面量 —— 统一从这里取，保证「同一个层级的元素到哪都一样」。
 * 只放跨页面复用的阶，一次性微调（某张卡自己的 padding）仍可直接写 dp。
 */
object AppRadius {
    /** 小徽标、图标底 */
    val xs = 6.dp

    /** 标签、小色块 */
    val sm = 10.dp

    /** 列表卡、输入框 */
    val md = 14.dp

    /** 快捷入口卡 */
    val lg = 16.dp

    /** 主视觉卡片 */
    val xl = 22.dp

    /** 胶囊 */
    val pill = 999.dp
}

object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp

    /** 页面左右安全边距 */
    val screenH = 20.dp

    /** 区块之间 */
    val section = 22.dp

    /** 大区块之间 */
    val block = 32.dp
}

object AppSize {
    /** 主按钮高度 */
    val button = 50.dp

    /** 触摸热区下限 */
    val touch = 48.dp

    /** 底部导航胶囊内单项高度 */
    val tabItem = 46.dp

    /** 顶栏高度。取 48dp 而非 46dp，让「叠头像 → 抽屉」这个唯一入口正好压住最小触摸热区 */
    val topBar = 48.dp
}
