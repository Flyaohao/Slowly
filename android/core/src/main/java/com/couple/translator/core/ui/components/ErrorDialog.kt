package com.couple.translator.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit,
    title: String = "出错了",
) {
    // 静默处理错误，不显示弹窗
    LaunchedEffect(message) {
        onDismiss()
    }
}
