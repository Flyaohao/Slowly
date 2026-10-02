package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSurfaceMuted
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * 带兜底的图片：加载中 / 加载失败都画成同一块「柔和的空图位」。
 *
 * 2026-10-01 新增。此前全项目直接用 `AsyncImage`，**没有任何 error 回调**：
 * 网络失败时 Coil 什么都不画，于是 56dp 的列表缩略图变成一个空洞、
 * 220dp 的详情大图变成一大片空白——用户看到的是「App 坏了」，
 * 而不只是「图没加载出来」。这与「不允许出现让用户觉得 App 坏了的体验」冲突。
 *
 * 为什么不灰块：纯灰占位在暖粉调版式里很扎眼，且同样像「没加载出来」。
 * 这里用语义色 `AppSurfaceMuted` + `AppTextTertiary` 的淡图图标，
 * 与卡片底色同族，形状跟随调用方的圆角（品牌要求圆润、排斥直角）。
 *
 * 失败时**不重试、不转圈**：失败就是失败，给一个安静的空图位即可；
 * 真正的重试入口由页面层的下拉刷新提供。
 *
 * @param model 图片地址（http(s) 绝对路径或本地 Uri）
 * @param contentDescription 无障碍描述；纯装饰图可传 null
 * @param shape 圆角形状，默认跟随品牌 md 圆角
 * @param iconSize 空图位里图标的尺寸，默认按容器较小边长的三分之一偏大一点
 */
@Composable
fun AppAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.md),
    contentScale: ContentScale = ContentScale.Crop,
    iconSize: Dp = 22.dp,
) {
    val empty: @Composable () -> Unit = { AppImageFallback(shape = shape, iconSize = iconSize) }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.clip(shape),
        loading = { empty() },
        error = { empty() },
    )
}

/**
 * 图片位的空态：淡底 + 居中图标。形状与外层一致，避免圆角处露出直角。
 *
 * 单独提出来是为了让 [AppAsyncImage] 的 loading / error 两个槽复用同一实现，
 * 也方便个别页面想单独定制时直接引用。
 */
@Composable
fun AppImageFallback(
    shape: Shape = RoundedCornerShape(AppRadius.md),
    iconSize: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(AppSurfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            tint = AppTextTertiary,
            modifier = Modifier.size(iconSize),
        )
    }
}
