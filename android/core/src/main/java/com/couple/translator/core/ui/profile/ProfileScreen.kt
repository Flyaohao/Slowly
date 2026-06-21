package com.couple.translator.core.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.components.TextInputField
import com.couple.translator.core.ui.theme.Accent
import com.couple.translator.core.ui.theme.Background
import com.couple.translator.core.ui.theme.BorderLight
import com.couple.translator.core.ui.theme.TextPrimary
import com.couple.translator.core.ui.theme.TextSecondary

private val genderOptions = listOf("男", "女", "其他", "不愿透露")

private val yearOptions = (1950..2010).toList().reversed()
private val monthOptions = (1..12).toList()
private val dayOptions = (1..31).toList()

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
            TopAppBar(
                title = { Text("个人资料") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleEditing() }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                AsyncImage(
                    model = uiState.avatarUrl.ifBlank { null },
                    contentDescription = "头像",
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )

                Spacer(modifier = Modifier.height(24.dp))

                TextInputField(
                    value = uiState.nickname,
                    onValueChange = viewModel::onNicknameChange,
                    label = "昵称",
                    enabled = uiState.isEditing,
                )

                Spacer(modifier = Modifier.height(12.dp))

                GenderDropdown(
                    selected = uiState.gender,
                    onSelect = viewModel::onGenderChange,
                    enabled = uiState.isEditing,
                )

                Spacer(modifier = Modifier.height(12.dp))

                BirthdayPicker(
                    birthday = uiState.birthday,
                    onSelect = viewModel::onBirthdayChange,
                    enabled = uiState.isEditing,
                )

                Spacer(modifier = Modifier.height(12.dp))

                TextInputField(
                    value = uiState.signature,
                    onValueChange = viewModel::onSignatureChange,
                    label = "签名",
                    placeholder = "写一句话介绍自己",
                    enabled = uiState.isEditing,
                    singleLine = false,
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (uiState.isEditing) {
                    PrimaryButton(
                        text = "保存",
                        onClick = { viewModel.save() },
                        isLoading = uiState.isLoading,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
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
                focusedBorderColor = Accent,
                unfocusedBorderColor = BorderLight,
                focusedLabelColor = Accent,
                cursorColor = TextPrimary,
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
            color = TextSecondary,
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
                focusedBorderColor = Accent,
                unfocusedBorderColor = BorderLight,
                focusedLabelColor = Accent,
                cursorColor = TextPrimary,
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
