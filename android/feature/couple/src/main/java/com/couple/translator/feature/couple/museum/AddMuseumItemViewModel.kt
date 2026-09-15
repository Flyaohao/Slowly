package com.couple.translator.feature.couple.museum

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couple.translator.feature.couple.data.model.MuseumDto
import com.couple.translator.feature.couple.data.repository.MuseumRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

data class AddMuseumItemUiState(
    val title: String = "",
    val story: String = "",
    val itemType: String = "word",
    val imageUri: Uri? = null,
    val imageUploadedUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String = "",
    val created: Boolean = false,
)

@HiltViewModel
class AddMuseumItemViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: MuseumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddMuseumItemUiState())
    val uiState: StateFlow<AddMuseumItemUiState> = _uiState.asStateFlow()

    /** 关闭错误弹窗（B-05：此前 Screen 传空的 onDismiss，弹窗无法关闭） */
    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateStory(story: String) {
        _uiState.update { it.copy(story = story) }
    }

    fun updateItemType(type: String) {
        _uiState.update { it.copy(itemType = type) }
    }

    fun updateImage(uri: Uri?) {
        _uiState.update { it.copy(imageUri = uri, imageUploadedUrl = null) }
    }

    /** 读取所选图片的字节数组与文件名（在 IO 线程）。 */
    private suspend fun readImage(uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        var name = "photo.jpg"
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.let { name = it }
            }
        }
        // 后端只认 jpg/jpeg/png/webp 后缀；display name 没有可用后缀时按 png 兜底
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in setOf("jpg", "jpeg", "png", "webp")) name = "$name.png"
        bytes to name
    }

    fun createItem() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "请输入标题") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            // 先传图拿到 image_url，再创建藏品
            var imageUrl: String? = state.imageUploadedUrl
            val pendingUri = state.imageUri
            if (pendingUri != null && imageUrl == null) {
                val image = readImage(pendingUri)
                if (image == null) {
                    _uiState.update { it.copy(isLoading = false, error = "图片读取失败，请重试") }
                    return@launch
                }
                val uploaded = repository.uploadImage(image.first, image.second)
                imageUrl = uploaded.fold(
                    onSuccess = { it },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(isLoading = false, error = err.message ?: "图片上传失败")
                        }
                        return@launch
                    },
                )
            }
            repository.createItem(
                MuseumDto.CreateMuseumItemRequest(
                    itemType = state.itemType,
                    title = state.title,
                    story = state.story.ifBlank { null },
                    imageUrl = imageUrl,
                ),
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, created = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "创建失败")
                    }
                },
            )
        }
    }
}
