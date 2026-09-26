package com.couple.translator.feature.couple.couplebind

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.couple.translator.core.ui.theme.AppErrorRed

/**
 * 解绑二次确认对话框（契约 §2.6-2：明确后果 = 共享数据冻结、各自画像归个人、72h 后对方确认生效）。
 *
 * [CoupleBindScreen] 与 [CoupleInfoScreen] 各有一份逐字相同的后果说明，
 * 抽到此处共用，避免两处文案各自漂移（DRY）。
 */
private const val UNBIND_CONSEQUENCES_TEXT =
    "解绑后果，请确认你已了解：\n" +
        "1. 共享数据冻结——情侣空间的共同记录将停止更新并被冻结；\n" +
        "2. 各自画像归个人——你们各自的画像与记忆归个人所有，不再共享；\n" +
        "3. 72 小时冷静期——申请后进入 72 小时冷静期，期满由对方确认后才正式生效，期间任意一方可取消。\n\n" +
        "确定要申请解绑吗？"

/**
 * 两处解绑入口共用的确认对话框。
 *
 * @param onConfirm 「申请解绑」
 * @param onDismiss  取消 / 点击外部
 */
@Composable
fun UnbindConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认解绑") },
        text = { Text(UNBIND_CONSEQUENCES_TEXT) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("申请解绑", color = AppErrorRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
