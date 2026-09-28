package com.couple.translator.core.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.couple.translator.core.network.toAbsoluteUrl
import com.couple.translator.core.ui.components.AppAccentButton
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppListItemDivider
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonBlock
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

private val genderOptions = listOf("男", "女", "其他", "不愿透露")

private val yearOptions = (1950..2010).toList().reversed()
private val monthOptions = (1..12).toList()
private val dayOptions = (1..31).toList()

/** 出生时辰 0-23（服务端只认小时；星盘上升位由它推算） */
private val hourOptions = (0..23).toList()

/** MBTI 16 型：代号 to 中文别称。代号进库，别称只做展示 */
private val mbtiOptions = listOf(
    "INTJ" to "建筑师", "INTP" to "逻辑学家", "ENTJ" to "指挥官", "ENTP" to "辩论家",
    "INFJ" to "提倡者", "INFP" to "调停者", "ENFJ" to "主人公", "ENFP" to "竞选者",
    "ISTJ" to "物流师", "ISFJ" to "守卫者", "ESTJ" to "总经理", "ESFJ" to "执政官",
    "ISTP" to "鉴赏家", "ISFP" to "探险家", "ESTP" to "企业家", "ESFP" to "表演者",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is ProfileUiEvent.SaveSuccess -> snackbarHostState.showSnackbar("保存成功")
                is ProfileUiEvent.ShowError -> {}
            }
        }
    }

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(message = uiState.error, onDismiss = { viewModel.clearError() })
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "个人资料",
                trailing = {
                    IconButton(onClick = { viewModel.toggleEditing() }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                SkeletonPageHeader()
                Spacer(modifier = Modifier.height(AppSpacing.section))
                repeat(4) {
                    SkeletonBlock(modifier = Modifier.fillMaxWidth().height(56.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.screenH)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                AsyncImage(
                    model = uiState.avatarUrl.ifBlank { null }.toAbsoluteUrl(),
                    contentDescription = "头像",
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Spacer(modifier = Modifier.height(20.dp))

                if (uiState.isEditing) {
                    EditProfileForm(uiState = uiState, viewModel = viewModel)
                } else {
                    ProfileViewContent(
                        nickname = uiState.nickname,
                        gender = uiState.gender,
                        birthday = uiState.birthday,
                        birthHour = uiState.birthHour,
                        birthPlace = uiState.birthPlace,
                        mbti = uiState.mbti,
                        signature = uiState.signature,
                    )
                }
            }
        }
        }
    }
}

/** 编辑态表单：只在意编辑模式下出现 */
@Composable
private fun EditProfileForm(
    uiState: ProfileUiState,
    viewModel: ProfileViewModel,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextInputField(
            value = uiState.nickname,
            onValueChange = viewModel::onNicknameChange,
            label = "昵称",
            enabled = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        GenderDropdown(
            selected = uiState.gender,
            onSelect = viewModel::onGenderChange,
            enabled = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        BirthdayPicker(
            birthday = uiState.birthday,
            onSelect = viewModel::onBirthdayChange,
            enabled = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        BirthHourDropdown(
            selected = uiState.birthHour,
            onSelect = viewModel::onBirthHourChange,
            enabled = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextInputField(
            value = uiState.birthPlace,
            onValueChange = viewModel::onBirthPlaceChange,
            label = "出生地",
            placeholder = "如：杭州（和时辰一起推星盘，可不填）",
            enabled = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        MbtiDropdown(
            selected = uiState.mbti,
            onSelect = viewModel::onMbtiChange,
            enabled = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextInputField(
            value = uiState.signature,
            onValueChange = viewModel::onSignatureChange,
            label = "签名",
            placeholder = "写一句话介绍自己",
            enabled = true,
            singleLine = false,
        )

        Spacer(modifier = Modifier.height(32.dp))

        AppAccentButton(
            text = "保存",
            onClick = { viewModel.save() },
            enabled = !uiState.isLoading,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** 查看态：昵称大字 + 签名 + 信息卡。不再是灰掉的输入框 */
@Composable
private fun ProfileViewContent(
    nickname: String,
    gender: String,
    birthday: String,
    birthHour: Int?,
    birthPlace: String,
    mbti: String,
    signature: String,
) {
    val birthdayText = birthday.split("-")
        .takeIf { it.size == 3 }
        ?.let { (y, m, d) -> "${y}年${m.trimStart('0')}月${d.trimStart('0')}日" }
        .orEmpty()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = nickname.ifBlank { "未设置昵称" },
            style = MaterialTheme.typography.headlineSmall,
            color = AppTextPrimary,
        )

        if (signature.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = signature,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    AppCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        ProfileInfoRow("性别", gender)
        AppListItemDivider()
        ProfileInfoRow("生日", birthdayText)
        AppListItemDivider()
        ProfileInfoRow("出生时辰", birthHour?.let { "%02d:00".format(it) }.orEmpty())
        AppListItemDivider()
        ProfileInfoRow("出生地", birthPlace)
        AppListItemDivider()
        val mbtiText = mbtiOptions.firstOrNull { it.first == mbti }
            ?.let { (code, name) -> "$code · $name" }
            .orEmpty()
        ProfileInfoRow("MBTI", mbtiText)
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "点右上角 ✎ 可编辑资料",
        style = MaterialTheme.typography.labelSmall,
        color = AppTextTertiary,
    )
    Spacer(modifier = Modifier.height(16.dp))
}

/** 信息卡里的一行：左标签右值。值为空显示「未填写」弱化处理 */
@Composable
private fun ProfileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
        )
        Spacer(modifier = Modifier.width(24.dp))
        Text(
            text = value.ifBlank { "未填写" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) AppTextTertiary else AppTextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenderDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = if (selected.isBlank()) "选择性别" else selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("性别") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppBorderLight,
                focusedLabelColor = AppAccent,
                cursorColor = AppTextPrimary,
            ),
            shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            genderOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

/** 出生时辰下拉（0-23 时）。未填就是未填——不默认正午，上升星座宁可不出 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthHourDropdown(
    selected: Int?,
    onSelect: (Int?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = selected?.let { "%02d:00".format(it) } ?: "选择出生时辰"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("出生时辰") },
            supportingText = { Text("精确到小时，用来推星盘；不填也能保存") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppBorderLight,
                focusedLabelColor = AppAccent,
                cursorColor = AppTextPrimary,
            ),
            shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hourOptions.forEach { hour ->
                DropdownMenuItem(
                    text = { Text("%02d:00".format(hour)) },
                    onClick = { onSelect(hour); expanded = false },
                )
            }
        }
    }
}

/** MBTI 十六型下拉。库里存代号（INTJ），菜单里带中文别称方便认 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MbtiDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = if (selected.isBlank()) "选择 MBTI 类型" else selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("MBTI") },
            supportingText = { Text("16 型自选；与问卷画像并列供军师参考") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppBorderLight,
                focusedLabelColor = AppAccent,
                cursorColor = AppTextPrimary,
            ),
            shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            mbtiOptions.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text("$code · $name") },
                    onClick = { onSelect(code); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPicker(
    birthday: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val parts = birthday.split("-")
    var year by remember { mutableStateOf(parts.getOrNull(0) ?: "") }
    var month by remember { mutableStateOf(parts.getOrNull(1)?.trimStart('0') ?: "") }
    var day by remember { mutableStateOf(parts.getOrNull(2)?.trimStart('0') ?: "") }

    fun emit() {
        if (year.isNotBlank() && month.isNotBlank() && day.isNotBlank()) {
            onSelect("$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "生日",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SimpleDropdown(
                selected = year.ifBlank { "年" },
                options = yearOptions.map { it.toString() },
                onSelect = { year = it; emit() },
                enabled = enabled,
                modifier = Modifier.weight(1.4f),
            )
            SimpleDropdown(
                selected = if (month.isBlank()) "月" else month,
                options = monthOptions.map { it.toString() },
                onSelect = { month = it; emit() },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            SimpleDropdown(
                selected = if (day.isBlank()) "日" else day,
                options = dayOptions.map { it.toString() },
                onSelect = { day = it; emit() },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            enabled = enabled,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppBorderLight,
                focusedLabelColor = AppAccent,
                cursorColor = AppTextPrimary,
            ),
            shape = MaterialTheme.shapes.medium,
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
