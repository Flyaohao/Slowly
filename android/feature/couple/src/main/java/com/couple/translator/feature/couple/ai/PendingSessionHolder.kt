package com.couple.translator.feature.couple.ai

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * P0-10B 改动三：会话列表 → 军师页 的跨导航图传参。
 *
 * 会话列表由**根 NavGraph** 渲染、军师页由 **CoupleShell** 渲染，两者在不同
 * 导航图上，无法用同一 NavBackStackEntry 的 savedStateHandle（且全仓本无
 * savedStateHandle 先例）。形态照 [com.couple.translator.core.service.AiStreamKeepAlive]
 * 单例 + deepLinkRoute「消费即清空」：
 *
 * 列表点一条 → [set]；军师页 LaunchedEffect → [consume] 取完立即置 null，
 * 避免同一意图被重复消费（切 tab 重组时不会把旧会话再灌一次）。
 *
 * 必须带上 [title]：`loadSession` 只拉消息与 sessionId，不同步标题——
 * 不传 title 会让状态条残留上一段会话的「正在继续 · xxx」（真机实测缺陷）。
 *
 * sessionId 不做任何本地持久化——续接判定永远问服务端。
 */
data class PendingSession(
    val sessionId: Long,
    val sceneKey: String,
    val title: String?,
    /** 补丁 A2：该会话是否已归档——状态条显示「已结束的对话」而非「正在继续」 */
    val archived: Boolean = false,
)

object PendingSessionHolder {

    private val _pending = MutableStateFlow<PendingSession?>(null)

    fun set(sessionId: Long, sceneKey: String, title: String?, archived: Boolean = false) {
        _pending.value = PendingSession(sessionId, sceneKey, title, archived)
    }

    /** 取出并清空；无待消费意图返回 null。 */
    fun consume(): PendingSession? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}
