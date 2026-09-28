package com.couple.translator.core.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 统一触感反馈（全局 UI/UX 方案 G2，D5 拍板：只在关键动作触发）。
 *
 * 四档语义：
 * - [tick]    轻点：chip 切换、开关、tab
 * - [confirm] 成功：发送成功、投票/确认动作完成
 * - [warning] 警示：危险操作（结束调解）、军师忙
 * - [error]   失败：发送失败、加载失败
 *
 * 用法：`val haptics = rememberAppHaptics()`，在动作成功/失败的回调里调对应方法。
 * 总开关走 [HapticsStore]（默认开，设置页开关 P1 接入）——这里只做「怎么震」，
 * 不做「震不震」的判断，调用方在拿到 store 状态后自行短路。
 */
class AppHaptics(private val view: View) {

    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        },
    )

    fun warning() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun error() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    private fun perform(constant: Int) {
        // FLAG_IGNORE_GLOBAL_SETTING 兼容旧机型上用户关掉系统触感但期望 App 内仍有的场景；
        // API 30 起系统已按「触摸反馈强度」分档，此处保持默认即可
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    return remember(view) { AppHaptics(view) }
}
