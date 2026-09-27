package com.couple.translator.feature.single.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.repository.DraftStore
import com.couple.translator.feature.single.data.model.DiaryDto
import com.couple.translator.feature.single.network.SingleApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComposeDiaryUiState(
    val title: String = "",
    val content: String = "",
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isLoading: Boolean = false,
    val isEditMode: Boolean = false,
    val error: String? = null,
    /** 本地有一份与当前模式匹配的未提交草稿，等用户决定恢复还是丢掉。 */
    val pendingDraft: DraftStore.Draft? = null,
    /** 最近一次自动保存时间（HH:mm）；null = 本次进来还没触发过自动保存。 */
    val autoSavedAt: String? = null,
    /** 进入页面时的原始内容（新建模式为空）。用来判断「有没有改动过」。 */
    val originalTitle: String = "",
    val originalContent: String = "",
) {
    /** 有没有值得挽留的内容。 */
    val hasContent: Boolean get() = title.isNotBlank() || content.isNotBlank()

    /**
     * 有没有未提交的改动。退出时只对「改过」的内容弹窗——
     * 点进编辑页又原样退出还要被问一句「要不要保存」，是纯打扰。
     */
    val isDirty: Boolean
        get() = hasContent && (title != originalTitle || content != originalContent)
}

@HiltViewModel
class ComposeDiaryViewModel @Inject constructor(
    private val apiService: SingleApiService,
    private val draftStore: DraftStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeDiaryUiState())
    val uiState: StateFlow<ComposeDiaryUiState> = _uiState.asStateFlow()

    private var editingDiaryId: Long? = null

    /** 加载已有观点进入编辑模式。 */
    fun loadForEdit(diaryId: Long) {
        if (editingDiaryId != null) return // 避免重复加载
        editingDiaryId = diaryId
        _uiState.update { it.copy(isLoading = true, isEditMode = true) }
        viewModelScope.launch {
            try {
                val response = apiService.getDiary(diaryId)
                val diary = response.data
                if (response.isSuccess && diary != null) {
                    _uiState.update {
                        it.copy(
                            title = diary.title,
                            content = diary.content,
                            originalTitle = diary.title,
                            originalContent = diary.content,
                            isLoading = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    // ============ 本地草稿 ============

    /**
     * 进入页面时检查草稿。只有草稿归属与当前模式一致才提示——
     * 在「编辑观点 7」里恢复一条「新建」的草稿，等于把两篇内容搅在一起。
     */
    fun checkDraft(currentEditId: Long?) {
        viewModelScope.launch {
            val draft = draftStore.read() ?: return@launch
            if (draft.editId != currentEditId) return@launch
            // 编辑模式下内容已经和草稿一模一样时不必打扰用户
            val state = _uiState.value
            if (state.title == draft.title && state.content == draft.content) return@launch
            _uiState.update { it.copy(pendingDraft = draft) }
        }
    }

    fun restoreDraft() {
        val draft = _uiState.value.pendingDraft ?: return
        _uiState.update {
            it.copy(
                title = draft.title,
                content = draft.content,
                autoSavedAt = draft.savedAt.ifBlank { null },
                pendingDraft = null,
            )
        }
    }

    /** 丢弃草稿。退出弹窗选「不保存」、或用户主动放弃恢复时调用。 */
    fun discardDraft() {
        _uiState.update { it.copy(pendingDraft = null) }
        viewModelScope.launch { draftStore.clear() }
    }

    /**
     * 30 秒自动保存的落点。**只写本地**，不产生任何服务端记录——
     * 半成品不该出现在观点列表里。
     */
    fun autoSaveDraft() {
        val state = _uiState.value
        if (!state.isDirty || state.isSaved) return
        viewModelScope.launch {
            draftStore.save(editingDiaryId, state.title, state.content)
            val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            _uiState.update { it.copy(autoSavedAt = now) }
        }
    }

    // ============ 字段编辑 ============

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateContent(content: String) {
        _uiState.update { it.copy(content = content) }
    }

    /** 提交到服务端（真正的「发布」）。成功后清掉本地草稿。 */
    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        if (state.title.isBlank() || state.content.isBlank()) {
            _uiState.update { it.copy(error = "标题和内容不能为空") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val response = if (state.isEditMode && editingDiaryId != null) {
                    apiService.updateDiary(
                        editingDiaryId!!,
                        DiaryDto.UpdateDiaryRequest(
                            title = state.title,
                            content = state.content,
                        ),
                    )
                } else {
                    apiService.createDiary(
                        DiaryDto.CreateDiaryRequest(
                            title = state.title,
                            content = state.content,
                        ),
                    )
                }
                if (response.isSuccess) {
                    // 发布成功 → 本地草稿失去意义，立刻清掉，否则下次进来会被"恢复"
                    draftStore.clear()
                    _uiState.update { it.copy(isSaving = false, isSaved = true) }
                } else {
                    _uiState.update { it.copy(isSaving = false, error = response.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
