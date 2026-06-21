package com.couple.translator.feature.couple.letter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.repository.LetterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComposeLetterUiState(
    val letterId: Long? = null,
    val title: String = "",
    val content: String = "",
    val letterType: String = "normal",
    val isPrivate: Boolean = false,
    val isSaving: Boolean = false,
    val isSending: Boolean = false,
    val isRewriting: Boolean = false,
    val showAiAssist: Boolean = false,
    val error: String = "",
)

sealed class ComposeLetterUiEvent {
    data class ShowError(val message: String) : ComposeLetterUiEvent()
    object LetterSent : ComposeLetterUiEvent()
    data class Rewritten(val content: String) : ComposeLetterUiEvent()
}

@HiltViewModel
class ComposeLetterViewModel @Inject constructor(
    private val letterRepository: LetterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeLetterUiState())
    val uiState: StateFlow<ComposeLetterUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<ComposeLetterUiEvent>()
    val event: SharedFlow<ComposeLetterUiEvent> = _event.asSharedFlow()

    private var autoSaveJob: Job? = null

    fun loadDraft(letterId: Long) {
        viewModelScope.launch {
            letterRepository.getLetter(letterId).fold(
                onSuccess = { letter ->
                    letter?.let {
                        _uiState.update { state ->
                            state.copy(
                                letterId = it.id,
                                title = it.title ?: "",
                                content = it.content ?: "",
                                letterType = it.letterType,
                                isPrivate = it.isPrivate,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "加载草稿失败") }
                },
            )
        }
    }

    fun onTitleChange(title: String) {
        _uiState.update { it.copy(title = title) }
        scheduleAutoSave()
    }

    fun onContentChange(content: String) {
        _uiState.update { it.copy(content = content) }
        scheduleAutoSave()
    }

    fun onLetterTypeChange(type: String) {
        _uiState.update { it.copy(letterType = type) }
    }

    fun onPrivateChange(isPrivate: Boolean) {
        _uiState.update { it.copy(isPrivate = isPrivate) }
    }

    fun showAiAssist() {
        _uiState.update { it.copy(showAiAssist = true) }
    }

    fun dismissAiAssist() {
        _uiState.update { it.copy(showAiAssist = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun saveDraft() {
        val state = _uiState.value
        if (state.title.isBlank() && state.content.isBlank()) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val request = LetterDto.LetterRequest(
                title = state.title,
                content = state.content,
                letterType = state.letterType,
                status = "draft",
                isPrivate = state.isPrivate,
            )

            val result = if (state.letterId != null) {
                letterRepository.updateLetter(state.letterId, request)
            } else {
                letterRepository.createLetter(request)
            }

            result.fold(
                onSuccess = { letter ->
                    letter?.let {
                        _uiState.update { state ->
                            state.copy(letterId = it.id, isSaving = false)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSaving = false, error = error.message ?: "保存失败") }
                },
            )
        }
    }

    fun sendLetter() {
        val state = _uiState.value
        if (state.content.isBlank()) {
            _uiState.update { it.copy(error = "请输入信件内容") }
            return
        }

        _uiState.update { it.copy(isSending = true, error = "") }
        viewModelScope.launch {
            // Step 1: Save as DRAFT first (not sent)
            val request = LetterDto.LetterRequest(
                title = state.title.ifBlank { null },
                content = state.content,
                letterType = state.letterType,
                status = "draft",
                isPrivate = state.isPrivate,
            )

            val letterId = state.letterId
            val saveResult = if (letterId != null) {
                letterRepository.updateLetter(letterId, request)
            } else {
                letterRepository.createLetter(request)
            }

            saveResult.fold(
                onSuccess = { letter ->
                    if (letter != null) {
                        // Update local letterId
                        _uiState.update { it.copy(letterId = letter.id) }

                        // Step 2: Call send endpoint (backend transitions draft -> sent)
                        letterRepository.sendLetter(letter.id).fold(
                            onSuccess = {
                                _uiState.update { it.copy(isSending = false) }
                                _event.emit(ComposeLetterUiEvent.LetterSent)
                            },
                            onFailure = { error ->
                                _uiState.update {
                                    it.copy(isSending = false, error = error.message ?: "发送失败")
                                }
                            },
                        )
                    } else {
                        _uiState.update {
                            it.copy(isSending = false, error = "保存失败")
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSending = false, error = error.message ?: "保存失败")
                    }
                },
            )
        }
    }

    fun rewriteLetter(style: String) {
        val state = _uiState.value
        if (state.content.isBlank()) {
            _uiState.update { it.copy(error = "请先输入信件内容") }
            return
        }

        _uiState.update { it.copy(isRewriting = true, showAiAssist = false) }
        viewModelScope.launch {
            // Ensure letter is saved first
            val letterId = state.letterId ?: run {
                val saveReq = LetterDto.LetterRequest(
                    title = state.title,
                    content = state.content,
                    letterType = state.letterType,
                    status = "draft",
                    isPrivate = state.isPrivate,
                )
                letterRepository.createLetter(saveReq).getOrNull()?.also {
                    _uiState.update { s -> s.copy(letterId = it.id) }
                }?.id
            }

            if (letterId == null) {
                _uiState.update { it.copy(isRewriting = false, error = "保存失败") }
                return@launch
            }

            letterRepository.rewriteLetter(letterId, style, state.content).fold(
                onSuccess = { response ->
                    response?.let {
                        _uiState.update { state ->
                            state.copy(content = it.rewrittenContent, isRewriting = false)
                        }
                        _event.emit(ComposeLetterUiEvent.Rewritten(it.rewrittenContent))
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isRewriting = false, error = error.message ?: "AI 改写失败") }
                },
            )
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(30_000)
            saveDraft()
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoSaveJob?.cancel()
    }
}
