package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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

    // ── 残留位移自愈（2026-09-28）────────────────────────────────────
    // 手势被抽屉/键盘/系统边缘手势打断时收不到松手事件，verticalOffset
    // 可能停在中间值。手势结束后 400ms 仍有残留位移且未在刷新 → endRefresh()
    // 归零收回。gestureActive 用整个包装 Box 的 pointer 事件跟踪：手指按住
    // 期间不干预，避免把用户正在进行的拖拽误判为残留。
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

    // 指示器可见性：只在「真的在刷新或有位移」时才组合进树——空闲态物理上
    // 不渲染任何东西。
    val indicatorVisible by remember {
        derivedStateOf { state.isRefreshing || state.verticalOffset > 1f }
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

        // ── 自绘指示器（2026-09-28，替代 PullToRefreshContainer）─────
        // m3 1.2.1 的 PullToRefreshContainer 自带一个【无条件渲染】的 40dp
        // 圆形背景（containerColor），靠 translationY = offset - height 藏在
        // 内容区上沿之外；但外层没有裁剪、且我们的布局上沿在顶栏下方，
        // 白圆就常驻悬在顶栏区域（真机截图实锤，18 个页面全中招）。
        //
        // 这里复刻同样的圆形容器 + 位移几何，但两道保险：
        // 1. 可见性条件渲染（空闲态不组合进树）；
        // 2. 整体 clipToBounds 进「顶栏以下的 120dp 窗口」，位移途中也
        //    不会探出内容区压到顶栏。窗口高度盖过下拉全程（阈值 80dp +
        //    圆 40dp）；无 pointer 修饰，不挡下层触摸。
        if (indicatorVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .clipToBounds(),
            ) {
                // 40dp = material3 内部 SpinnerContainerSize（internal 取不到，
                // BOM 已钉 1.2.1，跟随升级时需复核）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(40.dp)
                        .graphicsLayer {
                            translationY = state.verticalOffset - size.height
                        }
                        .shadow(elevation = 3.dp, shape = CircleShape, clip = true)
                        .background(
                            color = PullToRefreshDefaults.containerColor,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PullToRefreshDefaults.Indicator(
                        state = state,
                        color = PullToRefreshDefaults.contentColor,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
