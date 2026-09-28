package com.couple.translator.core.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * 通用下拉刷新包装组件。
 *
 * @param isRefreshing 是否正在刷新（由 ViewModel 控制）
 * @param onRefresh 下拉刷新回调
 * @param modifier Modifier
 * @param content 页面内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()

    // 当 Material3 检测到下拉手势触发刷新时，调用 onRefresh
    LaunchedEffect(state) {
        snapshotFlow { state.isRefreshing }
            .collect { refreshing ->
                if (refreshing) {
                    onRefresh()
                }
            }
    }

    // 当 ViewModel 完成刷新（isRefreshing 变为 false）时，结束动画
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && state.isRefreshing) {
            state.endRefresh()
        }
    }

    // ── 白圈泄漏修复（2026-09-28 真机反馈）──────────────────────────────
    // material3 1.2.1 的 PullToRefreshContainer 永远渲染一个 48dp 白圆，
    // 靠 verticalOffset=0 藏在屏幕上沿外；但手势被打断（抽屉/键盘/系统边缘
    // 手势拦截，收不到松手事件）时 onPreFling 不回调，verticalOffset 残留 >0，
    // 白圆就半露在页面顶部不再收回（全 App 18 个下拉刷新页面都会中招）。
    //
    // 修法两层：
    // 1. 指示器只在「真的在刷新或位移 >0」时才渲染 —— 空闲态物理上不可能露出；
    // 2. 手势结束后 400ms 仍有残留位移且未在刷新（只可能来自被打断的手势）
    //    → endRefresh() 归零收回。
    // gestureActive 用整个包装 Box 的 pointer 事件跟踪：手指按住期间不干预，
    // 避免把用户正在进行的拖拽误判为残留。
    var gestureActive by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        snapshotFlow { Triple(state.verticalOffset, state.isRefreshing, gestureActive) }
            .collect { (offset, refreshing, active) ->
                if (!active && !refreshing && offset > 1f) {
                    delay(400)
                    if (!gestureActive && !state.isRefreshing && state.verticalOffset > 1f) {
                        state.endRefresh()
                    }
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    gestureActive = true
                    waitForUpOrCancellation()
                    gestureActive = false
                }
            }
            .nestedScroll(state.nestedScrollConnection),
    ) {
        content()

        PullToRefreshContainer(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter),
            indicator = { indicatorState ->
                if (indicatorState.isRefreshing || indicatorState.verticalOffset > 1f) {
                    PullToRefreshDefaults.Indicator(state = indicatorState)
                }
            },
        )
    }
}
