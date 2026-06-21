package com.couple.translator.feature.couple.letter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.LetterDto
import com.couple.translator.feature.couple.data.repository.LetterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LetterDetailUiState(
    val letter: LetterDto.LetterResponse? = null,
    val understanding: LetterDto.LetterUnderstanding? = null,
    val isLoading: Boolean = false,
    val isLoadingAi: Boolean = false,
    val showUnderstanding: Boolean = false,
    val error: String = "",
)

sealed class LetterDetailUiEvent {
    data class ShowError(val message: String) : LetterDetailUiEvent()
    object LetterDeleted : LetterDetailUiEvent()
}

@HiltViewModel
class LetterDetailViewModel @Inject constructor(
    private val letterRepository: LetterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LetterDetailUiState())
    val uiState: StateFlow<LetterDetailUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<LetterDetailUiEvent>()
    val event: SharedFlow<LetterDetailUiEvent> = _event.asSharedFlow()

    fun loadLetter(id: Long) {
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            letterRepository.getLetter(id).fold(
                onSuccess = { letter ->
                    _uiState.update { it.copy(letter = letter, isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun toggleFavorite() {
        val letter = _uiState.value.letter ?: return
        viewModelScope.launch {
            letterRepository.toggleFavorite(letter.id).fold(
                onSuccess = { updated ->
                    _uiState.update { it.copy(letter = updated) }
                },
                onFailure = { error ->
                    _event.emit(LetterDetailUiEvent.ShowError(error.message ?: "操作失败"))
                },
            )
        }
    }

    fun understandLetter() {
        val letter = _uiState.value.letter ?: return
        _uiState.update { it.copy(isLoadingAi = true) }
        viewModelScope.launch {
            letterRepository.understandLetter(letter.id).fold(
                onSuccess = { understanding ->
                    _uiState.update {
                        it.copy(
                            understanding = understanding,
                            isLoadingAi = false,
                            showUnderstanding = true,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingAi = false) }
                    _event.emit(LetterDetailUiEvent.ShowError(error.message ?: "AI 理解失败"))
                },
            )
        }
    }

    fun dismissUnderstanding() {
        _uiState.update { it.copy(showUnderstanding = false) }
    }

    fun deleteLetter() {
        val letter = _uiState.value.letter ?: return
        viewModelScope.launch {
            letterRepository.deleteLetter(letter.id).fold(
                onSuccess = {
                    _event.emit(LetterDetailUiEvent.LetterDeleted)
                },
                onFailure = { error ->
                    _event.emit(LetterDetailUiEvent.ShowError(error.message ?: "删除失败"))
                },
            )
        }
    }
}
