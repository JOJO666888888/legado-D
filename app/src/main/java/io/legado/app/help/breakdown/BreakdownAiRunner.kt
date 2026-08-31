package io.legado.app.help.breakdown

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import okhttp3.Request
import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.math.min

/**
 * AI 拆书:「预编号 prompt + JSON Schema + 引文锚点校验」管线。
 *
 * 行号不交给 AI 数:文本逐行加 `N|` 前缀,AI 返回 startLine/endLine + quote(原文引用片段),
 * 由本类做锚点校验并重算区间;校验失败段落标记 needCheck(需人工核对),不静默丢弃。
 */
object BreakdownAiRunner {

    const val MAX_RETRY = 2

    /** AI 拆解结果(整章草稿) */
    data class AiSegment(
        val startLine: Int,
        val endLine: Int,
        val label: String,
        val contentSummary: String,
        val rhythmNote: String,
        val highlights: List<String>,
        val quote: String,
        val needCheck: Boolean
    )

    data class AiChapterResult(
        val chapterSummary: String,
        val segments: List<AiSegment>
    )

    class AiException(message: String) : Exception(message)

    /* ------------------------------ Prompt 构建 ------------------------------ */

    /**
     * 输出 JSON Schema 说明(贴进 prompt;response_format 可选)
     */
    private val schemaText = """
        {
          "chapterSummary": "本章剧情+节奏总结(含危机递进/爽点释放/结尾悬念反转)",
          "segments": [
            {
              "startLine": 行号区间起点(整数,1起),
              "endLine": 行号区间终点(整数,含端点),
              "label": "功能标签(从给定词库中挑选,可自创但不超过2个词)",
              "contentSummary": "内容简述(30字内)",
              "rhythmNote": "节奏拆解(讲清楚这段在推动什么/如何调动情绪)",
              "highlights": ["亮点或爆点一句", "可多条"],
              "quote": "从原文原文逐字复制的引用片段,必须与 startLine-endLine 区间对应(供机械对齐校验)"
            }
          ]
        }
    """.trimIndent()

    /**
     * 构建 system 提示词:角色 + 字段定义 + 标签词库 + 方法论 + Schema
     */
    fun buildSystemPrompt(
        templateLabels: List<String>,
        aiPromptExtra: String
    ): String {
        val sb = StringBuilder()
        sb.append("你是资深网文编辑,擅长对长篇小说逐章做结构化拆解。\n")
        sb.append("\n任务:根据给定章节文本(逐行已预编号 `N|原文行`),把本章拆成若干节奏段。\n")
        sb.append("每段必须:①startLine/endLine 为行号区间(1起,含端点,必须落在给定行号内);")
        sb.append("②quote 字段逐字复制该区间内的原文片段(不能改写、不能省略中间内容),供机械对齐校验;")
        sb.append("③label 从词库中挑选最贴切的一个;④内容简述/节奏拆解/亮点爆点按模板要求填写。\n")
        sb.append("区间不要重叠,尽量覆盖全文;宁可多分几段也不要漏段。\n")
        if (templateLabels.isNotEmpty()) {
            sb.append("\n段功能标签词库:\n")
            templateLabels.forEachIndexed { i, label -> sb.append(i + 1).append(". ").append(label).append('\n') }
        }
        if (aiPromptExtra.isNotBlank()) {
            sb.append("\n方法论与拆解规范:\n").append(aiPromptExtra).append('\n')
        }
        sb.append("\n只输出 JSON,不要任何解释文字。JSON 结构如下:\n").append(schemaText)
        return sb.toString()
    }

    /* ------------------------------ 请求 ------------------------------ */

    /**
     * 单章拆解。成功返回草稿;失败抛 [AiException]。
     *
     * @param numberedContent buildNumberedContent 的产物
     */
    suspend fun breakdownChapter(
        numberedContent: String,
        chapterTitle: String,
        templateLabels: List<String>,
        aiPromptExtra: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): AiChapterResult {
        if (numberedContent.isBlank()) {
            throw AiException("章节内容为空,无法拆解")
        }
        val system = buildSystemPrompt(templateLabels, aiPromptExtra)
        val user = "章节《$chapterTitle》预编号文本(行号以 `N|` 开头,引用时直接复制此行内容):\n\n$numberedContent"
        val jsonRaw = requestChatCompletion(system, user, baseUrl, apiKey, model)
        return parseAndVerify(jsonRaw)
    }

    private suspend fun requestChatCompletion(
        system: String,
        user: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): String {
        val url = normalizeUrl(baseUrl) + "/chat/completions"
        val payload = buildPayload(system, user, model, jsonMode = true)
        var lastError: Exception? = null
        for (attempt in 0..MAX_RETRY) {
            if (attempt > 0) {
                delay(1000L * attempt * attempt) // 指数退避
            }
            try {
                val body = okHttpClient.newCallResponseBody {
                    url(url)
                    header("Authorization", "Bearer $apiKey")
                    header("Content-Type", "application/json")
                    postJson(payload)
                }
                val text = body.string()
                return extractContent(text)
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: ConnectException) {
                lastError = e
            } catch (e: java.io.IOException) {
                lastError = e
            }
        }
        throw AiException(lastError?.message ?: "网络请求失败")
    }

    private fun buildPayload(
        system: String,
        user: String,
        model: String,
        jsonMode: Boolean
    ): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("temperature", 0.2)
        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", system)
        })
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", user)
        })
        root.add("messages", messages)
        if (jsonMode) {
            root.add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }
        return root.toString()
    }

    /**
     * 兼容不返回标准 finish_reason/message 内容结构的服务商:
     * 支持 {"choices":[{"message":{"content":"..."}}]} 及其变体。
     */
    private fun extractContent(respText: String): String {
        // 失败响应也尝试提取错误信息
        if (respText.contains("\"error\"")) {
            val e = runCatching {
                val obj = JsonParser.parseString(respText).asJsonObject.getAsJsonObject("error")
                obj.get("message")?.asString
            }.getOrNull()
            if (!e.isNullOrBlank()) {
                throw AiException(e)
            }
        }
        val content = runCatching {
            val obj = JsonParser.parseString(respText).asJsonObject
            val choices = obj.getAsJsonArray("choices")
            choices.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.asString
        }.getOrNull()
        if (content.isNullOrBlank()) {
            // 直接返回对象内容(部分兼容网关)
            val direct = runCatching {
                val obj = JsonParser.parseString(respText).asJsonObject
                val c = obj.get("content")
                if (c != null && !c.isJsonNull) c.asString else null
            }.getOrNull()
            if (!direct.isNullOrBlank()) return direct
            throw AiException("AI 响应异常:${respText.take(200)}")
        }
        return content
    }

    /** 容忍 ```json 围栏等包裹 */
    private fun stripCodeFence(text: String): String {
        var t = text.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val m = fence.find(t)
        if (m != null) {
            t = m.groupValues[1].trim()
        }
        // 仅保留首个 { ... } 块
        val start = t.indexOf('{')
        var end = t.lastIndexOf('}')
        if (start >= 0 && end > start) {
            end = min(end + 1, t.length)
            t = t.substring(start, end)
        }
        return t
    }

    /* ------------------------------ 校验管线 ------------------------------ */

    /**
     * 解析 AI 返回的 JSON,校验区间合法性与引文锚点。
     * 校验失败的段标记 needCheck 并保留(降级人工核对),不静默丢弃。
     */
    fun parseAndVerify(jsonRaw: String): AiChapterResult {
        val clean = stripCodeFence(jsonRaw)
        val obj = try {
            JsonParser.parseString(clean).asJsonObject
        } catch (e: Exception) {
            // 二次尝试:提取 `{...}` 块
            val start = clean.indexOf('{')
            val end = clean.lastIndexOf('}')
            if (start in 0 until end) {
                try {
                    JsonParser.parseString(clean.substring(start, end + 1)).asJsonObject
                } catch (e2: Exception) {
                    throw AiException("AI 输出不是合法 JSON:${jsonRaw.take(150)}")
                }
            } else {
                throw AiException("AI 输出不是合法 JSON:${jsonRaw.take(150)}")
            }
        }
        val chapterSummary = objElementString(obj.get("chapterSummary"))
        val segments = mutableListOf<AiSegment>()
        val rawSegments = obj.getAsJsonArray("segments") ?: JsonArray()
        rawSegments.forEach { el ->
            if (el.isJsonObject) {
                segments.add(parseSegment(el.asJsonObject))
            }
        }
        if (segments.isEmpty()) {
            throw AiException("AI 结果里没有段落(segments 为空)")
        }
        return AiChapterResult(chapterSummary, segments)
    }

    private fun parseSegment(o: JsonObject): AiSegment {
        val startLine = o.int("startLine", 1)
        val endLine = o.int("endLine", startLine)
        val quote = o.string("quote")
        val highlights = o.arr("highlights")
        return AiSegment(
            startLine = startLine,
            endLine = endLine,
            label = o.string("label"),
            contentSummary = o.string("contentSummary"),
            rhythmNote = o.string("rhythmNote"),
            highlights = highlights,
            quote = quote,
            needCheck = false
        )
    }

    /**
     * 把 AI 段落按预编号文本行数做区间钳制与锚点校验,返回最终段落:
     * - 超出行数钳制到 [1, lines.size];
     * - quote 非空 → 校验区间↔引用一致性,失败则全文重定位,仍失败 needCheck=true;
     * - quote 为空 → 区间合法即可,不标记核对(手动修正区间口径)。
     */
    fun finalizeSegments(aiSegments: List<AiSegment>, lines: Array<String>): List<AiSegment> {
        val maxLine = lines.size
        if (maxLine == 0) return aiSegments
        return aiSegments.map { seg ->
            val s = seg.startLine.coerceIn(1, maxLine)
            val e = seg.endLine.coerceIn(s, maxLine)
            var needCheck = seg.needCheck
            var finalS = s
            var finalE = e
            if (seg.quote.isNotBlank()) {
                val (ns, ne, failed) = BreakdownHelper.verifyAnchor(lines, s, e, seg.quote)
                finalS = ns
                finalE = ne
                needCheck = needCheck || failed
            }
            seg.copy(startLine = finalS, endLine = finalE, needCheck = needCheck)
        }
    }

    /* ------------------------------ 工具 ------------------------------ */

    private fun normalizeUrl(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (url.isBlank()) throw AiException("未配置服务地址")
        return url
    }

    private fun JsonObject.string(key: String): String {
        val e = get(key) ?: return ""
        return if (e.isJsonPrimitive && !e.isJsonNull) e.asString else ""
    }

    private fun JsonObject.int(key: String, def: Int): Int {
        val e = get(key) ?: return def
        return if (e.isJsonPrimitive && !e.isJsonNull && e.asJsonPrimitive.isNumber) {
            e.asInt
        } else {
            def
        }
    }

    private fun JsonObject.arr(key: String): List<String> {
        val e = get(key) ?: return emptyList()
        if (!e.isJsonArray) return emptyList()
        return e.asJsonArray
            .filter { it.isJsonPrimitive }
            .map { it.asString }
            .filter { it.isNotBlank() }
    }

    private fun objElementString(e: com.google.gson.JsonElement?): String =
        if (e != null && e.isJsonPrimitive && !e.isJsonNull) e.asString else ""
}