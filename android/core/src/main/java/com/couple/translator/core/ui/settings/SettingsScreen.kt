package com.couple.translator.core.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.couple.translator.core.notification.AppNotifications
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import com.couple.translator.core.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onLogout: () -> Unit,
    isCoupleMode: Boolean = true,
    themeMode: ThemeMode = ThemeMode.DEFAULT,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    notificationPref: NotificationPrefUiState = NotificationPrefUiState(),
    onEmailNotifyChange: (Boolean) -> Unit = {},
    onNotificationPrefErrorShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showAbout by remember { mutableStateOf(false) }

    // 版本号直接问系统要，不在界面上硬编码——写死的版本号迟早会和
    // build.gradle 里的 versionName 对不上，那是最容易露怯的一处细节。
    val versionName = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    // 系统通知权限状态。用户的开关在系统设置里，随时可能被改，
    // 所以每次回到这个页面都重新读一次，不缓存。
    var systemNotifyAllowed by remember { mutableStateOf(AppNotifications.canPost(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemNotifyAllowed = AppNotifications.canPost(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 保存失败必须让用户看见：开关不做乐观更新，界面会停回原值，
    // 如果不提示，用户会以为"点了没反应"。
    LaunchedEffect(notificationPref.error) {
        if (notificationPref.error.isNotBlank()) {
            snackbarHostState.showSnackbar(notificationPref.error)
            onNotificationPrefErrorShown()
        }
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "设置",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 账号设置
            SectionTitle(text = "账号")
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column {
                    SettingsItem(
                        icon = Icons.Outlined.Person,
                        title = "个人信息",
                        subtitle = "编辑昵称、性别、生日等",
                        onClick = onNavigateToProfile,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 外观设置 —— 情侣 / 单身模式都会显示（主题跟使用模式无关）
            SectionTitle(text = "外观")
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column {
                    ThemeMode.values().forEachIndexed { index, mode ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = AppBorderLight,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        ThemeOptionItem(
                            icon = mode.icon,
                            title = mode.label,
                            subtitle = mode.hint,
                            selected = mode == themeMode,
                            onSelect = { onThemeModeChange(mode) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 通知设置 - 仅情侣模式（通知都是围绕伴侣互动的：收信 / 调解邀请 / 解绑请求）
            if (isCoupleMode) {
                SectionTitle(text = "通知")
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column {
                        SwitchItem(
                            icon = Icons.Outlined.MarkEmailRead,
                            title = "邮件通知",
                            subtitle = emailNotifySubtitle(notificationPref),
                            checked = notificationPref.emailNotifyEnabled,
                            enabled = notificationPref.emailReady && !notificationPref.isLoading,
                            onCheckedChange = onEmailNotifyChange,
                        )
                        HorizontalDivider(color = AppBorderLight, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsItem(
                            icon = Icons.Outlined.Notifications,
                            title = "系统通知栏",
                            subtitle = if (systemNotifyAllowed) {
                                "已开启，收到信时会弹提醒"
                            } else {
                                "未开启，点按前往系统设置里打开"
                            },
                            onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            },
                        )
                    }
                }

                Text(
                    text = "邮件通知是「App 被关掉也能收到提醒」的唯一通道——" +
                        "提醒只告诉你有人写了信，不包含信件内容，内容仍然只能在 App 内查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            // 关于
            SectionTitle(text = "关于")
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column {
                    SettingsItem(
                        icon = Icons.Outlined.Info,
                        title = "关于应用",
                        subtitle = "版本 $versionName",
                        onClick = { showAbout = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 退出登录
            AppCard(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text(
                    text = "退出登录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppAccent,
                    modifier = Modifier
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            },
            title = { Text("关于应用") },
            text = {
                Column {
                    Text("Slowly慢慢说 · 版本 $versionName")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "已知边界（如实说明）：\n" +
                            "· 未接入第三方推送，App 被系统杀掉后收不到即时通知，" +
                            "只能靠邮件提醒（需在设置里打开）\n" +
                            "· 未启用 HTTPS，仅作个人使用\n" +
                            "· 无 CI，测试脚本手动执行",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }
}

/** 「邮件通知」这一行的说明文案：把"能不能用"和"发到哪儿"讲清楚 */
private fun emailNotifySubtitle(pref: NotificationPrefUiState): String = when {
    pref.loadFailed -> "读取失败，重新进入此页可重试"
    !pref.emailReady -> "服务端暂未配置发信，此开关不可用"
    pref.email.isBlank() -> "开启后，重要事件会发到你的注册邮箱"
    else -> "开启后，重要事件会发到 ${pref.email}"
}

/** 带开关的设置行：左侧图标 + 文案，右侧 Switch */
@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .pressFeedback(enabled = enabled, onClick = { onCheckedChange(!checked) })
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTextSecondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) AppTextPrimary else AppTextTertiary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppSurface,
                checkedTrackColor = AppAccent,
            ),
        )
    }
}

/** 「外观」三个选项各自的图标 */
private val ThemeMode.icon: ImageVector
    get() = when (this) {
        ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Outlined.LightMode
        ThemeMode.DARK -> Icons.Outlined.DarkMode
    }

/** 「外观」三个选项各自的说明文案 */
private val ThemeMode.hint: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随手机的深色模式设置"
        ThemeMode.LIGHT -> "界面始终使用浅色外观"
        ThemeMode.DARK -> "界面始终使用深色外观"
    }

/** 主题单选项：整行可点，右侧 RadioButton 表示当前选中 */
@Composable
private fun ThemeOptionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .pressFeedback(onClick = onSelect)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AppAccent else AppTextSecondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = AppAccent,
                unselectedColor = AppBorderLight,
            ),
        )
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTextSecondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }
        }
    }
}
