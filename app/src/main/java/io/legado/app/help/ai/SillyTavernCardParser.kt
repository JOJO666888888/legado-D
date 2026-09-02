package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.utils.GSON
import java.util.zip.Inflater

/**
 * SillyTavern 角色卡解析器(M10,MoRead 移植)。
 *
 * 支持:
 *   - PNG 内嵌卡:遍历 PNG chunk 找 tEXt/zTXt/iTXt 里 keyword == "chara"(V1/V2)或 "ccv3"(V3),
 *     value 为 base64 字符串 → JSON;
 *   - JSON 卡(V1 顶层字段 / V2/V3 带 data 对象 + spec);
 *   - 世界书(character_book / world):enabled 与 constant 条目打包进 config;
 *   - `{{char}}/{{user}}` 宏替换;
 *   - 解析失败返回 null(导入入口 toast 提示,不崩溃)。
 *
 * 生成规则(与「拆书模板内置为 skill」路线一致,零新表):
 *   systemPrompt = description + personality + scenario 合并 + greeting/示例对话提示
 *   config       = 世界书启用条目列表 JSON
 */
object SillyTavernCardParser {

    /** 角色卡导入的产物 */
    data class ParsedCard(
        val name: String,
        val systemPrompt: String,
        /** 世界书启用条目列表 JSON(keys/content/constant) */
        val configJson: String
    )

    fun parse(data: ByteArray): ParsedCard? {
        val json = when {
            isPng(data) -> extractFromPng(data)?.let(::extractJsonBytes)
            else -> extractJsonBytes(data)
        } ?: return null
        return parseCard(json)
    }

    /* ------------------------------ PNG chunk 提取 ------------------------------ */

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun isPng(data: ByteArray): Boolean =
        data.size >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_SIG)

    /** 从 PNG 提取 "chara"/"ccv3" 文本块的值(base64) */
    private fun extractFromPng(data: ByteArray): ByteArray? {
        var pos = 8
        while (pos + 8 <= data.size) {
            val len = readIntBE(data, pos)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") break
            val start = pos + 8
            val end = start + len
            if (end > data.size) break
            if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                var ki = start
                while (ki < end && data[ki] != 0.toByte()) ki++
                val keyword = String(data, start, ki - start, Charsets.UTF_8)
                if (keyword == "chara" || keyword == "ccv3") {
                    val textStart = ki.coerceAtMost(end)
                    return when (type) {
                        "zTXt" -> inflate(data.copyOfRange(textStart + 1, end))
                        "iTXt" -> parseITxt(data, textStart, end)
                        else -> data.copyOfRange(textStart, end)
                    }
                }
            }
            pos = end + 4 // CRC
        }
        return null
    }

    private fun parseITxt(data: ByteArray, textStart: Int, end: Int): ByteArray? {
        // iTXt layout: keyword\0 compressionFlag(1) compressionMethod(1) languageTag\0 translatedKeyword\0 text
        var p = textStart
        if (p >= end) return null
        val compressionFlag = data[p].toInt() and 0xFF
        p++
        if (p >= end) return null
        // language tag
        while (p < end && data[p] != 0.toByte()) p++
        p++
        while (p < end && data[p] != 0.toByte()) p++
        p++
        if (p >= end) return null
        val raw = data.copyOfRange(p, end)
        return if (compressionFlag == 1) inflate(raw, true) else raw
    }

    private fun inflate(data: ByteArray, skipMethodByte0: Boolean = false): ByteArray? {
        return kotlin.runCatching {
            val src = if (skipMethodByte0 && data.isNotEmpty()) data.copyOfRange(1, data.size) else data
            val inflater = Inflater()
            inflater.setInput(src)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        }.getOrNull()
    }

    /* ------------------------------ JSON 提取/解析 ------------------------------ */

    /** 文本块内容可能是 JSON 原文或 base64 字符串(ccv3 规范 base64) */
    private fun extractJsonBytes(bytes: ByteArray): ByteArray? {
        val trim = bytes.toString(Charsets.UTF_8).trim()
        if (trim.startsWith("{") || trim.startsWith("[")) return bytes
        return kotlin.runCatching {
            android.util.Base64.decode(trim, android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    private fun parseCard(json: ByteArray): ParsedCard? {
        val root = kotlin.runCatching {
            com.google.gson.JsonParser.parseString(json.toString(Charsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return null
        val data = root.getAsJsonObject("data") ?: root
        val name = data.jstr("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val description = data.jstr("description").orEmpty()
        val personality = data.jstr("personality").orEmpty()
        val scenario = data.jstr("scenario").orEmpty()
        val firstMes = data.jstr("first_mes").orEmpty()
        val mesExample = data.jstr("mes_example").orEmpty()

        val sb = StringBuilder()
        if (description.isNotBlank()) {
            sb.append("【角色形象】\n").append(description).append("\n\n")
        }
        if (personality.isNotBlank()) {
            sb.append("【性格】\n").append(personality).append("\n\n")
        }
        if (scenario.isNotBlank()) {
            sb.append("【场景】\n").append(scenario).append("\n\n")
        }
        if (firstMes.isNotBlank()) {
            sb.append("【开场白(Greeting)】\n扮演该角色,以这个开场白开始对话(可微调):\n")
                .append(firstMes).append("\n\n")
        }
        if (mesExample.isNotBlank()) {
            sb.append("【示例对话(供理解口吻与用语习惯,不要逐字照抄)】\n")
                .append(mesExample).append("\n\n")
        }
        // 宏替换
        val prompt = macroReplace(sb.toString().trim(), name)
        val worldConfig = buildWorldConfig(data)
        return ParsedCard(name = name, systemPrompt = prompt, configJson = worldConfig)
    }

    /** 世界书:收集 enabled 条目(默认启用),constant 条目标记「常驻」 */
    private fun buildWorldConfig(data: JsonObject): String {
        val book = data.getAsJsonObject("character_book")
            ?: data.getAsJsonObject("world")
            ?: data.getAsJsonObject("world_book")
        if (book == null) return "[]"
        val entries = book.getAsJsonArray("entries") ?: JsonArray()
        val out = JsonArray()
        entries.forEach { e ->
            if (e == null || !e.isJsonObject) return@forEach
            val o = e.asJsonObject
            if (o.has("enabled") && !o.get("enabled").asBoolean) return@forEach
            val keys = o.getAsJsonArray("keys")?.mapNotNull { it.takeIf { s -> s != null && s.isJsonPrimitive }?.asString }
                ?: emptyList()
            val content = o.jstr("content").orEmpty()
            val constant = o.has("constant") && o.get("constant").asBoolean
            if (content.isBlank()) return@forEach
            out.add(JsonObject().apply {
                add("keys", GSON.toJsonTree(keys.take(8)))
                addProperty("content", content)
                addProperty("constant", constant)
            })
        }
        return GSON.toJson(out)
    }

    private fun macroReplace(text: String, charName: String): String =
        text.replace("{{char}}", charName)
            .replace("{{Char}}", charName)
            .replace("{{CHAR}}", charName)
            .replace("{{user}}", "用户")
            .replace("{{User}}", "用户")

    /* ------------------------------ 小工具 ------------------------------ */

    private fun readIntBE(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)

    private fun JsonObject.jstr(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive && !e.isJsonNull) e.asString else null
    }
}