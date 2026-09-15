package com.couple.translator.core.ui.questionnaire

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.QuestionnaireDto
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.LoadingIndicator
import com.couple.translator.core.ui.components.PrimaryButton
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppErrorRed
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionnaireScreen(
    questionnaireId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToProfile: (Boolean) -> Unit,
    viewModel: QuestionnaireViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSubmitDialog by remember { mutableStateOf(false) }
    var showOverview by remember { mutableStateOf(false) }

    LaunchedEffect(questionnaireId) {
        viewModel.loadQuestions(questionnaireId)
    }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is QuestionnaireUiEvent.NavigateToProfile -> {
                    onNavigateToProfile(event.coupleProfileReady)
                }
                is QuestionnaireUiEvent.ShowValidation -> {
                    showSubmitDialog = true
                }
                is QuestionnaireUiEvent.ShowError -> {}
                is QuestionnaireUiEvent.SubmitSuccess -> {}
            }
        }
    }

    if (uiState.error.isNotEmpty() && !showSubmitDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("提示") },
            text = { Text(uiState.error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("确定")
                }
            },
        )
    }

    // Submit validation dialog
    if (showSubmitDialog) {
        val unanswered = uiState.getUnansweredRequired()
        if (unanswered.isNotEmpty()) {
            SubmitValidationDialog(
                unansweredCount = unanswered.size,
                totalCount = uiState.questions.size,
                unansweredIndices = unanswered.map { uiState.questions.indexOf(it) },
                onJumpTo = { index ->
                    showSubmitDialog = false
                    viewModel.jumpTo(index)
                },
                onDismiss = { showSubmitDialog = false },
            )
        }
    }

    // Overview bottom sheet
    if (showOverview) {
        QuestionOverviewSheet(
            questions = uiState.questions,
            answers = uiState.answers,
            currentIndex = uiState.currentIndex,
            onSelect = { index ->
                showOverview = false
                viewModel.jumpTo(index)
            },
            onDismiss = { showOverview = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.questions.isNotEmpty()) {
                        Text(
                            text = "${uiState.currentIndex + 1} / ${uiState.questions.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showOverview = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "题目总览")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                ),
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        if (uiState.questions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无题目", style = MaterialTheme.typography.bodyLarge, color = AppTextSecondary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Progress bar with answered count
            val answeredCount = uiState.questions.count { uiState.answers.containsKey(it.id) }
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                val animatedProgress = remember { Animatable(0f) }
                LaunchedEffect(uiState.progress) {
                    animatedProgress.animateTo(
                        targetValue = uiState.progress,
                        animationSpec = tween(400, easing = FastOutSlowInEasing),
                    )
                }
                LinearProgressIndicator(
                    progress = { animatedProgress.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AppAccent,
                    trackColor = AppBorderLight,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "已答 $answeredCount 题",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextTertiary,
                )
            }

            // Question content with animation
            uiState.currentQuestion?.let { question ->
                AnimatedContent(
                    targetState = uiState.currentIndex,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        slideInHorizontally(
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                            initialOffsetX = { fullWidth -> direction * fullWidth / 3 }
                        ) + fadeIn(animationSpec = tween(200)) togetherWith
                        slideOutHorizontally(
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                            targetOffsetX = { fullWidth -> -direction * fullWidth / 3 }
                        ) + fadeOut(animationSpec = tween(150))
                    },
                    label = "question_transition",
                ) { targetIndex ->
                    val targetQuestion = uiState.questions.getOrNull(targetIndex) ?: question
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Question number badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppAccentLight)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "第 ${targetIndex + 1} 题",
                                style = MaterialTheme.typography.labelMedium,
                                color = AppAccent,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Question text
                        Text(
                            text = targetQuestion.questionText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 28.sp,
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Options
                        when (targetQuestion.questionType) {
                            "single_choice" -> SingleChoiceContent(
                                question = targetQuestion,
                                selectedOptionId = uiState.answers[targetQuestion.id] as? Long,
                                onSelect = { viewModel.onSingleChoiceSelect(targetQuestion.id, it) },
                            )
                            "multi_choice" -> MultiChoiceContent(
                                question = targetQuestion,
                                selectedOptionIds = (uiState.answers[targetQuestion.id] as? List<*>)?.filterIsInstance<Long>() ?: emptyList(),
                                onToggle = { viewModel.onMultiChoiceToggle(targetQuestion.id, it) },
                            )
                            "likert" -> LikertContent(
                                selectedValue = (uiState.answers[targetQuestion.id] as? Number)?.toInt(),
                                onSelect = { viewModel.onLikertSelect(targetQuestion.id, it) },
                            )
                            "sort" -> SortContent(
                                question = targetQuestion,
                                orderedOptionIds = (uiState.answers[targetQuestion.id] as? List<*>)?.filterIsInstance<Long>() ?: emptyList(),
                                onMoveUp = { idx -> viewModel.onSortReorder(targetQuestion.id, idx, idx - 1) },
                                onMoveDown = { idx -> viewModel.onSortReorder(targetQuestion.id, idx, idx + 1) },
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // Bottom navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppBackground)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!uiState.isFirstQuestion) {
                    TextButton(onClick = { viewModel.onPrevious() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                        )
                        Text("上一题")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (uiState.isLastQuestion) {
                    PrimaryButton(
                        text = "提交问卷",
                        onClick = { viewModel.onSubmit() },
                        isLoading = uiState.isSubmitting,
                        modifier = Modifier.width(160.dp),
                    )
                } else {
                    TextButton(onClick = { viewModel.onNext() }) {
                        Text("下一题")
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}

// ==================== Submit Validation Dialog ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubmitValidationDialog(
    unansweredCount: Int,
    totalCount: Int,
    unansweredIndices: List<Int>,
    onJumpTo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = AppErrorRed,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = "还有 $unansweredCount 题未作答",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = "完成所有必答题后才能提交，以下是未作答的题目：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    unansweredIndices.take(12).forEach { index ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, AppAccent, RoundedCornerShape(8.dp))
                                .clickable { onJumpTo(index) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "第 ${index + 1} 题",
                                style = MaterialTheme.typography.labelMedium,
                                color = AppAccent,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (unansweredIndices.size > 12) {
                        Text(
                            text = "等共 ${unansweredIndices.size} 题",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTextTertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("继续答题")
            }
        },
    )
}

// ==================== Question Overview Sheet ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionOverviewSheet(
    questions: List<QuestionnaireDto.QuestionResponse>,
    answers: Map<Long, Any>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "题目总览",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val answeredCount = questions.count { answers.containsKey(it.id) }
            Text(
                text = "已答 $answeredCount / ${questions.size} 题",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(300.dp),
            ) {
                itemsIndexed(questions) { index, question ->
                    val isAnswered = answers.containsKey(question.id)
                    val isCurrent = index == currentIndex
                    val bgColor = when {
                        isCurrent -> AppAccent
                        isAnswered -> AppAccentLight
                        else -> Color.Transparent
                    }
                    val textColor = when {
                        isCurrent -> Color.White
                        isAnswered -> AppAccent
                        else -> AppTextTertiary
                    }
                    val borderColor = when {
                        isCurrent -> AppAccent
                        isAnswered -> AppAccent
                        else -> AppBorderLight
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isAnswered && !isCurrent) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = AppAccent,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                color = textColor,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Legend
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppAccent),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("当前", style = MaterialTheme.typography.labelSmall, color = AppTextTertiary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppAccentLight),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("已答", style = MaterialTheme.typography.labelSmall, color = AppTextTertiary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, AppBorderLight, RoundedCornerShape(4.dp)),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("未答", style = MaterialTheme.typography.labelSmall, color = AppTextTertiary)
                }
            }
        }
    }
}

// ==================== Single Choice ====================

@Composable
private fun SingleChoiceContent(
    question: QuestionnaireDto.QuestionResponse,
    selectedOptionId: Long?,
    onSelect: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options?.sortedBy { it.sortOrder }?.forEach { option ->
            val isSelected = selectedOptionId == option.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) AppAccent else AppBorderLight,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .background(if (isSelected) AppAccentLight else Color.Transparent)
                    .clickable { onSelect(option.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(option.id) },
                    colors = RadioButtonDefaults.colors(selectedColor = AppAccent),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = option.optionText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) AppAccent else AppTextPrimary,
                )
            }
        }
    }
}

// ==================== Multi Choice ====================

@Composable
private fun MultiChoiceContent(
    question: QuestionnaireDto.QuestionResponse,
    selectedOptionIds: List<Long>,
    onToggle: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options?.sortedBy { it.sortOrder }?.forEach { option ->
            val isSelected = selectedOptionIds.contains(option.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) AppAccent else AppBorderLight,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .background(if (isSelected) AppAccentLight else Color.Transparent)
                    .clickable { onToggle(option.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle(option.id) },
                    colors = CheckboxDefaults.colors(checkedColor = AppAccent),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = option.optionText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) AppAccent else AppTextPrimary,
                )
            }
        }

        if (selectedOptionIds.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "已选 ${selectedOptionIds.size} 项",
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
    }
}

// ==================== Likert ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LikertContent(
    selectedValue: Int?,
    onSelect: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("非常不同意", style = MaterialTheme.typography.labelSmall, color = AppTextTertiary)
            Text("非常同意", style = MaterialTheme.typography.labelSmall, color = AppTextTertiary)
        }

        Spacer(modifier = Modifier.height(16.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            (1..7).forEach { value ->
                val isSelected = selectedValue == value
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.5.dp,
                            color = if (isSelected) AppAccent else AppBorderLight,
                            shape = CircleShape,
                        )
                        .background(if (isSelected) AppAccent else Color.Transparent)
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) Color.White else AppTextPrimary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        if (selectedValue != null) {
            Spacer(modifier = Modifier.height(16.dp))
            val label = when (selectedValue) {
                1 -> "非常不同意"
                2 -> "不同意"
                3 -> "有点不同意"
                4 -> "中立"
                5 -> "有点同意"
                6 -> "同意"
                7 -> "非常同意"
                else -> ""
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppAccent,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ==================== Sort ====================

@Composable
private fun SortContent(
    question: QuestionnaireDto.QuestionResponse,
    orderedOptionIds: List<Long>,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    val optionMap = question.options?.associateBy { it.id } ?: emptyMap()
    val displayList = if (orderedOptionIds.isNotEmpty()) {
        orderedOptionIds
    } else {
        question.options?.sortedBy { it.sortOrder }?.map { it.id } ?: emptyList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "点击箭头调整顺序，排在前面的更重要",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextTertiary,
        )

        displayList.forEachIndexed { index, optionId ->
            val option = optionMap[optionId] ?: return@forEachIndexed
            val isFirst = index == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = if (isFirst) 2.dp else 1.dp,
                        color = if (isFirst) AppAccent else AppBorderLight,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .background(if (isFirst) AppAccentLight else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Rank number
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isFirst) AppAccent else AppBorderLight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isFirst) Color.White else AppTextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = option.optionText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isFirst) FontWeight.Medium else FontWeight.Normal,
                    color = if (isFirst) AppAccent else AppTextPrimary,
                    modifier = Modifier.weight(1f),
                )

                // Up/Down buttons
                Column {
                    IconButton(
                        onClick = { onMoveUp(index) },
                        enabled = index > 0,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "上移",
                            modifier = Modifier.size(22.dp),
                            tint = if (index > 0) AppAccent else AppBorderLight,
                        )
                    }
                    IconButton(
                        onClick = { onMoveDown(index) },
                        enabled = index < displayList.size - 1,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "下移",
                            modifier = Modifier.size(22.dp),
                            tint = if (index < displayList.size - 1) AppAccent else AppBorderLight,
                        )
                    }
                }
            }
        }
    }
}
