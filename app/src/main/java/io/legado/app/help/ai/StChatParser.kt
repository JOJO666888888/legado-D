package io.legado.app.help.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * SillyTavern 聊天存档(.jsonl)解析器。
 *
 * 存档格式(ST 原生,与 sillytavern-gateway 的 data/chats 存档双向互通):
 *   首行:   会话元数据 {"user_name":..,"character_name":..,"create_date":..,"chat_metadata":{..}}
 *   后续行: 一条消息 {"name":..,"is_user":..,"is_system":..,"mes":..,"swipes":[..],"send_date":..}
 *
 * 解析为「轮」结构: 一轮 = 一条用户消息 + 其后连续的非用户消息;
 * 开场白(first_mes)是首轮,没有用户消息。
 */
object StChatParser {

    /** 会话首行元数据 */
    data class StChatMeta(
        val userName: String = "User",
        val charName: String = "",
        val createDate: String = ""
    )

    /** 一条 ST 消息 */
    data class StMessage(
        val name: String,
        val isUser: Boolean,
        val mes: String
    )

    /** 一轮对话: 用户输入(开场白轮为 null) + 其后的角色回复(可能多条) */
    data class StRound(
        val userMessage: StMessage?,
        val charMessages: List<StMessage>
    )

    /** 一场对话(对应一个 jsonl 存档) */
    data class StChat(
        val meta: StChatMeta,
        val rounds: List<StRound>
    )

    /** 解析失败(非存档格式/无角色名/无消息)返回 null,不抛异常 */
    fun parse(bytes: ByteArray): StChat? {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        var userName = "User"
        var charName = ""
        var createDate = ""
        val messages = arrayListOf<StMessage>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val obj = runCatching {
                JsonParser.parseString(line).asJsonObject
            }.getOrNull() ?: continue
            if (charName.isBlank() && obj.has("character_name")) {
                // 首行元数据
                userName = obj.jstr("user_name")?.takeIf { it.isNotBlank() } ?: "User"
                charName = obj.jstr("character_name").orEmpty()
                createDate = obj.jstr("create_date").orEmpty()
                continue
            }
            // 消息行: 跳过系统注释/隐藏消息
            if (obj.optBool("is_system") || obj.optBool("is_hidden")) continue
            val mes = obj.jstr("mes") ?: continue
            if (mes.isBlank()) continue
            val isUser = obj.optBool("is_user")
            val name = obj.jstr("name")?.takeIf { it.isNotBlank() }
                ?: if (isUser) userName else charName
            messages.add(StMessage(name = name, isUser = isUser, mes = mes))
        }
        if (charName.isBlank()) {
            // 旧版存档无首行元数据时,以第一条角色消息的 name 兜底识别角色
            charName = messages.firstOrNull { !it.isUser }?.name.orEmpty()
            if (charName.isBlank()) return null
        }
        if (messages.isEmpty()) return null
        return StChat(
            meta = StChatMeta(userName = userName, charName = charName, createDate = createDate),
            rounds = groupRounds(messages)
        )
    }

    private fun groupRounds(messages: List<StMessage>): List<StRound> {
        val rounds = arrayListOf<StRound>()
        var user: StMessage? = null
        val replies = arrayListOf<StMessage>()

        fun flush() {
            if (user == null && replies.isEmpty()) return
            rounds.add(StRound(userMessage = user, charMessages = replies.toList()))
            user = null
            replies.clear()
        }

        for (msg in messages) {
            if (msg.isUser) {
                flush()
                user = msg
            } else {
                replies.add(msg)
            }
        }
        flush()
        return rounds
    }

    /* ------------------------------ 小工具 ------------------------------ */

    private fun JsonObject.optBool(key: String): Boolean {
        val e = get(key) ?: return false
        return e.isJsonPrimitive && runCatching { e.asBoolean }.getOrDefault(false)
    }

    private fun JsonObject.jstr(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive && !e.isJsonNull) e.asString else null
    }
}
