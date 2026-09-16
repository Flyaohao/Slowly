package com.couple.translator.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody

/**
 * 一帧 SSE 报文：`event: <name>` + `data: <json>`。
 *
 * 注释行（以 `:` 开头的心跳）按协议不属于任何事件，解析器直接丢弃——
 * 服务端每 2 秒会发一帧 `: keep-alive` 防连接被判超时，它不该惊动上层。
 */
data class SseFrame(val event: String, val data: String)

/**
 * SSE 行解析器：逐行喂入，攒满一帧（空行）才吐出。
 *
 * 从 [sseFrames] 里抽出来是**为了能单测**：协议细节（心跳注释必须丢、
 * `data:` 可以多行要攒齐、缺 event 或缺 data 的残帧不能派发）都是踩过坑的地方，
 * 而原先它们焊死在 `ResponseBody` 扩展里，测一次就得起一个真 HTTP 流。
 * 现在核心逻辑是纯函数，JVM 单测直接喂字符串即可。
 *
 * 注意：多行 `data:` 按**直接拼接**处理（不是 SSE 规范里的 `\n` 连接）——
 * 服务端发的是单行 JSON，拼接只是兜底；改成 `\n` 反而会把 JSON 弄坏。
 */
internal class SseLineParser {

    private var eventName: String? = null
    private val dataBuf = StringBuilder()

    /** 喂一行；返回 null 表示还凑不满一帧（或这行按协议该被忽略） */
    fun feed(line: String): SseFrame? {
        when {
            // 空行 = 一帧结束（`data:` 可能多行，要等这一行才派发）
            line.isEmpty() -> {
                val name = eventName
                val frame = if (name != null && dataBuf.isNotEmpty()) {
                    SseFrame(name, dataBuf.toString())
                } else {
                    null
                }
                eventName = null
                dataBuf.setLength(0)
                return frame
            }

            line.startsWith("event:") -> eventName = line.substring(6).trim()
            line.startsWith("data:") -> dataBuf.append(line.substring(5).trim())
            // 其余（含 `:` 开头的心跳注释）按协议忽略
        }
        return null
    }
}

/**
 * 把 okhttp 的流式响应体解析成 SSE 帧流。
 *
 * **为什么不引 EventSource 之类的库**：服务端报文就是极简三行帧
 * （`event:` / `data:` / 空行），自己解析既能保证「收到即 emit」的实时性
 * （库往往还会做一层缓冲），也少一个依赖。okhttp 的 [ResponseBody.source]
 * 本来就是按行读取的，正好对上。
 *
 * 放在 core 是因为它跟业务无关：AI 对话、信件解读、量表分析……只要走 SSE
 * 都是同一套报文格式，各自再抄一份解析器迟早会抄出不一致。
 *
 * **注意**：`Dispatch.IO` 上的 [okio.BufferedSource.readUtf8Line] 是阻塞调用，
 * 协程取消不会立刻打断它。因此调用方要么等流自然结束，要么配合服务端的
 * 取消接口使用（服务端停推 → 读到的行为 null → 循环退出）。
 */
fun ResponseBody.sseFrames(): Flow<SseFrame> = flow {
    val source = source()
    val parser = SseLineParser()

    while (true) {
        val line = source.readUtf8Line() ?: break
        parser.feed(line)?.let { emit(it) }
    }
}.flowOn(Dispatchers.IO)
