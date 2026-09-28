package com.couple.translator.core.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.ProfileDimensionLabels
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppLinkText
import com.couple.translator.core.ui.components.AppTag
import com.couple.translator.core.ui.components.AppTagTone
import com.couple.translator.core.ui.components.AppPageHeader
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * 画像历史版本：看 / 比 / 撤回 / 删。
 *
 * 操作对象一律是**整个画像版本**，没有「改某一维度」的入口——维度分是问卷与
 * 用户观点共同作用的产物，允许直接改一格，之后任何解释都无从谈起。
 *
 * 撤回为什么不覆盖当前版本：服务端把目标版本**再派生一次**成新版本。这样
 * 「撤回」这个动作本身也留在历史里，用户撤错了可以再撤回来。
 */
@Composable
fun ProfileVersionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileVersionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var pendingDelete by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        containerColor = AppBackground,
        topBar = { AppBackTopBar(onBack = onNavigateBack, title = "画像历史版本") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AppPageHeader(
                title = "画像历史版本",
                subtitle = "每一次变化都是一个版本。撤回本身也会留成新版本，随时能再撤回来。",
            )
            Spacer(modifier = Modifier.height(AppSpacing.lg))

            uiState.message?.let { msg ->
                InlineBanner(text = msg, color = AppAccent, onDismiss = { viewModel.dismissMessage() })
            }
            uiState.error?.let { msg ->
                InlineBanner(text = msg, color = AppErrorRed, onDismiss = { viewModel.dismissError() })
            }

            when {
                uiState.isLoading -> repeat(3) {
                    AppCard(
                        modifier = Modifier.padding(horizontal = AppSpacing.screenH, vertical = 5.dp),
                    ) {
                        Text(
                            text = "载入中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextTertiary,
                        )
                    }
                }

                uiState.versions.isEmpty() -> AppCard(
                    modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                ) {
                    Text(
                        text = "还没有历史版本",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTextPrimary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "完成一次问卷，或让军师用观点补充过画像之后，这里会出现记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }

                else -> {
                    val currentId = uiState.versions.first().id
                    uiState.versions.forEach { v ->
                        VersionCard(
                            version = v,
                            isCurrent = v.id == currentId,
                            expanded = uiState.expandedId == v.id,
                            diff = if (uiState.expandedId == v.id) uiState.diff else null,
                            isDiffLoading = uiState.isDiffLoading && uiState.expandedId == v.id,
                            isActing = uiState.isActing,
                            onToggle = { viewModel.toggleExpand(v.id) },
                            onRestore = { viewModel.restore(v.id) },
                            onDelete = { pendingDelete = v.id },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个版本") },
            text = { Text("删除后无法恢复。当前生效的版本删不掉，被关系画像用着的版本也删不掉。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(id)
                }) {
                    Text("删除", color = AppErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun VersionCard(
    version: ProfileDto.ProfileVersionResponse,
    isCurrent: Boolean,
    expanded: Boolean,
    diff: ProfileDto.VersionDiffResponse?,
    isDiffLoading: Boolean,
    isActing: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(modifier = Modifier.padding(horizontal = AppSpacing.screenH, vertical = 5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "v${version.version}",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    // 去AI味 P-3f：来源/当前从 accent 小灰字升级为 AppTag 徽章
                    AppTag(text = version.originLabel, tone = AppTagTone.Accent)
                    if (isCurrent) {
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        AppTag(text = "当前", tone = AppTagTone.Success)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = version.createdAt?.take(16) ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
                version.originNote?.let { note ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }
            }
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起" else "对比", color = AppAccent)
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            when {
                isDiffLoading -> Text(
                    text = "正在对比…",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )

                diff == null -> Text(
                    text = "没能取到对比结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )

                diff.items.isEmpty() -> Text(
                    text = if (isCurrent) "这就是当前画像。" else "这个版本与当前画像没有差别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                )

                else -> {
                    Text(
                        text = "与当前画像的差别",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    diff.items.forEach { item ->
                        // 去AI味 P-3f：去掉「· label：from → to」的单色拼接，
                        // 改为「维度名左、分数变化右」的两端对齐行，变化量一眼可读
                        val label = item.label.ifBlank {
                            ProfileDimensionLabels.of(item.dimensionKey)
                        }
                        val from = item.baseScore?.let { fmt(it) } ?: "—"
                        val to = item.targetScore?.let { fmt(it) } ?: "—"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "$from → $to",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = AppAccent,
                            )
                        }
                    }
                }
            }

            if (!isCurrent) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isActing) {
                        AppLinkText(label = "撤回这个版本", onClick = onRestore)
                    } else {
                        Text(
                            text = "处理中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    AppLinkText(label = "删除", onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun InlineBanner(text: String, color: Color, onDismiss: () -> Unit) {
    Surface(
        color = color.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .padding(bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("知道了", color = color)
            }
        }
    }
}

/** 分数显示：整数不带小数点（82 而不是 82.0）。 */
private fun fmt(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
