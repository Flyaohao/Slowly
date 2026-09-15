package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSize
import com.couple.translator.core.ui.theme.AppSpacing

/**
 * 骨架屏预设。
 *
 * 页面加载态**一律用骨架屏**替代居中转圈 —— 转圈只说明"在等"，骨架屏说明"长什么样、马上填色"，
 * 而且数据到位时不会整页跳一下。
 *
 * 用法：
 * ```
 * if (uiState.isLoading) { SkeletonListPage(); return }
 * ```
 */

/** 顶栏占位：一个 30dp 圆 + 一条短线。高度跟 [AppSize.topBar] 对齐，避免加载完成时整体位移。 */
@Composable
fun SkeletonTopBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSize.topBar)
            .padding(horizontal = AppSpacing.screenH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(modifier = Modifier.size(30.dp), shape = CircleShape)
    }
}

/** 页面大标题占位。 */
@Composable
fun SkeletonPageHeader(
    modifier: Modifier = Modifier,
    titleWidth: Dp = 120.dp,
    showSubtitle: Boolean = true,
) {
    Column(modifier = modifier.padding(horizontal = AppSpacing.screenH)) {
        SkeletonBlock(modifier = Modifier.width(titleWidth).height(26.dp))
        if (showSubtitle) {
            Spacer(modifier = Modifier.height(10.dp))
            SkeletonBlock(modifier = Modifier.width(titleWidth + 56.dp).height(12.dp))
        }
    }
}

/** 一张卡片的占位：左侧图块 + 两行文字。 */
@Composable
fun SkeletonListRow(modifier: Modifier = Modifier, withLeading: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withLeading) {
            SkeletonBlock(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(AppRadius.md),
            )
            Spacer(modifier = Modifier.width(AppSpacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth(0.55f).height(14.dp))
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f).height(11.dp))
        }
    }
}

/** 卡片式列表占位。 */
@Composable
fun SkeletonListCard(
    modifier: Modifier = Modifier,
    rows: Int = 3,
    withLeading: Boolean = true,
) {
    AppCard(modifier = modifier.padding(horizontal = AppSpacing.screenH)) {
        repeat(rows) { index ->
            SkeletonListRow(withLeading = withLeading)
            if (index != rows - 1) AppListItemDivider()
        }
    }
}

/** 通用「列表页」加载态：顶栏 + 大标题 + 主按钮 + 一张列表卡。 */
@Composable
fun SkeletonListPage(
    modifier: Modifier = Modifier,
    cardRows: Int = 3,
    showButton: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        SkeletonTopBar()
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        SkeletonPageHeader()
        if (showButton) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH)
                    .padding(top = AppSpacing.section)
                    .height(AppSize.button),
                shape = RoundedCornerShape(AppRadius.pill),
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.section))
        SkeletonListCard(rows = cardRows)
    }
}

/** 纯列表页加载态（顶部有返回箭头的那种）。 */
@Composable
fun SkeletonPlainListPage(
    modifier: Modifier = Modifier,
    cardRows: Int = 4,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        Box(modifier = Modifier.height(AppSize.topBar).fillMaxWidth())
        SkeletonBlock(
            modifier = Modifier
                .padding(horizontal = AppSpacing.screenH)
                .width(100.dp)
                .height(22.dp),
        )
        Spacer(modifier = Modifier.height(AppSpacing.section))
        SkeletonListCard(rows = cardRows)
    }
}

/** 详情 / 长文页加载态：标题 + 若干长短不一的正文行。 */
@Composable
fun SkeletonDetailPage(
    modifier: Modifier = Modifier,
    paragraphLines: Int = 6,
) {
    val ratios = listOf(0.95f, 0.88f, 0.97f, 0.72f, 0.9f, 0.6f)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        Box(modifier = Modifier.height(AppSize.topBar).fillMaxWidth())
        SkeletonPageHeader(showSubtitle = false)
        Spacer(modifier = Modifier.height(AppSpacing.section))
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(paragraphLines) { index ->
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(ratios[index % ratios.size])
                        .height(13.dp),
                )
            }
        }
    }
}

/** 聊天页加载态：左右交替的气泡占位。 */
@Composable
fun SkeletonChatPage(modifier: Modifier = Modifier, bubbles: Int = 4) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        Box(modifier = Modifier.height(AppSize.topBar).fillMaxWidth())
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(bubbles) { index ->
                val fromMe = index % 2 == 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start,
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 3 == 0) 0.7f else 0.55f)
                            .height(if (index % 2 == 0) 58.dp else 44.dp),
                        shape = RoundedCornerShape(AppRadius.lg),
                    )
                }
            }
        }
    }
}
