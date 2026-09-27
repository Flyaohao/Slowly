package com.couple.translator.core.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.core.data.model.ProfileDto
import com.couple.translator.core.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileVersionsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val versions: List<ProfileDto.ProfileVersionResponse> = emptyList(),
    /** 正在展开对比的版本 id（同一时刻只展开一个，避免一屏里塞满差异）。 */
    val expandedId: Long? = null,
    val diff: ProfileDto.VersionDiffResponse? = null,
    val isDiffLoading: Boolean = false,
    /** 正在撤回 / 删除，用于禁用按钮防止连点。 */
    val isActing: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ProfileVersionsViewModel @Inject constructor(
    private val repository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileVersionsUiState())
    val uiState: StateFlow<ProfileVersionsUiState> = _uiState.asStateFlow()

    /** 最新版本 = 当前生效的画像。列表本身按 version 倒序，所以就是第一条。 */
    private fun currentIdOf(list: List<ProfileDto.ProfileVersionResponse>): Long =
        list.firstOrNull()?.id ?: 0L

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getProfileVersions().fold(
                onSuccess = { list ->
                    _uiState.update { it.copy(isLoading = false, versions = list, expandedId = null, diff = null) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
                },
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            repository.getProfileVersions().fold(
                onSuccess = { list -> _uiState.update { it.copy(isRefreshing = false, versions = list) } },
                onFailure = { e ->
                    _uiState.update { it.copy(isRefreshing = false, error = e.message ?: "刷新失败") }
                },
            )
        }
    }

    /**
     * 展开某个版本，并拉它与当前版本的差异。
     *
     * 对比的基准固定为「当前版本」而不是「上一个版本」：用户此刻真正的问题是
     * 「回到那会儿会有什么不同」，与 v-1 比解决不了这个问题。
     */
    fun toggleExpand(versionId: Long) {
        if (_uiState.value.expandedId == versionId) {
            _uiState.update { it.copy(expandedId = null, diff = null) }
            return
        }
        _uiState.update { it.copy(expandedId = versionId, diff = null, isDiffLoading = true, error = null) }
        viewModelScope.launch {
            repository.diffProfileVersion(versionId).fold(
                onSuccess = { d -> _uiState.update { it.copy(isDiffLoading = false, diff = d) } },
                onFailure = { e ->
                    _uiState.update { it.copy(isDiffLoading = false, error = e.message ?: "对比失败") }
                },
            )
        }
    }

    /**
     * 撤回到某个历史版本。
     *
     * 服务端是**派生式**撤回（把目标版本再派生一个新版本），所以撤回本身
     * 也会出现在列表里、也能再被撤回——不会出现"撤错了就回不去"。
     */
    fun restore(versionId: Long) {
        if (_uiState.value.isActing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActing = true, error = null, message = null) }
            repository.restoreProfileVersion(versionId).fold(
                onSuccess = { r ->
                    _uiState.update {
                        it.copy(
                            isActing = false,
                            message = "已撤回，并记录为新版本 v${r.version}。",
                        )
                    }
                    load()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isActing = false, error = e.message ?: "撤回失败") }
                },
            )
        }
    }

    /** 删除一个历史版本（当前版本、被关系画像引用的版本服务端会拒绝）。 */
    fun delete(versionId: Long) {
        if (_uiState.value.isActing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActing = true, error = null, message = null) }
            repository.deleteProfileVersion(versionId).fold(
                onSuccess = {
                    _uiState.update { it.copy(isActing = false, message = "已删除该版本。") }
                    load()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isActing = false, error = e.message ?: "删除失败") }
                },
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
