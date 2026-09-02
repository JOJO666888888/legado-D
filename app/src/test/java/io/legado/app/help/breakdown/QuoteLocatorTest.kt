package io.legado.app.help.breakdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M12 引文宽松定位纯逻辑单测:逐字匹配、归一化匹配、下标回映射。 */
class QuoteLocatorTest {

    @Test
    fun `blank or too short quote returns null`() {
        assertNull(QuoteLocator.locate("稍微长一点的正文内容。", ""))
        assertNull(QuoteLocator.locate("稍微长一点的正文内容。", "五个字"))
        assertNull(QuoteLocator.locate("", "这是一段超过六个字的引文"))
    }

    @Test
    fun `exact single hit returns exact match`() {
        val m = QuoteLocator.locate("风雨送春归,飞雪迎春到。", "飞雪迎春到", hintOffset = 0)
        assertTrue(m != null)
        assertTrue(m!!.exact)
        assertFalse(m.multiple)
        assertEquals(8, m.start)
        assertEquals(8 + "飞雪迎春到".length, m.endExclusive)
    }

    @Test
    fun `exact multiple hits pick nearest to hint`() {
        val text = "山。山。山。山。山。" // 「山。」出现 5 次
        // 取离 hint=5 最近的一处:命中起点 0,2,4,6,8 → 离 5 最近为 4
        val m = QuoteLocator.locate(text, "山。", hintOffset = 5)!!
        assertTrue(m.multiple)
        assertEquals(4, m.start)
    }

    @Test
    fun `fuzzy match maps compressed index back to original offset`() {
        val full = "「你好，世界」！"
        // 归一化(抹 ',' 与 '!',保留「」)后命中,映射回原文区间 [1,6) 覆盖「你好，世界」
        val m = QuoteLocator.locate(full, "你好世界", hintOffset = 0)
        assertTrue(m != null)
        assertFalse(m!!.exact)
        assertEquals(1, m.start)
        assertEquals(6, m.endExclusive)
        assertEquals("你好，世界", full.substring(m.start, m.endExclusive))
    }

    @Test
    fun `fuzzy match ignores whitespace`() {
        val full = "甲 乙 丙 丁"
        val m = QuoteLocator.locate(full, "甲乙丙", hintOffset = 0)
        assertTrue(m != null)
        assertFalse(m!!.exact)
        assertEquals(0, m.start)
        assertEquals(5, m.endExclusive) // 甲(0) 空格 乙(2) 空格 丙(4) 空格 → 丙之后为 5
    }

    @Test
    fun `quote that becomes empty after normalization finds nothing`() {
        // 全标点引文(长度>6 但归一化后为空)→ 不定位
        assertNull(QuoteLocator.locate("abcdefghijklmn", "，。！？、；："))
    }

    @Test
    fun `no occurrence returns null`() {
        assertNull(QuoteLocator.locate("完全无关的正文内容。", "这个引文在正文里", hintOffset = 0))
    }
}