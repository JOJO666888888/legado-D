package io.legado.app.help.ai

import com.google.gson.JsonObject
import java.io.IOException

/**
 * SSE 单条事件 → 流式事件的纯解析器(从 SseStreamClient 抽出,便于 JVM 单测)。
 *
 * 无网络 / 无 Android 依赖;行为与原 SseStreamClient 私有实现完全一致:
 *   - dispatchOpenAiEvent:OpenAI 兼容 content / thinking / tool_calls 增量;
 *   - dispatchClaudeEvent:Anthropic content_block_start / content_block_delta(text/thinking/input_json);
 *   - extractErrorMessage:从非 2xx 响应体提取可读厂商 message。
 */
internal object SseEventParser {

    /** 工具调用增量拼接器(与 OpenAI delta.tool_calls 结构和 Anthropic tool_use 语义对齐) */
    class ToolBuild(val index: Int) {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun toDelta() = SseStreamClient.AiToolCallDelta(index, id, name, arguments.toString())
    }

    /** OpenAI 兼容事件分派:content / thinking / tool_calls 增量 + finish_reason */
    fun dispatchOpenAiEvent(
        data: String,
        full: StringBuilder,
        thinking: StringBuilder,
        toolBuilders: HashMap<Int, ToolBuild>,
        emit: (SseStreamClient.StreamEvent) -> Unit
    ) {
        if (data.isBlank()) return
        val o = kotlin.runCatching {
            com.google.gson.JsonParser.parseString(data).asJsonObject
        }.getOrNull() ?: return
        // 兼容部分网关根对象直接 content/errors
        val direct = o.get("content")
        if (direct != null && !direct.isJsonNull && direct.isJsonPrimitive) {
            val s = direct.asString.orEmpty()
            if (s.isNotEmpty()) {
                full.append(s)
                emit(SseStreamClient.StreamEvent.Delta(s))
            }
        }
        val choices = o.getAsJsonArray("choices") ?: return
        for (el in choices) {
            if (el == null || !el.isJsonObject) continue
            val c = el.asJsonObject
            val delta = c.getAsJsonObject("delta")
            val message = c.getAsJsonObject("message")
            // 思考增量(优先 delta,兜底 message)
            val reasoningStr = delta?.jstr("reasoning_content")
                ?: delta?.jstr("reasoning")
                ?: delta?.jstr("thinking")
                ?: message?.jstr("reasoning_content")
            if (!reasoningStr.isNullOrEmpty() && reasoningStr != "null") {
                thinking.append(reasoningStr)
                emit(SseStreamClient.StreamEvent.Reasoning(reasoningStr))
            }
            val contentStr = delta?.jstr("content")
                ?: message?.jstr("content")
            if (!contentStr.isNullOrEmpty()) {
                full.append(contentStr)
                emit(SseStreamClient.StreamEvent.Delta(contentStr))
            }
            // 工具调用增量(OpenAI delta.tool_calls)
            val toolCalls = delta?.getAsJsonArray("tool_calls") ?: continue
            for (tc in toolCalls) {
                if (tc == null || !tc.isJsonObject) continue
                val obj = tc.asJsonObject
                val index = obj.jint("index") ?: 0
                val build = toolBuilders.getOrPut(index) { ToolBuild(index) }
                obj.jstr("id")?.let { if (it != "null" && build.id.isBlank()) build.id = it }
                obj.getAsJsonObject("function")?.let { fn ->
                    fn.jstr("name")?.let { if (it != "null" && build.name.isBlank()) build.name = it }
                    fn.jstr("arguments")?.let { if (it != "null") build.arguments.append(it) }
                }
            }
        }
    }

    /** Anthropic SSE 事件分派:content_block 增量 */
    fun dispatchClaudeEvent(
        data: String,
        full: StringBuilder,
        thinking: StringBuilder,
        toolBuilders: HashMap<Int, ToolBuild>,
        emit: (SseStreamClient.StreamEvent) -> Unit
    ) {
        if (data.isBlank()) return
        val o = kotlin.runCatching {
            com.google.gson.JsonParser.parseString(data).asJsonObject
        }.getOrNull() ?: return
        val type = o.jstr("type").orEmpty()
        when (type) {
            "error" -> {
                val msg = o.getAsJsonObject("error")?.jstr("message")
                    ?: o.jstr("message") ?: "anthropic 接口报错"
                emit(SseStreamClient.StreamEvent.Error(IOException(msg)))
            }
            "content_block_start" -> {
                val index = o.jint("index") ?: 0
                val block = o.getAsJsonObject("content_block")
                if (block?.jstr("type") == "tool_use") {
                    val build = toolBuilders.getOrPut(index) { ToolBuild(index) }
                    build.id = block.jstr("id").orEmpty()
                    build.name = block.jstr("name").orEmpty()
                }
            }
            "content_block_delta" -> {
                val index = o.jint("index") ?: 0
                val delta = o.getAsJsonObject("delta") ?: return
                when (delta.jstr("type")) {
                    "text_delta" -> {
                        val t = delta.jstr("text").orEmpty()
                        if (t.isNotEmpty()) {
                            full.append(t)
                            emit(SseStreamClient.StreamEvent.Delta(t))
                        }
                    }
                    "thinking_delta" -> {
                        val t = delta.jstr("thinking").orEmpty()
                        if (t.isNotEmpty()) {
                            thinking.append(t)
                            emit(SseStreamClient.StreamEvent.Reasoning(t))
                        }
                    }
                    "input_json_delta" -> {
                        val t = delta.jstr("partial_json").orEmpty()
                        if (t.isNotEmpty()) {
                            val build = toolBuilders.getOrPut(index) { ToolBuild(index) }
                            build.arguments.append(t)
                        }
                    }
                }
            }
        }
    }

    /** 从错误响应体提取可读厂商 message:{error:{message}} / {message} */
    fun extractErrorMessage(text: String): String? {
        if (text.isBlank()) return null
        return kotlin.runCatching {
            val o = com.google.gson.JsonParser.parseString(text).asJsonObject
            val err = o.getAsJsonObject("error")
            err?.jstr("message") ?: o.jstr("message")
        }.getOrNull()
    }
}

/* ------------------------------ GSON 小工具(SseStreamClient 与 SseEventParser 共用) ------------------------------ */

internal fun JsonObject.jstr(key: String): String? {
    val e = get(key) ?: return null
    return if (e.isJsonPrimitive && !e.isJsonNull) e.asString else null
}

internal fun JsonObject.jint(key: String): Int? {
    val e = get(key) ?: return null
    return if (e.isJsonPrimitive && !e.isJsonNull && e.asJsonPrimitive.isNumber) e.asInt else null
}

internal fun JsonObject.asJsonObject(key: String): JsonObject? {
    val e = get(key) ?: return null
    return if (e.isJsonObject) e.asJsonObject else null
}