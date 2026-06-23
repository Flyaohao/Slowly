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
    val recentDiaries: List<LetterDto.LetterResponse> = emptyList(),
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

    fun refresh(coupleMode: Boolean) {
        _uiState.update { it.copy(isRefreshing = true, error = "") }
        viewModelScope.launch {
            if (coupleMode) {
                refreshCoupleMailbox()
            } else {
                refreshSingleDiary()
            }
        }
    }

    private suspend fun refreshCoupleMailbox() {
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
                recentDiaries = emptyList(),
                isRefreshing = false,
            )
        }

        if (inboxResult.isFailure && sentResult.isFailure && favResult.isFailure) {
            _uiState.update { it.copy(error = inboxResult.exceptionOrNull()?.message ?: "加载失败") }
        }
    }

    private suspend fun refreshSingleDiary() {
        val draftsResult = letterRepository.getDrafts()
        val allResult = letterRepository.getLetters()

        val drafts = draftsResult.getOrNull()?.items ?: emptyList()
        val all = allResult.getOrNull()?.items ?: emptyList()
        val favorites = all.filter { it.isFavorite }

        _uiState.update {
            it.copy(
                receivedLetters = emptyList(),
                sentLetters = emptyList(),
                favoriteLetters = favorites,
                recentDiaries = drafts.take(5),
                isRefreshing = false,
            )
        }
    }

    fun loadMailbox(coupleMode: Boolean) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            if (coupleMode) {
                loadCoupleMailbox()
            } else {
                loadSingleDiary()
            }
        }
    }

    private suspend fun loadCoupleMailbox() {
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
                recentDiaries = emptyList(),
                isLoading = false,
            )
        }

        if (inboxResult.isFailure && sentResult.isFailure && favResult.isFailure) {
            _uiState.update { it.copy(error = inboxResult.exceptionOrNull()?.message ?: "加载失败") }
        }
    }

    private suspend fun loadSingleDiary() {
        val draftsResult = letterRepository.getDrafts()
        val allResult = letterRepository.getLetters()

        val drafts = draftsResult.getOrNull()?.items ?: emptyList()
        val all = allResult.getOrNull()?.items ?: emptyList()
        val favorites = all.filter { it.isFavorite }

        _uiState.update {
            it.copy(
                receivedLetters = emptyList(),
                sentLetters = emptyList(),
                favoriteLetters = favorites,
                recentDiaries = drafts.take(5),
                isLoading = false,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }
}
