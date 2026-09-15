package com.couple.translator.feature.single.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppPrimaryButton
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeDiaryScreen(
    onNavigateBack: () -> Unit,
    diaryId: Long? = null,
    viewModel: ComposeDiaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 使用 TextFieldValue 来跟踪光标/选区
    var contentFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    // 用于同步 ViewModel 状态到 TextFieldValue（仅在初始加载或外部变化时）
    var lastSyncedContent by remember { mutableStateOf("") }

    // 如果传入了 diaryId，加载编辑模式
    LaunchedEffect(diaryId) {
        if (diaryId != null && diaryId > 0) {
            viewModel.loadForEdit(diaryId)
        }
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    // 同步 ViewModel 的 content 到 TextFieldValue（初始加载编辑内容时）
    LaunchedEffect(uiState.content) {
        if (uiState.content != lastSyncedContent) {
            lastSyncedContent = uiState.content
            contentFieldValue = TextFieldValue(
                text = uiState.content,
                selection = TextRange(uiState.content.length),
            )
        }
    }

    if (uiState.isLoading) {
        SkeletonDetailPage()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        AppBackTopBar(
            onBack = onNavigateBack,
            title = if (uiState.isEditMode) "编辑日记" else "写日记",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 标题
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("标题") },
                placeholder = { Text("给日记起个名字") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    focusedLabelColor = AppAccent,
                ),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Markdown 格式工具栏
            MarkdownToolbar(
                onFormatAction = { action ->
                    val current = contentFieldValue
                    val result = applyMarkdownAction(current, action)
                    contentFieldValue = result
                    lastSyncedContent = result.text
                    viewModel.updateContent(result.text)
                },
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 内容
            OutlinedTextField(
                value = contentFieldValue,
                onValueChange = { newValue ->
                    contentFieldValue = newValue
                    lastSyncedContent = newValue.text
                    viewModel.updateContent(newValue.text)
                },
                label = { Text("内容") },
                placeholder = { Text("支持 Markdown 格式...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    focusedLabelColor = AppAccent,
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 心情选择
            Text(
                text = "今天的心情",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MOOD_OPTIONS.forEach { mood ->
                    val isSelected = uiState.mood == mood
                    AppCard(
                        onClick = { viewModel.updateMood(if (isSelected) null else mood) },
                        shape = RoundedCornerShape(AppRadius.pill),
                        containerColor = if (isSelected) AppAccent else AppSurface,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = mood,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) AppSurface else AppTextSecondary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 天气选择
            Text(
                text = "今天的天气",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WEATHER_OPTIONS.forEach { weather ->
                    val isSelected = uiState.weather == weather
                    AppCard(
                        onClick = { viewModel.updateWeather(if (isSelected) null else weather) },
                        shape = RoundedCornerShape(AppRadius.pill),
                        containerColor = if (isSelected) AppAccent else AppSurface,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = weather,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) AppSurface else AppTextSecondary,
                        )
                    }
                }
            }

            // 错误提示
            if (uiState.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppErrorRed,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 保存按钮
            AppPrimaryButton(
                text = when {
                    uiState.isSaving -> "保存中..."
                    uiState.isEditMode -> "更新日记"
                    else -> "保存日记"
                },
                onClick = viewModel::save,
                enabled = !uiState.isSaving,
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ==================== Markdown 格式工具栏 ====================

/** Markdown 格式操作类型 */
enum class MarkdownAction {
    BOLD,
    ITALIC,
    H1,
    H2,
    LIST,
    LINK,
}

@Composable
private fun MarkdownToolbar(
    onFormatAction: (MarkdownAction) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = AppSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MarkdownToolbarButton(label = "B", action = MarkdownAction.BOLD, onFormatAction = onFormatAction)
            MarkdownToolbarButton(label = "I", action = MarkdownAction.ITALIC, onFormatAction = onFormatAction)
            MarkdownToolbarButton(label = "H1", action = MarkdownAction.H1, onFormatAction = onFormatAction)
            MarkdownToolbarButton(label = "H2", action = MarkdownAction.H2, onFormatAction = onFormatAction)
            MarkdownToolbarButton(label = "•", action = MarkdownAction.LIST, onFormatAction = onFormatAction)
            MarkdownToolbarButton(label = "🔗", action = MarkdownAction.LINK, onFormatAction = onFormatAction)
        }
    }
}

@Composable
private fun MarkdownToolbarButton(
    label: String,
    action: MarkdownAction,
    onFormatAction: (MarkdownAction) -> Unit,
) {
    TextButton(
        onClick = { onFormatAction(action) },
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = AppTextPrimary,
        )
    }
}

// ==================== Markdown 格式化逻辑 ====================

/**
 * 对 TextFieldValue 应用 Markdown 格式操作。
 * 根据操作类型修改文本并更新光标/选区。
 */
private fun applyMarkdownAction(
    fieldValue: TextFieldValue,
    action: MarkdownAction,
): TextFieldValue {
    val text = fieldValue.text
    val selection = fieldValue.selection

    return when (action) {
        MarkdownAction.BOLD -> wrapSelection(text, selection, "**")
        MarkdownAction.ITALIC -> wrapSelection(text, selection, "*")
        MarkdownAction.H1 -> insertAtLineStart(text, selection, "# ")
        MarkdownAction.H2 -> insertAtLineStart(text, selection, "## ")
        MarkdownAction.LIST -> insertAtLineStart(text, selection, "- ")
        MarkdownAction.LINK -> insertLink(text, selection)
    }
}

/**
 * 用指定的 marker 包裹选中的文本。
 * 如果没有选中文本，则包裹并在光标处插入 marker。
 */
private fun wrapSelection(
    text: String,
    selection: TextRange,
    marker: String,
): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val selectedText = text.substring(start, end)
    val before = text.substring(0, start)
    val after = text.substring(end, text.length)

    if (start == end) {
        // 没有选中文本，插入 marker 对，光标放在中间
        val newText = "$before$marker$marker$after"
        val newCursorPos = start + marker.length
        return TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos),
        )
    }

    // 有选中文本，用 marker 包裹
    val newText = "$before$marker$selectedText$marker$after"
    val newStart = start + marker.length
    val newEnd = newStart + selectedText.length
    return TextFieldValue(
        text = newText,
        selection = TextRange(newStart, newEnd),
    )
}

/**
 * 在当前行的开头插入指定的前缀（如 "# "、"## "、"- "）。
 */
private fun insertAtLineStart(
    text: String,
    selection: TextRange,
    prefix: String,
): TextFieldValue {
    val cursorPos = selection.min
    // 找到当前行的起始位置
    val lineStart = text.lastIndexOf('\n', cursorPos - 1) + 1

    val newText = text.substring(0, lineStart) + prefix + text.substring(lineStart)
    val newCursorPos = cursorPos + prefix.length

    return TextFieldValue(
        text = newText,
        selection = TextRange(newCursorPos),
    )
}

/**
 * 在光标位置插入 Markdown 链接模板 [text](url)。
 * 如果有选中文本，将其作为 link text。
 */
private fun insertLink(
    text: String,
    selection: TextRange,
): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val selectedText = text.substring(start, end)

    val linkTemplate = if (selectedText.isNotEmpty()) {
        "[$selectedText](url)"
    } else {
        "[text](url)"
    }

    val newText = text.substring(0, start) + linkTemplate + text.substring(end)

    // 选中 "url" 部分方便用户替换
    val urlStart = if (selectedText.isNotEmpty()) {
        start + selectedText.length + 2 // after "[selectedText]("
    } else {
        start + 6 // after "[text]("
    }
    val urlEnd = urlStart + 3 // "url".length

    return TextFieldValue(
        text = newText,
        selection = TextRange(urlStart, urlEnd),
    )
}
