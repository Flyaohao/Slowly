package com.couple.translator.core.ui.aiconfig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * AI 服务配置页（v5.0）：每个用户绑定自己的 api-key / model / base_url。
 *
 * 规则（设计文档 D 系列）：
 * - 强制配置（D4）：未配置时全 App AI 功能不可用，本页是唯一入口；
 * - 保存前强制测试（D10）：后端 PUT 内做「对话 + Embedding」双探测，
 *   测试不过返回 30011，原因透传到 [AiConfigUiState.error]；
 * - key 只进不出：列表里只显示打码值，新增 key 保存时才入库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: AiConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var newKeyInput by remember { mutableStateOf("") }

    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) snackbarHostState.showSnackbar(state.error)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) snackbarHostState.showSnackbar("已保存，AI 功能已就绪")
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "AI 服务配置")
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

            Text(
                text = "军师的每一次回答、每一份解读都调用你自己的模型服务。" +
                    "API Key 加密存储、不会展示给任何人（包括对方）。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ---- 服务商与端点 ----
            SectionTitle(text = "服务与模型")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ProviderSelector(
                        selected = state.providerType,
                        onSelect = viewModel::setProviderType,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextInputField(
                        value = state.baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        label = "Base URL",
                        placeholder = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextInputField(
                        value = state.modelName,
                        onValueChange = viewModel::setModelName,
                        label = "对话模型名称",
                        placeholder = "如 qwen-plus / deepseek-chat / claude-sonnet-4-5",
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Embedding ----
            SectionTitle(text = "Embedding（记忆检索用）")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextInputField(
                        value = state.embeddingModel,
                        onValueChange = viewModel::setEmbeddingModel,
                        label = "Embedding 模型",
                        placeholder = "如 text-embedding-v4（须输出 1024 维）",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextInputField(
                        value = state.embeddingBaseUrl,
                        onValueChange = viewModel::setEmbeddingBaseUrl,
                        label = "Embedding 端点（留空与上方一致）",
                        placeholder = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextInputField(
                        value = state.embeddingApiKey,
                        onValueChange = viewModel::setEmbeddingApiKey,
                        label = "Embedding 专用 Key（留空与聊天共用）",
                        isPassword = true,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- API Keys ----
            SectionTitle(text = "API Keys")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (state.keys.isEmpty() && state.newKeys.isEmpty()) {
                        Text(
                            text = "还没有添加 Key。至少需要一把才能保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                    state.keys.forEachIndexed { index, item ->
                        if (index > 0 || state.newKeys.isNotEmpty()) {
                            HorizontalDivider(
                                color = AppBorderLight,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        KeyRow(
                            masked = item.masked,
                            label = item.label,
                            enabled = item.enabled,
                            errorHint = item.lastErrorCode,
                            onToggle = { viewModel.toggleKey(item.id, it) },
                            onDelete = { viewModel.deleteKey(item.id) },
                        )
                    }
                    state.newKeys.forEachIndexed { index, key ->
                        HorizontalDivider(
                            color = AppBorderLight,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        KeyRow(
                            // 与顶部「不会展示给任何人」口径一致：未保存的 key 也不以明文示人，
                            // 只在本地展示打码形态（原文仅存于 ViewModel，保存时才提交）。
                            masked = maskNewKey(key),
                            label = "新增（保存时校验）",
                            enabled = true,
                            errorHint = "",
                            onToggle = {},
                            onDelete = { viewModel.removeNewKey(index) },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextInputField(
                            value = newKeyInput,
                            onValueChange = { newKeyInput = it },
                            label = "添加新 Key",
                            isPassword = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                viewModel.addNewKey(newKeyInput)
                                newKeyInput = ""
                            },
                            enabled = newKeyInput.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "添加",
                                tint = AppAccent,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- 限流 ----
            SectionTitle(text = "使用限制")
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .pressFeedback(onClick = { viewModel.setRateLimit(!state.enableRateLimit) })
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "参与请求限流",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppTextPrimary,
                        )
                        Text(
                            text = "关闭后不再限制你的调用频率（保留滥用兜底）",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextTertiary,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = state.enableRateLimit,
                        onCheckedChange = { viewModel.setRateLimit(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppSurface,
                            checkedTrackColor = AppAccent,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- 测试与保存 ----
            // 测试结果放在按钮下方：插在上方会把「测试连接/保存」顶下去，连续操作容易点空。
            AppAccentButton(
                text = if (state.isTesting) "正在测试…" else "测试连接",
                onClick = viewModel::testConnection,
                enabled = !state.isTesting && !state.isSaving,
            )
            Spacer(modifier = Modifier.height(12.dp))
            AppPrimaryButton(
                text = if (state.isSaving) "正在保存…" else "保存",
                onClick = viewModel::save,
                enabled = !state.isSaving && !state.isTesting,
            )

            if (state.testPassed.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "✓ ${state.testPassed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppAccent,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 本地打码未保存的新 key，格式与服务端一致（如 `sk-****1680`）。
 * 过短的 key 尾 4 位会暴露过多内容（≤8 位时尾 4 占一半以上），整体打码不回显。
 */
private fun maskNewKey(raw: String): String {
    val key = raw.trim()
    return if (key.length <= 8) "••••••••" else key.take(3) + "****" + key.takeLast(4)
}

/** 服务商单选（D7：OpenAI 兼容 / Anthropic 双协议）。 */
@Composable
private fun ProviderSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(
            selected = selected == "openai",
            onClick = { onSelect("openai") },
            label = { Text("OpenAI 兼容") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AppAccent.copy(alpha = 0.12f),
                selectedLabelColor = AppAccent,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = selected == "anthropic",
            onClick = { onSelect("anthropic") },
            label = { Text("Anthropic") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AppAccent.copy(alpha = 0.12f),
                selectedLabelColor = AppAccent,
            ),
        )
    }
    if (selected == "anthropic") {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Anthropic 协议没有 Embedding API，需单独填写 Embedding 端点。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextTertiary,
        )
    }
}

/** 一把 key 的展示行：打码值 + 启停 + 删除。 */
@Composable
private fun KeyRow(
    masked: String,
    label: String,
    enabled: Boolean,
    errorHint: String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Key,
            contentDescription = null,
            tint = AppTextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = masked,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary,
            )
            val sub = buildString {
                append(label)
                if (errorHint.isNotBlank()) append(" · 最近错误：$errorHint")
            }
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppSurface,
                checkedTrackColor = AppAccent,
            ),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除",
                tint = AppTextSecondary,
            )
        }
    }
}
