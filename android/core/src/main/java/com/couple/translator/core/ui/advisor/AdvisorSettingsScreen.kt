package com.couple.translator.core.ui.advisor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.SectionTitle
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

private data class OptionSpec(val value: String, val label: String, val description: String)

private val voiceStyleOptions = listOf(
    OptionSpec("gentle", "温柔", "轻声细语，先接住情绪"),
    OptionSpec("calm", "冷静", "就事论事，稳稳拆问题"),
    OptionSpec("direct", "直接", "有话直说，不绕弯子"),
    OptionSpec("cute", "活泼阳光", "轻松跳脱，带点俏皮"),
    OptionSpec("mature", "成熟", "克制稳重，点到为止"),
)

private val detailLevelOptions = listOf(
    OptionSpec("brief", "简短", "一两句话给结论"),
    OptionSpec("standard", "标准", "结论 + 必要的展开"),
    OptionSpec("detailed", "详细", "完整推导与例子"),
)

private val proactivityOptions = listOf(
    OptionSpec("passive", "被动", "只回答你问的"),
    OptionSpec("moderate", "适度", "关键处主动提醒"),
    OptionSpec("active", "主动", "看到问题就开口"),
)

/**
 * 军师设置页（契约 §3.3，W4.4）。
 * GET/PUT `/api/v1/advisor/settings` 同构；抽屉「AI 形象 → 军师设置」入口指向本页，
 * 旧 avatar_customize 路由与页面保留（隐藏 ≠ 删除）。
 */
@Composable
fun AdvisorSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AdvisorSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(onBack = onNavigateBack, title = "军师设置")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (uiState.loadFailed) {
                Text(
                    text = "暂时读不到已保存的设置，以下为默认值。保存后会写入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                    modifier = Modifier.padding(horizontal = AppSpacing.screenH, vertical = 8.dp),
                )
            }

            // ---------- 称呼 ----------
            SectionTitle("称呼")
            TextInputField(
                value = settings.addressName,
                onValueChange = viewModel::setAddressName,
                label = "军师怎么称呼你",
                placeholder = "例如：宝宝、阿哲",
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            )

            // ---------- 语气 ----------
            SectionTitle("语气")
            OptionGroup(
                options = voiceStyleOptions,
                selected = settings.voiceStyle,
                onSelect = viewModel::setVoiceStyle,
            )

            // ---------- 详细程度 ----------
            SectionTitle("回答详细程度")
            OptionGroup(
                options = detailLevelOptions,
                selected = settings.detailLevel,
                onSelect = viewModel::setDetailLevel,
            )

            // ---------- 主动程度 ----------
            SectionTitle("主动程度")
            OptionGroup(
                options = proactivityOptions,
                selected = settings.proactivity,
                onSelect = viewModel::setProactivity,
            )

            // ---------- 判断依据 ----------
            SectionTitle("判断依据")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppSurface)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "显示判断依据",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = AppTextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "建议后附上画像 / 记忆等来源，让你知道它为什么这么判断",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }
                Switch(
                    checked = settings.showEvidence,
                    onCheckedChange = viewModel::setShowEvidence,
                )
            }

            // ---------- 保存 ----------
            Spacer(modifier = Modifier.height(AppSpacing.block))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                if (uiState.error.isNotBlank()) {
                    Text(
                        text = uiState.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppErrorRed,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (uiState.saved) {
                    Text(
                        text = "已保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppAccent,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                AppPrimaryButton(
                    text = if (uiState.isSaving) "保存中…" else "保存",
                    onClick = viewModel::save,
                    enabled = !uiState.isSaving && !uiState.isLoading,
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.block))
        }
    }
}

@Composable
private fun OptionGroup(
    options: List<OptionSpec>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH),
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
            val isSelected = option.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) AppAccentLight else AppSurface)
                    .clickable { onSelect(option.value) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = AppTextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                }
                if (isSelected) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppAccent,
                    )
                }
            }
        }
    }
}
