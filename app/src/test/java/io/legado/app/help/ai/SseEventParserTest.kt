package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSE 单条事件解析纯逻辑单测(M3 抽取 SseEventParser 后可测)。 */
class SseEventParserTest {

    /* ------------------------------ OpenAI 兼容 ------------------------------ */

    @Test
    fun `openai reasoning and content split into separate events`() {
        val full = StringBuilder()
        val thinking = StringBuilder()
        val builds = HashMap<Int, SseEventParser.ToolBuild>()
        val events = mutableListOf<SseStreamClient.StreamEvent>()
        SseEventParser.dispatchOpenAiEvent(
            """{"choices":[{"delta":{"reasoning_content":"想想","content":"答"}}]}""",
            full, thinking, builds
        ) { events.add(it) }
        assertEquals("想想", thinking.toString())
        assertEquals("答", full.toString())
        assertEquals(
            listOf("Reasoning", "Delta"),
            events.map { it.javaClass.simpleName }
        )
    }

    @Test
    fun `openai message-object fallback content`() {
        val full = StringBuilder()
        val thinking = StringBuilder()
        val events = mutableListOf<SseStreamClient.StreamEvent>()
        SseEventParser.dispatchOpenAiEvent(
            """{"choices":[{"message":{"content":"完整内容"}}]}""",
            full, thinking, HashMap()
        ) { events.add(it) }
        assertEquals("完整内容", full.toString())
        assertEquals(1, events.size)
    }

    @Test
    fun `openai ignores reasoning null literal`() {
        val full = StringBuilder()
        val thinking = StringBuilder()
        val events = mutableListOf<SseStreamClient.StreamEvent>()
        SseEventParser.dispatchOpenAiEvent(
            """{"choices":[{"delta":{"reasoning_content":"null","content":"x"}}]}""",
            full, thinking, HashMap()
        ) { events.add(it) }
        assertEquals("", thinking.toString())
        assertEquals("x", full.toString())
        assertEquals(1, events.size)
    }

    @Test
    fun `openai root-level content is emitted as delta`() {
        val full = StringBuilder()
        val events = mutableListOf<SseStreamClient.StreamEvent>()
        SseEventParser.dispatchOpenAiEvent(
            """{"content":"直"}""", full, StringBuilder(), HashMap()
        ) { events.add(it) }
        assertEquals("直", full.toString())
        assertTrue(events.first() is SseStreamClient.StreamEvent.Delta)
    }

    @Test
    fun `openai tool calls accumulate arguments across deltas`() {
        val builds = HashMap<Int, SseEventParser.ToolBuild>()
        SseEventParser.dispatchOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"ReadBookSection","arguments":"{\"chapterIndex\":1,"}}]}}]}""",
            StringBuilder(), StringBuilder(), builds
        ) {}
        SseEventParser.dispatchOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"charLimit\":1200}"}}]}}]}""",
            StringBuilder(), StringBuilder(), builds
        ) {}
        assertEquals(1, builds.size)
        val call = builds.getValue(0).toDelta()
        assertEquals("call_1", call.id)
        assertEquals("ReadBookSection", call.name)
        assertEquals("{\"chapterIndex\":1,\"charLimit\":1200}", call.arguments)
    }

    @Test
    fun `openai malformed json is ignored`() {
        val full = StringBuilder()
        SseEventParser.dispatchOpenAiEvent("not-json", full, StringBuilder(), HashMap()) {}
        assertEquals("", full.toString())
    }

    /* ------------------------------ Anthropic 兼容 ------------------------------ */

    @Test
    fun `claude text and thinking deltas`() {
        val full = StringBuilder()
        val thinking = StringBuilder()
        val events = mutableListOf<SseStreamClient.StreamEvent>()
        SseEventParser.dispatchClaudeEvent(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"T"}}""",
            full, thinking, HashMap()
        ) { events.add(it) }
        SseEventParser.dispatchClaudeEvent(
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Th"}}""",
            full, thinking, HashMap()
        ) { events.add(it) }
        assertEquals("T", full.toString())
        assertEquals("Th", thinking.toString())
        assertEquals(listOf("Delta", "Reasoning"), events.map { it.javaClass.simpleName })
    }

    @Test
    fun `claude tool use start and partial json`() {
        val builds = HashMap<Int, SseEventParser.ToolBuild>()
        SseEventParser.dispatchClaudeEvent(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"SearchBook"}}""",
            StringBuilder(), StringBuilder(), builds
        ) {}
        SseEventParser.dispatchClaudeEvent(
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"keyword\":\"测试\""}}""",
            StringBuilder(), StringBuilder(), builds
        ) {}
        SseEventParser.dispatchClaudeEvent(
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"}}"}""",
            StringBuilder(), StringBuilder(), builds
        ) {}
        val call = builds.getValue(1).toDelta()
        assertEquals("toolu_1", call.id)
        assertEquals("SearchBook", call.name)
        assertEquals("{\"keyword\":\"测试\"}}", call.arguments)
    }

    @Test
    fun `claude error event carries vendor message`() {
        val events = mutableListOf<SseStreamClient.StreamEvent>()
        SseEventParser.dispatchClaudeEvent(
            """{"type":"error","error":{"message":"overloaded"}}""",
            StringBuilder(), StringBuilder(), HashMap()
        ) { events.add(it) }
        val err = events.first() as SseStreamClient.StreamEvent.Error
        assertEquals("overloaded", err.t.message)
    }

    /* ------------------------------ 错误体提取 ------------------------------ */

    @Test
    fun `extract error message from vendor bodies`() {
        assertEquals("rate limited", SseEventParser.extractErrorMessage("""{"error":{"message":"rate limited"}}"""))
        assertEquals("boom", SseEventParser.extractErrorMessage("""{"message":"boom"}"""))
        assertNull(SseEventParser.extractErrorMessage("not json at all"))
        assertNull(SseEventParser.extractErrorMessage("{}"))
        assertNull(SseEventParser.extractErrorMessage(""))
        assertNull(SseEventParser.extractErrorMessage("  "))
    }
}