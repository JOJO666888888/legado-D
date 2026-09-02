package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M7 切片器纯逻辑单测:边界口径 480/640、段落优先、句终符切分、UTF-16 偏移。 */
class ChapterChunkerTest {

    @Test
    fun `blank text returns empty`() {
        assertTrue(ChapterChunker.chunk("").isEmpty())
        assertTrue(ChapterChunker.chunk("   \n\n ").isEmpty())
    }

    @Test
    fun `short single paragraph returns one chunk at offset zero`() {
        val chunks = ChapterChunker.chunk("青山依旧在,几度夕阳红。")
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].first)
        assertEquals("青山依旧在,几度夕阳红。", chunks[0].second)
    }

    @Test
    fun `multi paragraph chunks keep accumulating text and start offset`() {
        // "第一段。"(4 字) + '\n' + "第二段。"(4 字) → 单槽,起点 0,内容以 \n 连接
        val chunks = ChapterChunker.chunk("第一段。\n第二段。")
        assertEquals(1, chunks.size)
        assertEquals("第一段。\n第二段。", chunks[0].second)
        assertEquals(0, chunks[0].first)
    }

    @Test
    fun `oversized paragraph with no sentence end hard-cuts at 640`() {
        val chunks = ChapterChunker.chunk("a".repeat(700))
        assertEquals(2, chunks.size)
        assertEquals(640, chunks[0].second.length)
        assertEquals(0, chunks[0].first)
        assertEquals(700 - 640, chunks[1].second.length)
        assertEquals(640, chunks[1].first)
    }

    @Test
    fun `oversized paragraph cuts at sentence end not exceeding 640`() {
        // "啊。"×400 = 800 字;句子结束点(句号后一字符)落在偶数位 → 首块在 640 处切(≤640 的最后一个句终点)
        val chunks = ChapterChunker.chunk("啊。".repeat(400))
        assertTrue(chunks.size >= 2)
        assertEquals(640, chunks[0].second.length)
        assertTrue(chunks[0].second.endsWith("。"))
        assertEquals(640, chunks[1].first)
        assertEquals(800, chunks.sumOf { it.second.length })
    }

    @Test
    fun `normal paragraph under 640 never split internally`() {
        val text = "短".repeat(500)
        val chunks = ChapterChunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals(500, chunks[0].second.length)
    }

    @Test
    fun `chunk offsets respect UTF-16 length`() {
        val text = "𠀀𠀁".repeat(400) // 代理对字符:每个占 2 个 UTF-16 单元
        val chunks = ChapterChunker.chunk(text)
        // 总长度 1600 > 640 → 硬切,偏移按 UTF-16 计
        assertTrue(chunks.size >= 2)
        assertEquals(0, chunks[0].first)
        assertEquals(640, chunks[0].second.length)
        assertEquals(640, chunks[1].first)
    }
}