package com.couple.translator.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地草稿：写作页每 30 秒把未提交的标题/正文存到这里，退出时用来挽回意外丢失。
 *
 * **为什么不建第二个 DataStore**：`TokenStore.kt` 顶部那条约定是硬约束——同一个
 * DataStore 文件只允许存在一个实例，另写一份 `preferencesDataStore(name = ...)`
 * 会在运行时抛 `IllegalStateException: There are multiple DataStores active for
 * the same file`。约定给出的解法是复用同一个委托，这里照做：`context.dataStore`
 * 是本包 internal 的，直接拿来用，只是换了一组 key。
 *
 * 存的是**纯本地内容**，不上云、不进观点列表——它是"防丢"，不是"发布"。
 * 真正的发布动作发生在写作页退出弹窗点「保存」的那一刻。
 */
@Singleton
class DraftStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val titleKey = stringPreferencesKey("draft_viewpoint_title")
    private val contentKey = stringPreferencesKey("draft_viewpoint_content")
    private val editIdKey = longPreferencesKey("draft_viewpoint_edit_id")
    private val savedAtKey = stringPreferencesKey("draft_viewpoint_saved_at")

    /** 一份草稿。[editId] 为 null 表示新建，否则是正在编辑的观点 id。 */
    data class Draft(
        val editId: Long?,
        val title: String,
        val content: String,
        val savedAt: String,
    )

    suspend fun save(editId: Long?, title: String, content: String) {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        context.dataStore.edit { prefs ->
            prefs[titleKey] = title
            prefs[contentKey] = content
            prefs[editIdKey] = editId ?: NEW_DRAFT
            prefs[savedAtKey] = now
        }
    }

    /** 读草稿；两条都为空视为没有草稿。 */
    suspend fun read(): Draft? {
        val prefs = context.dataStore.data.first()
        val title = prefs[titleKey].orEmpty()
        val content = prefs[contentKey].orEmpty()
        if (title.isBlank() && content.isBlank()) return null
        val rawEditId = prefs[editIdKey] ?: NEW_DRAFT
        return Draft(
            editId = if (rawEditId > 0L) rawEditId else null,
            title = title,
            content = content,
            savedAt = prefs[savedAtKey].orEmpty(),
        )
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(titleKey)
            prefs.remove(contentKey)
            prefs.remove(editIdKey)
            prefs.remove(savedAtKey)
        }
    }

    private companion object {
        /** 新建模式下写入的哨兵：DataStore 的 Long 不能为 null，用 -1 表示「不属于任何已有观点」。 */
        const val NEW_DRAFT = -1L
    }
}
