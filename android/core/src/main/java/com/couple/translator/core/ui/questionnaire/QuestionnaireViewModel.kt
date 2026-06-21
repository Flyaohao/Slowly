package com.couple.translator.core.ui.questionnaire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.data.repository.QuestionnaireRepository
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

data class QuestionnaireUiState(
    val questionnaireId: Long = 0,
    val questions: List<QuestionnaireDto.QuestionResponse> = emptyList(),
    val currentIndex: Int = 0,
    val answers: Map<Long, Any> = emptyMap(),
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: String = "",
) {
    val currentQuestion: QuestionnaireDto.QuestionResponse?
        get() = questions.getOrNull(currentIndex)

    val progress: Float
        get() = if (questions.isEmpty()) 0f else (currentIndex + 1).toFloat() / questions.size

    val isLastQuestion: Boolean
        get() = currentIndex == questions.size - 1

    val isFirstQuestion: Boolean
        get() = currentIndex == 0

    fun getUnansweredRequired(): List<QuestionnaireDto.QuestionResponse> {
        return questions.filter { q ->
            q.isRequired && !isAnswered(q.id)
        }
    }

    private fun isAnswered(questionId: Long): Boolean {
        val answer = answers[questionId] ?: return false
        return when (answer) {
            is String -> answer.isNotBlank()
            is List<*> -> answer.isNotEmpty()
            else -> true
        }
    }
}

sealed class QuestionnaireUiEvent {
    data object SubmitSuccess : QuestionnaireUiEvent()
    data class NavigateToProfile(val coupleProfileReady: Boolean) : QuestionnaireUiEvent()
    data class ShowError(val message: String) : QuestionnaireUiEvent()
    data object ShowValidation : QuestionnaireUiEvent()
}

@HiltViewModel
class QuestionnaireViewModel @Inject constructor(
    private val questionnaireRepository: QuestionnaireRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuestionnaireUiState())
    val uiState: StateFlow<QuestionnaireUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<QuestionnaireUiEvent>()
    val event: SharedFlow<QuestionnaireUiEvent> = _event.asSharedFlow()

    private var autoAdvanceJob: Job? = null
    private val pendingSaveJobs = mutableSetOf<Job>()

    fun loadQuestions(questionnaireId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = "", questionnaireId = questionnaireId) }

            val questionsResult = questionnaireRepository.getQuestions(questionnaireId)

            questionsResult.fold(
                onSuccess = { questions ->
                    val existingAnswers = mutableMapOf<Long, Any>()
                    var savedIndex = 0
                    try {
                        val progressResult = questionnaireRepository.getProgress()
                        progressResult.getOrNull()?.let { progress ->
                            progress.answers.forEach { answer ->
                                answer.answerValue?.let { value ->
                                    existingAnswers[answer.questionId] = value
                                }
                            }
                            savedIndex = progress.currentQuestionIndex
                        }
                    } catch (_: Exception) {}

                    // Initialize sort questions with default option order if not already answered
                    val sortedQuestions = questions.sortedBy { q -> q.sortOrder }
                    for (q in sortedQuestions) {
                        if (q.questionType == "sort" && !existingAnswers.containsKey(q.id)) {
                            val defaultOrder = q.options?.sortedBy { it.sortOrder }?.map { it.id } ?: emptyList()
                            if (defaultOrder.isNotEmpty()) {
                                existingAnswers[q.id] = defaultOrder
                            }
                        }
                    }

                    _uiState.update {
                        it.copy(
                            questions = sortedQuestions,
                            answers = existingAnswers,
                            currentIndex = if (questions.isEmpty()) 0 else savedIndex.coerceIn(0, questions.size - 1),
                            isLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    fun onSingleChoiceSelect(questionId: Long, optionId: Long) {
        _uiState.update { state ->
            state.copy(answers = state.answers.toMutableMap().apply { put(questionId, optionId) })
        }
        saveAnswer(questionId, mapOf("selected_option_id" to optionId))
        scheduleAutoAdvance()
    }

    fun onMultiChoiceToggle(questionId: Long, optionId: Long) {
        _uiState.update { state ->
            val current = state.answers[questionId] as? List<*> ?: emptyList<Any>()
            val updated = if (current.contains(optionId)) {
                current.filter { it != optionId }
            } else {
                current + optionId
            }
            state.copy(answers = state.answers.toMutableMap().apply { put(questionId, updated) })
        }
        val current = _uiState.value.answers[questionId] as? List<*> ?: return
        saveAnswer(questionId, mapOf("selected_option_ids" to current))
    }

    fun onSortReorder(questionId: Long, fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            @Suppress("UNCHECKED_CAST")
            val current = (state.answers[questionId] as? List<Long>)?.toMutableList()
                ?: state.questions.find { it.id == questionId }
                    ?.options?.sortedBy { it.sortOrder }?.map { it.id }?.toMutableList()
                ?: mutableListOf()
            if (fromIndex in current.indices && toIndex in current.indices) {
                val item = current.removeAt(fromIndex)
                current.add(toIndex, item)
            }
            state.copy(answers = state.answers.toMutableMap().apply { put(questionId, current) })
        }
        val current = _uiState.value.answers[questionId] as? List<*> ?: return
        saveAnswer(questionId, mapOf("ordered_option_ids" to current))
    }

    fun onLikertSelect(questionId: Long, value: Int) {
        _uiState.update { state ->
            state.copy(answers = state.answers.toMutableMap().apply { put(questionId, value) })
        }
        val question = _uiState.value.questions.find { it.id == questionId }
        // Map value (1-7) to option by index: 1st option = value 1, 2nd = value 2, etc.
        val sortedOptions = question?.options?.sortedBy { it.sortOrder }
        val selectedOption = sortedOptions?.getOrNull(value - 1)
        if (selectedOption != null) {
            saveAnswer(questionId, mapOf("selected_option_id" to selectedOption.id))
        }
        scheduleAutoAdvance()
    }

    fun onNext() {
        cancelAutoAdvance()
        _uiState.update { state ->
            if (state.currentIndex < state.questions.size - 1) {
                val newIndex = state.currentIndex + 1
                saveCurrentIndex(newIndex)
                state.copy(currentIndex = newIndex)
            } else {
                state
            }
        }
    }

    fun onPrevious() {
        cancelAutoAdvance()
        _uiState.update { state ->
            if (state.currentIndex > 0) {
                val newIndex = state.currentIndex - 1
                saveCurrentIndex(newIndex)
                state.copy(currentIndex = newIndex)
            } else {
                state
            }
        }
    }

    fun jumpTo(index: Int) {
        cancelAutoAdvance()
        _uiState.update { state ->
            if (index in state.questions.indices) {
                saveCurrentIndex(index)
                state.copy(currentIndex = index)
            } else {
                state
            }
        }
    }

    fun onSubmit() {
        cancelAutoAdvance()
        val state = _uiState.value
        val unanswered = state.getUnansweredRequired()
        if (unanswered.isNotEmpty()) {
            viewModelScope.launch {
                _event.emit(QuestionnaireUiEvent.ShowValidation)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }

            // Wait for all pending saves to complete before submitting
            pendingSaveJobs.toList().forEach { it.join() }

            // Refresh state after all saves are done
            val currentState = _uiState.value
            val answers = currentState.answers.map { (questionId, value) ->
                QuestionnaireDto.AnswerRequest(questionId = questionId, answerValue = value)
            }
            questionnaireRepository.submitQuestionnaire(currentState.questionnaireId, answers).fold(
                onSuccess = { response ->
                    _uiState.update { it.copy(isSubmitting = false) }
                    _event.emit(QuestionnaireUiEvent.NavigateToProfile(response.coupleProfileReady))
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSubmitting = false, error = error.message ?: "提交失败")
                    }
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    private fun scheduleAutoAdvance() {
        cancelAutoAdvance()
        val state = _uiState.value
        if (state.isLastQuestion) return

        autoAdvanceJob = viewModelScope.launch {
            delay(500)
            onNext()
        }
    }

    private fun cancelAutoAdvance() {
        autoAdvanceJob?.cancel()
        autoAdvanceJob = null
    }

    private fun saveAnswer(questionId: Long, value: Any) {
        val job = viewModelScope.launch {
            val questionnaireId = _uiState.value.questionnaireId
            questionnaireRepository.saveAnswers(
                questionnaireId,
                listOf(QuestionnaireDto.AnswerRequest(questionId = questionId, answerValue = value)),
            )
        }
        pendingSaveJobs.add(job)
        job.invokeOnCompletion { pendingSaveJobs.remove(job) }
    }

    private fun saveCurrentIndex(index: Int) {
        viewModelScope.launch {
            val questionnaireId = _uiState.value.questionnaireId
            questionnaireRepository.saveProgressIndex(questionnaireId, index)
        }
    }
}
