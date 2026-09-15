package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.couple.translator.core.network.toAbsoluteUrl
import com.couple.translator.core.ui.theme.InterFontFamily

/**
 * 圆形头像。有图显示图，无图 / 加载中 / 加载失败都退化成一个字。
 *
 * 不用占位灰块：头像位一旦出现灰块，整页看起来就像"没加载出来"。
 *
 * @param ringColor 外圈颜色，用来在浅底卡片上把头像"托"起来；null 表示无圈
 */
@Composable
fun AvatarBubble(
    url: String?,
    nickname: String?,
    size: Dp,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fallbackText: String = "我",
    ringColor: Color? = null,
    ringWidth: Dp = 2.dp,
) {
    val absolute = url?.toAbsoluteUrl()
    val core = Modifier.size(size).clip(CircleShape).background(containerColor)

    if (ringColor == null) {
        AvatarCore(
            modifier = modifier.then(core),
            url = absolute,
            nickname = nickname,
            size = size,
            contentColor = contentColor,
            fallbackText = fallbackText,
        )
    } else {
        Box(
            modifier = modifier
                .size(size + ringWidth * 2)
                .clip(CircleShape)
                .background(ringColor)
                .padding(ringWidth),
            contentAlignment = Alignment.Center,
        ) {
            AvatarCore(
                modifier = core,
                url = absolute,
                nickname = nickname,
                size = size,
                contentColor = contentColor,
                fallbackText = fallbackText,
            )
        }
    }
}

@Composable
private fun AvatarCore(
    modifier: Modifier,
    url: String?,
    nickname: String?,
    size: Dp,
    contentColor: Color,
    fallbackText: String,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            InitialLetter(nickname, size, contentColor, fallbackText)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = nickname,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { InitialLetter(nickname, size, contentColor, fallbackText) },
                error = { InitialLetter(nickname, size, contentColor, fallbackText) },
            )
        }
    }
}

@Composable
private fun InitialLetter(
    nickname: String?,
    size: Dp,
    contentColor: Color,
    fallbackText: String,
) {
    Text(
        text = nickname?.trim()?.take(1)?.takeIf { it.isNotBlank() } ?: fallbackText,
        style = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.38f).sp,
        ),
        color = contentColor,
    )
}
