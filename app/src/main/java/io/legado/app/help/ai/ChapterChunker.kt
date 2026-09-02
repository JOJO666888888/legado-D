package io.legado.app.help.ai

/**
 * 章节切片器(M7,MoRead 纯函数移植)。
 *
 * 口径:
 *   - 段落优先贪心打包:目标 480 字符,上限 640;chunk 边界**永不切断段落内部**;
 *   - 超长段落(>640)按句终符(。！？…；;)切,无句终符则在 640 处硬切;
 *   - 长度口径 UTF-16(Kotlin String.length 天然一致,与拆书/素材偏移同源)。
 *
 * 输出 (startOffset, chunkText):startOffset 为块在原文中的起始 UTF-16 偏移,
 * 供落库 charStart/charEnd 反查原文。
 */
object ChapterChunker {

    const val TARGET_CHARS = 480
    const val MAX_CHARS = 640

    /** 句终符:切点优先落在其后 */
    private val SENTENCE_END = java.util.regex.Pattern.compile("(?<=[。！？…；;])(?=\\S)")

    fun chunk(text: String): List<Pair<Int, String>> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        var offset = 0
        var slot = StringBuilder()
        var slotStart = 0
        text.lines().forEach { rawLine ->
            val lineStart = offset
            offset += rawLine.length + 1 // 换行计 1,与行号口径一致
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            splitParagraph(line).forEach { (inLine, piece) ->
                if (slot.isNotEmpty() && slot.length + 1 + piece.length > MAX_CHARS) {
                    out.add(slotStart to slot.toString())
                    slot.setLength(0)
                }
                if (slot.isEmpty()) slotStart = lineStart + inLine
                if (slot.isNotEmpty()) slot.append('\n')
                slot.append(piece)
            }
        }
        if (slot.isNotEmpty()) out.add(slotStart to slot.toString())
        return out
    }

    /**
     * 段落内切片:返回 (段内偏移, 片段)。段 ≤640 时原样返回;
     * 超长时在句终符处切(向前找),无句终符则在 640 处硬切。
     */
    private fun splitParagraph(line: String): List<Pair<Int, String>> {
        if (line.length <= MAX_CHARS) return listOf(0 to line)
        val ends = mutableListOf<Int>()
        val m = SENTENCE_END.matcher(line)
        while (m.find()) ends.add(m.end())
        val out = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (start < line.length) {
            val cap = start + MAX_CHARS
            val cut = ends.filter { it in (start + 1)..cap }.maxOrNull()
                ?: cap.coerceAtMost(line.length)
            out.add(start to line.substring(start, cut))
            start = cut
        }
        return out
    }
}