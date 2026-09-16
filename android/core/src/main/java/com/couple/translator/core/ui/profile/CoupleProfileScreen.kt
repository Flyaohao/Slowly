package com.couple.translator.core.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppEmptyState
import com.couple.translator.core.ui.components.ErrorDialog
import com.couple.translator.core.ui.components.PullToRefreshLayout
import com.couple.translator.core.ui.components.SkeletonListCard
import com.couple.translator.core.ui.components.SkeletonPageHeader
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppRadius
import com.couple.translator.core.ui.theme.AppSpacing
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoupleProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: CoupleProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error.isNotEmpty()) {
        ErrorDialog(
            message = uiState.error,
            onDismiss = { viewModel.clearError() },
        )
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "情侣组合画像",
            )
        },
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.screenH),
            ) {
                SkeletonPageHeader()
                Spacer(modifier = Modifier.height(AppSpacing.section))
                SkeletonListCard(rows = 4)
            }
            return@PullToRefreshLayout
        }

        val coupleProfile = uiState.coupleProfile
        if (coupleProfile == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppEmptyState(
                    icon = Icons.Outlined.Info,
                    title = "双方都完成问卷后才能查看组合画像",
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@PullToRefreshLayout
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.screenH)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = AppAccentLight,
                shape = RoundedCornerShape(AppRadius.xl),
                contentPadding = PaddingValues(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = uiState.conflictPatternName,
                        style = MaterialTheme.typography.titleLarge,
                        color = AppAccent,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = uiState.conflictPatternDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            coupleProfile.summary?.let { summary ->
                Text(
                    text = "画像摘要",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextSecondary,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ProfileMiniCard(
                        title = "我的画像",
                        profile = coupleProfile.userAProfile,
                        dimensions = coupleProfile.userADimensions,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    ProfileMiniCard(
                        title = "TA 的画像",
                        profile = coupleProfile.userBProfile,
                        dimensions = coupleProfile.userBDimensions,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
        }
    }
}

@Composable
private fun ProfileMiniCard(
    title: String,
    profile: ProfileDto.RelationshipProfileResponse?,
    dimensions: List<ProfileDto.DimensionScoreResponse>,
) {
    val profileTypeName = when (profile?.profileType) {
        "secure" -> "安全型"
        "anxious" -> "焦虑型"
        "dismissive" -> "疏离型"
        "fearful" -> "恐惧型"
        "mixed" -> "混合型"
        else -> "未知"
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = AppSurface,
        shape = RoundedCornerShape(AppRadius.lg),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = AppTextTertiary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = profileTypeName,
                style = MaterialTheme.typography.titleMedium,
                color = AppAccent,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            dimensions.take(4).forEach { dim ->
                val name = when (dim.dimensionKey) {
                    "attachment_anxiety" -> "焦虑"
                    "attachment_avoidance" -> "回避"
                    "conflict_pursue" -> "追问"
                    "conflict_withdraw" -> "退缩"
                    else -> dim.dimensionKey.take(4)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                    )
                    Text(
                        text = "${dim.score.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
