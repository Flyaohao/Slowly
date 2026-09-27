package com.couple.translator.feature.single.diary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.SkeletonDetailPage
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary
import kotlinx.coroutines.delay

/** 自动保存间隔：30 秒（用户裁决）。 */
private const val AUTO_SAVE_INTERVAL_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
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
    var showExitDialog by remember { mutableStateOf(false) }

    // 如果传入了 diaryId，加载编辑模式
    LaunchedEffect(diaryId) {
        if (diaryId != null && diaryId > 0) {
            viewModel.loadForEdit(diaryId)
        }
        viewModel.checkDraft(diaryId?.takeIf { it > 0 })
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

    /**
     * 30 秒自动保存（只写本地草稿）。
     *
     * 挂在 composable 的作用域上而不是 ViewModel：**离开这个页面，循环随组合一起
     * 取消**——「退出该页面后自动保存逻辑就关闭」是用户明确要的语义，用
     * viewModelScope 做不到（ViewModel 会活到导航条目被销毁）。
     */
    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_SAVE_INTERVAL_MS)
            viewModel.autoSaveDraft()
        }
    }

    /** 退出前先问一句：有未提交的改动才拦，没改过就直接走。 */
    val requestExit: () -> Unit = {
        if (uiState.isDirty) {
            showExitDialog = true
        } else {
            viewModel.discardDraft()
            onNavigateBack()
        }
    }

    BackHandler { requestExit() }

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
            onBack = requestExit,
            title = if (uiState.isEditMode) "编辑观点" else "写观点",
            trailing = {
                // 保存按钮收进顶栏右上角，把纵向空间让给正文
                TextButton(
                    onClick = { viewModel.save() },
                    enabled = !uiState.isSaving,
                ) {
                    Text(
                        text = if (uiState.isSaving) "保存中" else "保存",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (uiState.isSaving) AppTextTertiary else AppAccent,
                    )
                }
            },
        )

        // 不再套 verticalScroll：内容框要向下撑满剩余高度，滚动容器会把 weight 夹死
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            // 标题（保留单行小标题，不删）
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("标题") },
                placeholder = { Text("给这个观点起个名字") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    focusedLabelColor = AppAccent,
                ),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // [W4.5 收缩] Markdown 格式工具栏**已随本次布局重排移除**：观点不是笔记
            // 软件，保留六个格式按钮既无用也挤占正文高度。原实现（工具栏 + 六个
            // wrap/insert 工具函数）一并删除，不再以注释形态残留。

            // 正文：占满剩余高度
            OutlinedTextField(
                value = contentFieldValue,
                onValueChange = { newValue ->
                    contentFieldValue = newValue
                    lastSyncedContent = newValue.text
                    viewModel.updateContent(newValue.text)
                },
                label = { Text("内容") },
                placeholder = { Text("写下你的看法、态度或判断，军师会读懂它") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent,
                    focusedLabelColor = AppAccent,
                ),
            )

            // 自动保存回执：让"防丢"这件事对用户可见，否则它只是我们内部的实现
            uiState.autoSavedAt?.let { at ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "已自动保存到本地草稿（$at）",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextTertiary,
                )
            }

            if (uiState.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppErrorRed,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // 退出校验：保存 / 不保存 / 继续写
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("要保存这条观点吗？") },
            text = { Text("这次写的内容还没有提交，不保存就会丢掉。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        viewModel.save()
                    },
                ) {
                    Text("保存", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("继续写", color = AppTextSecondary)
                }
                TextButton(
                    onClick = {
                        showExitDialog = false
                        viewModel.discardDraft()
                        onNavigateBack()
                    },
                ) {
                    Text("不保存", color = AppErrorRed)
                }
            },
        )
    }

    // 断点恢复：上次自动保存过、但还没提交的内容
    uiState.pendingDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { viewModel.discardDraft() },
            title = { Text("恢复上次写的内容？") },
            text = {
                Text(
                    if (draft.savedAt.isNotBlank()) {
                        "上次在 ${draft.savedAt} 自动保存过一段还没提交的内容。"
                    } else {
                        "上次有一段还没提交的内容。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreDraft() }) {
                    Text("恢复", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.discardDraft() }) {
                    Text("丢弃", color = AppTextSecondary)
                }
            },
        )
    }
}
