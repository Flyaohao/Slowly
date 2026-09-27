package com.couple.translator.feature.couple.relation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.repository.ObservationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 军师观察卡 VM（V1 聚合版，《军师主动观察》设计文档 2026-09-28）。
 *
 * **作用域 = Activity**（CoupleShell 与 RelationScreen 都用
 * `hiltViewModel(activity)` 取同一个实例）：观察的「未读」是壳层角标
 * （F-5 合计口径）和关系页卡片（三态）共用的同一份状态，ack 之后角标
 * 必须立刻清零，分属两个 NavBackStackEntry 的默认 hiltViewModel() 会拿到
 * 两个互不知情的实例。
 *
 * 决策⑥（已拍板）：已读走服务端 ack，角标跨设备。前端 F-5 建议「开页
 * ack 后清零」——[load] 拿到 has_new=true 就立刻 ack；但卡片高亮
 * （isNewForCard）保留到下次进页，否则刚打开就看到 NEW 闪一下变灰。
 *
 * 降级语义沿用整改 §8.4：读不到 ≠ 没有。请求失败时维持上一次的内容
 * （可能为空），不报错、不清空——观察卡是关系页第一内容位，网络抖动
 * 不该让它凭空消失。
 */
data class ObservationUiState(
    /** 首次请求是否成功过。false 时不渲染观察区块（避免把失败装成冷启动）。 */
    val loaded: Boolean = false,
    /** null = 冷启动：服务端没有可拼装素材，渲染引导语。 */
    val content: String? = null,
    val observedAt: String? = null,
    /** 引用来源标题（F-3：MVP 只展示文字，不跳转）。 */
    val citationTitle: String? = null,
    /** 本次进页的「有新观察」态（高亮 + NEW），ack 后保留，下次 load 消失。 */
    val isNewForCard: Boolean = false,
    /** 服务端未读视角，ack 成功即清零 → 壳层角标数据源。 */
    val isNewForBadge: Boolean = false,
)

@HiltViewModel
class ObservationViewModel @Inject constructor(
    private val repository: ObservationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ObservationUiState())
    val uiState: StateFlow<ObservationUiState> = _uiState.asStateFlow()

    /**
     * 拉取一次观察。幂等：RelationScreen 每次进入组合都会调（返回本页
     * 刷新），服务端 has_new=false 时三态自然回落到安静态。
     */
    fun load() {
        viewModelScope.launch {
            repository.getObservation().onSuccess { data ->
                if (data == null) return@onSuccess
                _uiState.value = ObservationUiState(
                    loaded = true,
                    content = data.content,
                    observedAt = data.observed_at,
                    citationTitle = data.citation?.title,
                    isNewForCard = data.has_new,
                    isNewForBadge = data.has_new,
                )
                if (data.has_new && data.signature != null) {
                    repository.ack(data.signature).onSuccess {
                        _uiState.update { it.copy(isNewForBadge = false) }
                    }
                }
            }.onFailure {
                // 观察卡独立降级：维持现状，不整页报错
            }
        }
    }
}
