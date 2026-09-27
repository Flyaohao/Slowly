package com.couple.translator.feature.couple.data.repository

import com.couple.translator.core.network.sseFrames
import com.couple.translator.feature.couple.data.model.MediationRoomDto
import com.couple.translator.feature.couple.network.CoupleApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 共同调解室 Repository（设计文档 2026-09-28）。
 *
 * SSE 报文解析复用 `core.network.sseFrames()`（与军师 tab 同一套帧解析，
 * 各场景自抄解析器迟早抄出不一致）；这里只把通用帧翻译成军师发言语义。
 */
@Singleton
class MediationRoomRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val metaAdapter = moshi.adapter(Map::class.java)

    // ---------- 房间 CRUD / 状态动作 ----------

    suspend fun listRooms(): Result<MediationRoomDto.RoomListResponse?> = runCatchingApi {
        apiService.getMediationRooms()
    }

    suspend fun getStyles(): Result<List<MediationRoomDto.StyleItem>?> {
        val result = runCatchingApi { apiService.getMediationRoomStyles() }
        return when {
            result.isSuccess && !result.getOrNull().isNullOrEmpty() -> result
            else -> Result.success(defaultStyles())
        }
    }

    suspend fun createRoom(request: MediationRoomDto.CreateRoomRequest): Result<MediationRoomDto.RoomSummary?> =
        runCatchingApi { apiService.createMediationRoom(request) }

    suspend fun getRoom(roomId: Long): Result<MediationRoomDto.RoomDetail?> =
        runCatchingApi { apiService.getMediationRoom(roomId) }

    suspend fun getMessages(roomId: Long, afterId: Long): Result<MediationRoomDto.RoomMessagesResponse?> =
        runCatchingApi { apiService.getMediationRoomMessages(roomId, afterId) }

    suspend fun postMessage(
        roomId: Long,
        request: MediationRoomDto.PostMessageRequest,
    ): Result<MediationRoomDto.PostMessageResponse?> =
        runCatchingApi { apiService.postMediationRoomMessage(roomId, request) }

    suspend fun agree(roomId: Long): Result<MediationRoomDto.SimpleActionResponse?> =
        runCatchingApi { apiService.agreeMediationRoom(roomId) }

    suspend fun supplement(
        roomId: Long,
        request: MediationRoomDto.SupplementRequest,
    ): Result<MediationRoomDto.SupplementResponse?> =
        runCatchingApi { apiService.supplementMediationRoom(roomId, request) }

    suspend fun endVote(roomId: Long, action: String): Result<MediationRoomDto.SimpleActionResponse?> =
        runCatchingApi { apiService.endVoteMediationRoom(roomId, MediationRoomDto.EndVoteRequest(action)) }

    suspend fun confirmSettlement(roomId: Long): Result<MediationRoomDto.SimpleActionResponse?> =
        runCatchingApi { apiService.confirmMediationRoomSettlement(roomId) }

    suspend fun retrySettlement(roomId: Long): Result<MediationRoomDto.SimpleActionResponse?> =
        runCatchingApi { apiService.retryMediationRoomSettlement(roomId) }

    // ---------- 军师发言 SSE ----------

    /**
     * 军师发言流：meta → thinking* / delta* → done | error。
     * 与 [AiRepository.chatStream] 同构：`body.use` 保证取消/异常时立刻关连接，
     * `.catch` 把连接中断收敛成终态事件（否则 UI 的 isStreaming 永远为 true）。
     */
    fun advisorStream(roomId: Long, token: String): Flow<MediationRoomDto.AdvisorStreamEvent> = flow {
        val response = try {
            apiService.streamMediationRoomAdvisor(
                roomId, MediationRoomDto.AdvisorStreamRequest(token)
            )
        } catch (e: Exception) {
            emit(MediationRoomDto.AdvisorStreamEvent.Failure(50000, "网络异常：${e.message ?: "连接失败"}"))
            return@flow
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            emit(MediationRoomDto.AdvisorStreamEvent.Failure(response.code(), "服务端错误（${response.code()}）"))
            return@flow
        }
        val body = response.body()
        if (body == null) {
            emit(MediationRoomDto.AdvisorStreamEvent.Failure(50000, "服务端返回空响应"))
            return@flow
        }
        body.use { rb ->
            emitAll(rb.sseFrames().mapNotNull { frame -> decode(frame.event, frame.data) })
        }
    }.catch { e ->
        emit(MediationRoomDto.AdvisorStreamEvent.Failure(50000, "连接中断：${e.message ?: "未知错误"}"))
    }.flowOn(Dispatchers.IO)

    private fun decode(event: String, json: String): MediationRoomDto.AdvisorStreamEvent? {
        return try {
            when (event) {
                "meta" -> {
                    val map = metaAdapter.fromJson(json) ?: return null
                    val roomId = (map["room_id"] as? Number)?.toLong() ?: 0L
                    MediationRoomDto.AdvisorStreamEvent.Meta(roomId, map["scene"] as? String ?: "")
                }
                "delta" -> {
                    val map = metaAdapter.fromJson(json) ?: return null
                    MediationRoomDto.AdvisorStreamEvent.Delta(map["delta"] as? String ?: "")
                }
                "thinking" -> {
                    val map = metaAdapter.fromJson(json) ?: return null
                    MediationRoomDto.AdvisorStreamEvent.Thinking(map["delta"] as? String ?: "")
                }
                "done" -> {
                    val map = metaAdapter.fromJson(json) ?: return null
                    MediationRoomDto.AdvisorStreamEvent.Done(
                        (map["round_no"] as? Number)?.toInt() ?: 0
                    )
                }
                "error" -> {
                    val map = metaAdapter.fromJson(json)
                    MediationRoomDto.AdvisorStreamEvent.Failure(
                        (map?.get("code") as? Number)?.toInt() ?: 50000,
                        map?.get("message") as? String ?: "军师发言失败",
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            // thinking 等过程帧解析失败不中断整条流
            null
        }
    }

    // ---------- 内部 ----------

    private inline fun <T> runCatchingApi(block: () -> com.couple.translator.core.network.ApiResponse<T>): Result<T?> {
        return try {
            val response = block()
            if (response.isSuccess) Result.success(response.data)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 风格列表兜底（端点异常时前端仍可发起，key 与后端 mediation_styles 对齐）。 */
    private fun defaultStyles(): List<MediationRoomDto.StyleItem> = listOf(
        MediationRoomDto.StyleItem("gentle_empathy", "温和共情", "先接住情绪再谈事情"),
        MediationRoomDto.StyleItem("rational_review", "理性复盘", "把吵架拆成待解决的问题"),
    )
}
