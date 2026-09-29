package com.couple.translator.feature.couple.letter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.repository.LetterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MailboxUiState(
    val receivedLetters: List<LetterDto.LetterResponse> = emptyList(),
    val sentLetters: List<LetterDto.LetterResponse> = emptyList(),
    val favoriteLetters: List<LetterDto.LetterResponse> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String = "",
)

@HiltViewModel
class MailboxViewModel @Inject constructor(
    private val letterRepository: LetterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MailboxUiState())
    val uiState: StateFlow<MailboxUiState> = _uiState.asStateFlow()

    // 2026-09-29：单身模式删除后信箱恒为情侣模式，原 coupleMode 分支与
    // 单侧日记加载（refreshSingleDiary / loadSingleDiary）已一并移除。

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            val inboxResult = letterRepository.getInbox()
            val sentResult = letterRepository.getLetters(direction = "sent")
            val favResult = letterRepository.getLetters()

            val received = inboxResult.getOrNull()?.items ?: emptyList()
            val sent = sentResult.getOrNull()?.items ?: emptyList()
            val all = favResult.getOrNull()?.items ?: emptyList()
            val favorites = all.filter { it.isFavorite }

            _uiState.update {
                it.copy(
                    receivedLetters = received,
                    sentLetters = sent,
                    favoriteLetters = favorites,
                    isRefreshing = false,
                )
            }

            if (inboxResult.isFailure && sentResult.isFailure && favResult.isFailure) {
                _uiState.update { it.copy(error = inboxResult.exceptionOrNull()?.message ?: "加载失败") }
            }
        }
    }

    fun loadMailbox() {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val inboxResult = letterRepository.getInbox()
            val sentResult = letterRepository.getLetters(direction = "sent")
            val favResult = letterRepository.getLetters()

            val received = inboxResult.getOrNull()?.items ?: emptyList()
            val sent = sentResult.getOrNull()?.items ?: emptyList()
            val all = favResult.getOrNull()?.items ?: emptyList()
            val favorites = all.filter { it.isFavorite }

            _uiState.update {
                it.copy(
                    receivedLetters = received,
                    sentLetters = sent,
                    favoriteLetters = favorites,
                    isLoading = false,
                )
            }

            if (inboxResult.isFailure && sentResult.isFailure && favResult.isFailure) {
                _uiState.update { it.copy(error = inboxResult.exceptionOrNull()?.message ?: "加载失败") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }
}
