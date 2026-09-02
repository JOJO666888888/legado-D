package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.help.http.newCallResponseBody
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
import java.io.IOException
import kotlin.math.min

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
 * 一期增强(M3):
 *   - thinking 分流:识别 delta.reasoning_content / delta.reasoning / delta.thinking(DeepSeek/豆包/Claude),
 *     排队成 [StreamEvent.Reasoning] 事件,先于 content 抛给 UI(折叠展示);
 *   - 错误体解析:非 2xx 响应解析 {error:{message}} / {message},取前 500 字符兜底;
 *   - 工具调用:streamChat 支持 tools/tool_choice=auto,流式拼接 delta.tool_calls 增量,
 *     随 Done 事件一并返回完整 toolCalls;
 *   - 方言:openai(默认) / claude(Anthropic Messages 最小差异:x-api-key + anthropic-version + Messages 报文体);
 *   - 非流式 embedding:embedTexts 调 {base}/embeddings。
 *
 * 取消语义:return 的 Flow 取消 → okhttp Call cancel(),保证拆书"停止生成"不写半成品。
 *
 * 事件解析(OpenAI/Claude 单条事件 → 流式事件)与错误体提取已抽至 [SseEventParser],
 * 保持单一实现便于 JVM 单测。
 */
object SseStreamClient {

    const val DIALECT_OPENAI = "openai"
    const val DIALECT_CLAUDE = "claude"

    /** 结构化对话消息(OpenAI Messages / Anthropic system+messages 的双引擎通用表示) */
    data class ChatMessage(
        val role: String,
        val content: String,
        /** role=tool 时必填:关联 assistant 的 tool_call_id */
        val toolCallId: String = "",
        /** role=assistant 且该消息声明了工具调用时非空(OpenAI tool_calls JSON 数组) */
        val toolCalls: JsonArray? = null
    )

    /** 流式工具调用增量拼接结果(与 OpenAI delta.tool_calls 结构对齐) */
    data class AiToolCallDelta(
        val index: Int,
        val id: String,
        val name: String,
        val arguments: String
    )

    /** 流式事件:思考增量 / 正文增量 / 最终完成(含工具调用) / 错误 */
    sealed class StreamEvent {
        data class Reasoning(val text: String) : StreamEvent()
        data class Delta(val chunk: String) : StreamEvent()
        data class Done(
            val fullContent: String,
            val finishReason: String?,
            val toolCalls: List<AiToolCallDelta> = emptyList()
        ) : StreamEvent()
        data class Error(val t: Throwable) : StreamEvent()
    }

    /**
     * 发起流式对话请求,返回 Flow<StreamEvent>。
     *
     * @param system   system prompt
     * @param user     最新一条 user prompt(可以为 "", 只传 history)
     * @param messages 历史 (role, content[, toolCallId[/toolCalls]])
     * @param jsonMode true 时加 response_format=json_object(拆书结构化输出用)
     * @param tools    工具声明(OpenAI function calling;claude 方言映射为 Anthropic tools)
     * @param dialect  openai(默认) / claude
     */
    fun streamChat(
        system: String,
        user: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage> = emptyList(),
        jsonMode: Boolean = false,
        temperature: Double = 0.2,
        tools: List<AiToolSpec>? = null,
        dialect: String = DIALECT_OPENAI
    ): Flow<StreamEvent> = callbackFlow {
        val url = normalizeUrl(baseUrl) + if (dialect == DIALECT_CLAUDE) "/v1/messages" else "/chat/completions"
        val payload = if (dialect == DIALECT_CLAUDE) {
            buildClaudePayload(system, model, messages, user, temperature, tools)
        } else {
            buildOpenAiPayload(system, user, model, jsonMode, messages, temperature, tools, stream = true)
        }
        val requestBuilder = Request.Builder().apply {
            url(url)
            if (dialect == DIALECT_CLAUDE) {
                addHeader("x-api-key", apiKey)
                addHeader("anthropic-version", "2023-06-01")
            } else {
                addHeader("Authorization", "Bearer $apiKey")
            }
            addHeader("Content-Type", "application/json")
            addHeader("Accept", "text/event-stream")
            postJson(payload)
        }
        val call = okHttpClient.newCall(requestBuilder.build())
        val full = StringBuilder(4_096)
        val thinking = StringBuilder(1_024)
        val toolBuilders = HashMap<Int, SseEventParser.ToolBuild>()
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
                    val readable = SseEventParser.extractErrorMessage(errTxt) ?: errTxt.take(500)
                    trySend(StreamEvent.Error(IOException("HTTP ${response.code} $readable")))
                    channel.close()
                    return
                }
                try {
                    val source = body.source()
                    visitLines(source, buffer) { line ->
                        if (cancelled) return@visitLines
                        when {
                            line.startsWith("data:") -> {
                                val data = line.substring(5).trim()
                                if (!data.equals("[DONE]", ignoreCase = true)) {
                                    if (dialect == DIALECT_CLAUDE) {
                                        SseEventParser.dispatchClaudeEvent(data, full, thinking, toolBuilders) { ev -> trySend(ev) }
                                    } else {
                                        SseEventParser.dispatchOpenAiEvent(data, full, thinking, toolBuilders) { ev -> trySend(ev) }
                                    }
                                }
                            }
                            line.startsWith('{') || line.startsWith('[') -> {
                                if (dialect == DIALECT_CLAUDE) {
                                    SseEventParser.dispatchClaudeEvent(line, full, thinking, toolBuilders) { ev -> trySend(ev) }
                                } else {
                                    SseEventParser.dispatchOpenAiEvent(line, full, thinking, toolBuilders) { ev -> trySend(ev) }
                                }
                            }
                        }
                    }
                    val finished = full.toString()
                    val toolCalls = toolBuilders.values
                        .sortedBy { it.index }
                        .map { it.toDelta() }
                    trySend(StreamEvent.Done(finished, "stop", toolCalls))
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

    /**
     * 非流式 Embedding:POST {base}/embeddings,RETURNS data[].embedding(float[])。
     * texts 空 / 网络错误抛异常,由调用方(EmbeddingPipeline)静默降级。
     */
    suspend fun embedTexts(texts: List<String>, baseUrl: String, apiKey: String, model: String): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val url = normalizeUrl(baseUrl) + "/embeddings"
        val root = JsonObject()
        root.addProperty("model", model)
        val input = JsonArray()
        texts.forEach { input.add(it) }
        root.add("input", input)
        val respBody = okHttpClient.newCallResponseBody {
            url(url)
            addHeader("Authorization", "Bearer $apiKey")
            addHeader("Content-Type", "application/json")
            postJson(GSON.toJson(root))
        }
        val text = respBody.string()
        val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
        if (obj.has("error")) {
            val msg = SseEventParser.extractErrorMessage(text) ?: "embedding 接口报错"
            throw IOException(msg)
        }
        val data = obj.getAsJsonArray("data") ?: throw IOException("embedding 响应缺少 data")
        val out = mutableListOf<FloatArray>()
        data.forEach { el ->
            if (el.isJsonObject) {
                val arr = el.asJsonObject.getAsJsonArray("embedding") ?: return@forEach
                val floats = FloatArray(arr.size()) { i -> arr.get(i).asFloat }
                out.add(floats)
            }
        }
        if (out.isEmpty()) throw IOException("embedding 响应为空")
        if (out.size != texts.size) throw IOException("embedding 数量不匹配:期望 ${texts.size} 实际 ${out.size}")
        return out
    }

    /* ------------------------------ 内部工具 ------------------------------ */

    private fun normalizeUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').ifBlank {
            throw IllegalArgumentException("未配置 AI 服务地址")
        }

    private fun buildOpenAiPayload(
        system: String,
        user: String,
        model: String,
        jsonMode: Boolean,
        extraMessages: List<ChatMessage>,
        temperature: Double,
        tools: List<AiToolSpec>?,
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
        extraMessages.forEach { m ->
            arr.add(toOpenAiMessage(m))
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
        tools?.takeIf { it.isNotEmpty() }?.let {
            val toolsArr = JsonArray()
            it.forEach { spec ->
                toolsArr.add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", spec.name)
                        addProperty("description", spec.description)
                        add("parameters", spec.parameters)
                    })
                })
            }
            root.add("tools", toolsArr)
            root.addProperty("tool_choice", "auto")
        }
        return GSON.toJson(root)
    }

    /** Anthropic Messages 最小差异报文体(openai 消息映射为 claude role 语义) */
    private fun buildClaudePayload(
        system: String,
        model: String,
        extraMessages: List<ChatMessage>,
        user: String,
        temperature: Double,
        tools: List<AiToolSpec>?
    ): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("max_tokens", 4096)
        root.addProperty("temperature", temperature)
        root.addProperty("stream", true)
        if (system.isNotBlank()) root.addProperty("system", system)
        val arr = JsonArray()
        extraMessages.forEach { m ->
            val om = JsonObject()
            val role = when (m.role) {
                "tool" -> "user"
                else -> m.role
            }
            om.addProperty("role", role)
            if (m.toolCallId.isNotBlank()) {
                // claude 不区分 tool 角色:以 user 携带 tool_result + tool_use_id
                om.add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", m.toolCallId)
                        addProperty("content", m.content)
                    })
                })
            } else if (m.toolCalls != null && m.toolCalls.size() > 0) {
                val contentArr = JsonArray()
                m.toolCalls.forEach { tc ->
                    val toolCall = tc.asJsonObject
                    contentArr.add(JsonObject().apply {
                        addProperty("type", "tool_use")
                        addProperty("id", toolCall.jstr("id"))
                        addProperty("name", toolCall.asJsonObject("function")?.jstr("name").orEmpty())
                        add("input", toolCall.asJsonObject("function")?.get("arguments")?.let { parseJsonLoose(it.asString) } ?: JsonObject())
                    })
                }
                om.add("content", contentArr)
            } else {
                om.addProperty("content", m.content)
            }
            arr.add(om)
        }
        if (user.isNotBlank()) {
            arr.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", user)
            })
        }
        root.add("messages", arr)
        tools?.takeIf { it.isNotEmpty() }?.let {
            val toolsArr = JsonArray()
            it.forEach { spec ->
                toolsArr.add(JsonObject().apply {
                    addProperty("name", spec.name)
                    addProperty("description", spec.description)
                    add("input_schema", spec.parameters)
                })
            }
            root.add("tools", toolsArr)
        }
        return root.toString()
    }

    private fun toOpenAiMessage(m: ChatMessage): JsonObject = JsonObject().apply {
        addProperty("role", m.role)
        addProperty("content", m.content)
        if (m.role == "tool") {
            addProperty("tool_call_id", m.toolCallId)
        }
        if (m.toolCalls != null) {
            add("tool_calls", m.toolCalls)
        }
    }

    private fun parseJsonLoose(s: String): com.google.gson.JsonElement? {
        if (s.isBlank()) return null
        return kotlin.runCatching {
            if (s.trimStart().startsWith("{")) {
                com.google.gson.JsonParser.parseString(s)
            } else null
        }.getOrNull()
    }

    private inline fun visitLines(source: okio.BufferedSource, buffer: StringBuilder, visit: (String) -> Unit) {
        val buf = okio.Buffer()
        while (!source.exhausted()) {
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
                if (line.isNotEmpty()) visit(line)
            }
        }
    }
}