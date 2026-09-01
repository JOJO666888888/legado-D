package io.legado.app.help.ai

import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp

/**
 * 上下文预算 & 章节截断(按章节边界对齐,不把单章劈成两段)。
 * 超长时调用方(对话页/拆书房)提示"超出预算,已按章节边界截断前 N 章";
 * 返回 Pair:拼接文本 + 实际包含的章节索引范围(用于气泡 summary 展示 "《书名》第 X~Y 章")。
 */
object TokenBudget {

    suspend fun buildChapterContext(
        book: io.legado.app.data.entities.Book?,
        chapters: List<BookChapter>,
        model: String,
        charsBudget: Int = ModelCapabilities.injectionCharsBudget("")
    ): Pair<String, IntRange> {
        val actualBudget = if (charsBudget > 0) charsBudget else ModelCapabilities.injectionCharsBudget(
            book?.name ?: ""
        )
        val bk = book ?: return "" to IntRange.EMPTY
        val sb = StringBuilder(chapters.size * 400)
        var used = 0
        var firstIdx = -1
        var lastIdx = -1
        for (ch in chapters) {
            if (used >= actualBudget) break
            val content = runCatching { BookHelp.getContent(bk, ch) }.getOrNull().orEmpty()
            if (content.isBlank()) continue
            val header = "\n===== ${ch.index + 1}. ${ch.title} =====\n"
            val entryLen = header.length + content.length
            // 首章允许即使略超也至少放一章;后续章节严格遵守预算
            if (firstIdx != -1 && used + entryLen > actualBudget) break
            sb.append(header).append(content)
            used += entryLen
            if (firstIdx == -1) firstIdx = ch.index
            lastIdx = ch.index
        }
        val range = if (firstIdx < 0 || lastIdx < 0) IntRange.EMPTY else firstIdx..lastIdx
        // 注入格式:书名前缀 + 章节正文
        val head = "书籍《${bk.name}》作者《${bk.author}》正文(按章节注入):\n"
        return if (sb.isEmpty()) "" to range else (head + sb.toString()) to range
    }
}
