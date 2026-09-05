package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SillyTavern 聊天存档(.jsonl)解析纯逻辑单测。 */
class StChatParserTest {

    private fun chat(vararg lines: String): ByteArray =
        lines.joinToString("\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `header and rounds parsed`() {
        val bytes = chat(
            """{"user_name":"Alice","character_name":"Seraphina","create_date":"July 19, 2024 3:30pm","chat_metadata":{}}""",
            """{"name":"Seraphina","is_user":false,"is_system":false,"mes":"*她微微一笑* 你好，旅人。","send_date":"July 19, 2024 3:30pm","swipes":["*她微微一笑* 你好，旅人。"],"swipe_id":0}""",
            """{"name":"Alice","is_user":true,"is_system":false,"mes":"你好，请问图书馆怎么走？","send_date":"July 19, 2024 3:31pm"}""",
            """{"name":"Seraphina","is_user":false,"is_system":false,"mes":"跟我来吧，就在钟楼后面。","send_date":"July 19, 2024 3:32pm"}"""
        )
        val chat = mustParse(StChatParser.parse(bytes))
        assertEquals("Seraphina", chat.meta.charName)
        assertEquals("Alice", chat.meta.userName)
        assertEquals(2, chat.rounds.size)
        // 第 1 轮: 开场白(无用户消息)
        assertNull(chat.rounds[0].userMessage)
        assertEquals(1, chat.rounds[0].charMessages.size)
        assertEquals("Seraphina", chat.rounds[0].charMessages[0].name)
        // 第 2 轮: 用户消息 + 角色回复
        assertEquals("你好，请问图书馆怎么走？", chat.rounds[1].userMessage?.mes)
        assertEquals("跟我来吧，就在钟楼后面。", chat.rounds[1].charMessages[0].mes)
    }

    @Test
    fun `system and hidden messages skipped`() {
        val bytes = chat(
            """{"user_name":"Alice","character_name":"Seraphina"}""",
            """{"name":"System","is_user":false,"is_system":true,"mes":"隐藏注释"}""",
            """{"name":"System","is_user":false,"is_hidden":true,"mes":"旁白隐藏"}""",
            """{"name":"Alice","is_user":true,"is_system":false,"mes":"第一句"}""",
            """{"name":"Seraphina","is_user":false,"is_system":false,"mes":"回复"}"""
        )
        val chat = mustParse(StChatParser.parse(bytes))
        assertEquals(1, chat.rounds.size)
        assertEquals("第一句", chat.rounds[0].userMessage?.mes)
    }

    @Test
    fun `missing name falls back to header identity`() {
        val bytes = chat(
            """{"user_name":"Alice","character_name":"Seraphina"}""",
            """{"is_user":true,"mes":"在吗"}""",
            """{"is_user":false,"mes":"在的"}"""
        )
        val chat = mustParse(StChatParser.parse(bytes))
        assertEquals("Alice", chat.rounds[0].userMessage?.name)
        assertEquals("Seraphina", chat.rounds[0].charMessages[0].name)
    }

    @Test
    fun `legacy archive without header identified by first char message`() {
        val bytes = chat(
            """{"name":"Seraphina","is_user":false,"mes":"开场白"}""",
            """{"name":"Bob","is_user":true,"mes":"嗨"}"""
        )
        val chat = mustParse(StChatParser.parse(bytes))
        assertEquals("Seraphina", chat.meta.charName)
    }

    @Test
    fun `consecutive user messages split into separate rounds`() {
        val bytes = chat(
            """{"user_name":"A","character_name":"C"}""",
            """{"name":"A","is_user":true,"mes":"第一条"}""",
            """{"name":"A","is_user":true,"mes":"第二条"}""",
            """{"name":"C","is_user":false,"mes":"收到两条"}"""
        )
        val chat = mustParse(StChatParser.parse(bytes))
        assertEquals(2, chat.rounds.size)
        assertEquals("第一条", chat.rounds[0].userMessage?.mes)
        assertTrue(chat.rounds[0].charMessages.isEmpty())
        assertEquals("第二条", chat.rounds[1].userMessage?.mes)
        assertEquals("收到两条", chat.rounds[1].charMessages[0].mes)
    }

    @Test
    fun `invalid content returns null`() {
        assertNull(StChatParser.parse("这不是jsonl".toByteArray()))
        assertNull(StChatParser.parse(byteArrayOf()))
        // 只有元数据行,无消息
        assertNull(
            StChatParser.parse(
                """{"user_name":"A","character_name":"C"}""".toByteArray()
            )
        )
    }

    private fun mustParse(value: StChatParser.StChat?): StChatParser.StChat {
        assertNotNull("解析结果不应为空", value)
        return value!!
    }
}
