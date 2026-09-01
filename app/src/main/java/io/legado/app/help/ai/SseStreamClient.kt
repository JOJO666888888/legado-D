package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import java.io.IOException

/**
 * 流式 SSE Chat Completion 客户端(复用 okHttpClient + POST JSON body)。
 *
 * 标准 SSE 协议:
 *   data: {"choices":[{"delta":{"content":"字"},"finish_reason":null,...}]}\n\n
 *   data: [DONE]\n\n
 *
 * 兼容性处理(参考 BreakdownAiRunner 先例):
 *   - 部分网关返回 content 直接在根对象 delta->content 之外多包一层 message;
 *   - 部分网关走非 SSE 的 "NDJSON" 行(每行一个完整 JSON),我们把行当作一次 event 解析;
 *   - 非流式响应(普通 /chat/completions) 会退化为一次性下发完整事件(StreamEvent.Done with content)。
 *
 * 取消语义:return 的 Flow 取消 → okhttp Call cancel(),保证拆书"停止生成"不写半成品。
 */
object SseStreamClient {

    /** 流式事件:增量 delta(用于气泡滚动拼接) / 最终完成 / 错误 */
    sealed class StreamEvent {
        data class Delta(val chunk: String) : StreamEvent()
        data class Done(val fullContent: String, val finishReason: String?) : StreamEvent()
        data class Error(val t: Throwable) : StreamEvent()
    }

    /**
     * 发起流式对话请求,返回 Flow<StreamEvent>。
     *
     * @param system   system prompt
     * @param user     最新一条 user prompt(可以为 "", 只传 history)
     * @param messages 历史 [(role, content)]
     * @param jsonMode true 时加 response_format=json_object(拆书结构化输出用)
     */
    fun streamChat(
        system: String,
        user: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>> = emptyList(),
        jsonMode: Boolean = false,
        temperature: Double = 0.2
    ): Flow<StreamEvent> = callbackFlow {
        val url = normalizeUrl(baseUrl) + "/chat/completions"
        val payload = buildPayload(system, user, model, jsonMode, messages, temperature, stream = true)
        val requestBuilder = Request.Builder().apply {
            url(url)
            addHeader("Authorization", "Bearer $apiKey")
            addHeader("Content-Type", "application/json")
            addHeader("Accept", "text/event-stream")
            postJson(payload)
        }
        val call = okHttpClient.newCall(requestBuilder.build())
        val full = StringBuilder(4_096)
        val buffer = StringBuilder(1_024)
        var cancelled = false
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cancelled) return
                trySend(StreamEvent.Error(e))
                channel.close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (cancelled) return
                val body = response.body
                if (body == null) {
                    trySend(StreamEvent.Error(IOException("empty response body")))
                    channel.close()
                    return
                }
                if (!response.isSuccessful) {
                    val errTxt = kotlin.runCatching { body.string() }.getOrDefault("")
                    trySend(StreamEvent.Error(IOException("HTTP ${response.code} ${errTxt.take(500)}")))
                    channel.close()
                    return
                }
                try {
                    val source = body.source()
                    val buf = okio.Buffer()
                    while (!source.exhausted()) {
                        if (cancelled) return
                        val read = source.read(buf, 8192L)
                        if (read <= 0L) break
                        val chunk = buf.readUtf8()
                        buffer.append(chunk)
                        // 按 '\n'/'\r' 切分 event,完整事件立即分派
                        while (true) {
                            val nl = buffer.indexOf('\n')
                            val cr = buffer.indexOf('\r')
                            val idx = when {
                                nl < 0 && cr < 0 -> -1
                                nl < 0 -> cr
                                cr < 0 -> nl
                                else -> minOf(nl, cr)
                            }
                            if (idx < 0) break
                            val line = buffer.substring(0, idx).trimEnd()
                            var skip = idx + 1
                            while (skip < buffer.length && (buffer[skip] == '\n' || buffer[skip] == '\r')) skip++
                            buffer.delete(0, skip)
                            when {
                                line.startsWith("data:") -> {
                                    val data = line.substring(5).trim()
                                    if (!data.equals("[DONE]", ignoreCase = true)) {
                                        dispatchEvent(data, full) { ev -> trySend(ev) }
                                    }
                                }
                                line.startsWith('{') || line.startsWith('[') -> {
                                    dispatchEvent(line, full) { ev -> trySend(ev) }
                                }
                            }
                        }
                    }
                    val finished = full.toString()
                    trySend(StreamEvent.Done(finished, "stop"))
                    channel.close()
                } catch (t: Throwable) {
                    if (cancelled) return
                    trySend(StreamEvent.Error(t))
                    channel.close()
                } finally {
                    kotlin.runCatching { body.close() }
                }
            }
        })
        awaitClose {
            cancelled = true
            kotlin.runCatching { call.cancel() }
        }
    }.flowOn(Dispatchers.IO)

    /* ------------------------------ 内部工具 ------------------------------ */

    private fun normalizeUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').ifBlank {
            throw IllegalArgumentException("未配置 AI 服务地址")
        }

    private fun buildPayload(
        system: String,
        user: String,
        model: String,
        jsonMode: Boolean,
        extraMessages: List<Pair<String, String>>,
        temperature: Double,
        stream: Boolean
    ): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("temperature", temperature)
        root.addProperty("stream", stream)
        val arr = JsonArray()
        if (system.isNotBlank()) {
            arr.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", system)
            })
        }
        extraMessages.forEach { (role, content) ->
            arr.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            })
        }
        if (user.isNotBlank()) {
            arr.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", user)
            })
        }
        root.add("messages", arr)
        if (jsonMode) {
            root.add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }
        return GSON.toJson(root)
    }

    /**
     * 解析单个 data 载荷,兼容:
     *  {"choices":[{"delta":{"content":"..."}}]}
     *  {"choices":[{"message":{"content":"..."}}]} (非流式退化场景)
     *  {"content":"..."} (部分网关直接)
     */
    private fun dispatchEvent(data: String, full: StringBuilder, emit: (StreamEvent) -> Unit) {
        if (data.isBlank()) return
        val o = kotlin.runCatching {
            com.google.gson.JsonParser.parseString(data).asJsonObject
        }.getOrNull() ?: return
        val direct = o.get("content")
        if (direct != null && !direct.isJsonNull && direct.isJsonPrimitive) {
            val s = direct.asString.orEmpty()
            if (s.isNotEmpty()) {
                full.append(s)
                emit(StreamEvent.Delta(s))
            }
        }
        val choices = o.getAsJsonArray("choices") ?: return
        for (el in choices) {
            if (el == null || !el.isJsonObject) continue
            val c = el.asJsonObject
            val delta = c.getAsJsonObject("delta")
            val message = c.getAsJsonObject("message")
            val contentStr = delta?.get("content")?.takeIf { !it.isJsonNull }?.asString
                ?: message?.get("content")?.takeIf { !it.isJsonNull }?.asString
                ?: continue
            if (contentStr.isNotEmpty()) {
                full.append(contentStr)
                emit(StreamEvent.Delta(contentStr))
            }
        }
    }
}
