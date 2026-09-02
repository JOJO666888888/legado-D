package io.legado.app.help.breakdown

/**
 * 引文宽松定位器(M12,MoRead BookQuoteLocator 移植)。
 *
 * AI 复述爱改标点/加空白,「逐字全匹配」在小差异下会失败。这里提供两级定位:
 *   1. 逐字全匹配:全部命中里取离提示偏移最近的;
 *   2. 归一化匹配:抹掉空白与标点后匹配,并把压缩下标**映射回原文 UTF-16 偏移**;
 * 短引文(<6 字符)过于歧义,不参与定位(返回 null,让上层标 needCheck)。
 *
 * 纯函数、即插即用;输入为章节净化后连续文本(与 BreakdownHelper 行号口径一致)。
 */
object QuoteLocator {

    /** 归一化阈值:更短不定位 */
    const val MIN_QUOTE_LEN = 6

    /** 待抹除字符:空白 + 常见中文/英文标点符号(与 BreakdownHelper.normalizeQuote 对齐) */
    private val IGNORE = Regex("[\\s\\p{P}\\p{S}：；，。！？、（）【】《》“”‘’…—·,.:;!?()·/\\-]")

    data class Match(
        val start: Int,
        val endExclusive: Int,
        /** true=逐字精确命中;false=归一化模糊命中 */
        val exact: Boolean,
        /** 逐字命中存在多处(取离提示最近) */
        val multiple: Boolean
    )

    /**
     * 定位引文。
     * @param fullText   章节净化连续文本(行以 \n 连接)
     * @param quote      AI 返回的引用片段
     * @param hintOffset 提示偏移(章节区间起点),多命中时取离它最近
     */
    fun locate(fullText: String, quote: String, hintOffset: Int = 0): Match? {
        if (quote.isBlank() || quote.length < MIN_QUOTE_LEN) return null
        if (fullText.isEmpty()) return null
        // 1) 逐字全匹配
        val exactHits = exactAll(fullText, quote)
        if (exactHits.isNotEmpty()) {
            val (s, e) = nearest(exactHits, hintOffset)
            return Match(s, e, exact = true, multiple = exactHits.size > 1)
        }
        // 2) 归一化匹配 + 下标映射回原文
        val fuzzyHit = fuzzy(fullText, quote)
        if (fuzzyHit != null) {
            return Match(fuzzyHit.first, fuzzyHit.second, exact = false, multiple = false)
        }
        return null
    }

    /** 全部逐字命中 */
    fun exactAll(fullText: String, quote: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var from = 0
        while (true) {
            val idx = fullText.indexOf(quote, from)
            if (idx < 0) break
            out.add(idx to (idx + quote.length))
            from = idx + maxOf(quote.length, 1)
        }
        return out
    }

    /** 归一化命中,返回 [start, end) 原文偏移 */
    fun fuzzy(fullText: String, quote: String): Pair<Int, Int>? {
        if (quote.length < MIN_QUOTE_LEN) return null
        val normQuote = normalize(quote)
        if (normQuote.isEmpty()) return null
        val (normText, origPos) = normalizeWithMap(fullText)
        val ns = normText.indexOf(normQuote)
        if (ns < 0) return null
        val ne = ns + normQuote.length
        // 映射压缩下标回原文:ns → 首个保留字符原文偏移;ne-1 → 末个保留字符原偏移+1
        val start = origPos[ns]
        val end = origPos[ne - 1] + 1
        if (start >= end) return null
        return start to end
    }

    private fun nearest(hits: List<Pair<Int, Int>>, hint: Int): Pair<Int, Int> {
        return hits.minByOrNull { kotlin.math.abs(it.first - hint) } ?: hits.first()
    }

    fun normalize(text: String): String = IGNORE.replace(text, "")

    /** 归一化全文 + 原文偏移映射表 origPos[k] = 第 k 个保留字符的原文下标 */
    private fun normalizeWithMap(text: String): Pair<String, IntArray> {
        val sb = StringBuilder(text.length)
        val map = IntArray(text.length)
        var k = 0
        for (i in text.indices) {
            val c = text[i]
            if (IGNORE.matches(c.toString())) continue
            sb.append(c)
            map[k] = i
            k++
        }
        return sb.toString() to map.copyOf(k)
    }
}