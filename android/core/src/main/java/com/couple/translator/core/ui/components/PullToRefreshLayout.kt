package com.couple.translator.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(state.nestedScrollConnection),
    ) {
        content()

        PullToRefreshContainer(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
