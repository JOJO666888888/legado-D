package io.legado.app.help.ai

import com.google.gson.JsonObject
import io.legado.app.data.appDb
import io.legado.app.data.vector.VectorRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.GSON
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Agent 工具层(M5):AI 能"翻书"的最小闭环。
 *
 * - [AiToolSpec]:OpenAI function calling 的工具声明(直接进 payload tools 数组)
 * - [AgentTool]:工具实现契约;执行失败返回「工具执行失败:…」字符串而非抛异常(MoRead 纪律),
 *   保证工具轮不会因单个工具崩溃而中断。
 * - [ReaderToolset]:阅读类工具集,全部复用现有 DAO / BookHelp,零新依赖。
 *
 * 误用纪律:工具只读、无副作用;所有依赖(bookId 缺省值、会话上下文)由调用方注入 [ToolContext]。
 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/** 工具执行上下文:发起本次工具轮的会话与缺省书籍(缺省取会话所绑拆书档案的书) */
data class ToolContext(
    val convId: Long,
    val defaultBookUrl: String? = null
)

interface AgentTool {
    val spec: AiToolSpec
    suspend fun execute(args: JsonObject, ctx: ToolContext): String
}

object ReaderToolset {

    val all: List<AgentTool> = listOf(
        ReadBookSectionTool,
        GetReadingProgressTool,
        SearchBookTool,
        SemanticSearchTool
    )

    fun byName(name: String): AgentTool? = all.firstOrNull { it.spec.name == name }

    /* ------------------------------ 读取章节 ------------------------------ */

    data object ReadBookSectionTool : AgentTool {
        override val spec: AiToolSpec = AiToolSpec(
            name = "ReadBookSection",
            description = "阅读指定书籍指定章节的正文内容,并按字符数截断返回。章节内容会被截断,如需更多内容请分多次调用(每次指定新的 startOffset)。",
            parameters = GSON.fromJson(
                "{\"type\":\"object\",\"properties\":{\"bookUrl\":{\"type\":\"string\",\"description\":\"书籍唯一标识,可省略由会话自动推断\"},\"chapterIndex\":{\"type\":\"integer\",\"description\":\"章节索引(0 起)\"},\"startOffset\":{\"type\":\"integer\",\"description\":\"起始字符偏移(UTF-16),默认 0\"},\"charLimit\":{\"type\":\"integer\",\"description\":\"返回的最大字符数,默认 1200\"}},\"required\":[\"chapterIndex\"]}",
                JsonObject::class.java
            )
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            currentCoroutineContext().ensureActive()
            val bookUrl = args.str("bookUrl") ?: ctx.defaultBookUrl
                ?: return "工具执行失败:无法确定书籍,请提供 bookUrl 或绑定拆书档案"
            val chapterIndex = args.int("chapterIndex")
            if (chapterIndex == null || chapterIndex < 0) return "工具执行失败:缺少 chapterIndex"
            val startOffset = args.int("startOffset") ?: 0
            val charLimit = args.int("charLimit") ?: 1200
            val book = appDb.bookDao.getBook(bookUrl) ?: return "工具执行失败:书架中找不到该书"
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return "工具执行失败:章节 $chapterIndex 不存在"
            val content = runCatching { BookHelp.getContent(book, chapter) }
                .getOrNull().orEmpty()
            if (content.isBlank()) return "章节《${chapter.title}》内容为空或未缓存"
            val head = "《${book.name}》第 ${chapterIndex + 1} 章《${chapter.title}》正文(偏移 $startOffset):\n"
            val body = if (startOffset >= content.length) "" else content.substring(startOffset)
            val bodyLen = body.length
            val truncated = body.take(charLimit)
            return head + truncated + if (bodyLen > charLimit) "\n…(正文共 $bodyLen 字,已截断,可用 startOffset=${startOffset + charLimit} 继续读取)" else ""
        }
    }

    /* ------------------------------ 阅读进度 ------------------------------ */

    data object GetReadingProgressTool : AgentTool {
        override val spec: AiToolSpec = AiToolSpec(
            name = "GetReadingProgress",
            description = "获取指定书籍(或会话绑定的拆书档案书籍)的当前阅读进度,返回进度百分比与最近阅读章节。",
            parameters = GSON.fromJson(
                "{\"type\":\"object\",\"properties\":{\"bookUrl\":{\"type\":\"string\",\"description\":\"书籍唯一标识,可省略由会话自动推断\"}},\"required\":[]}",
                JsonObject::class.java
            )
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            currentCoroutineContext().ensureActive()
            val bookUrl = args.str("bookUrl") ?: ctx.defaultBookUrl
                ?: return "工具执行失败:无法确定书籍,请提供 bookUrl 或绑定拆书档案"
            val book = appDb.bookDao.getBook(bookUrl) ?: return "工具执行失败:书架中找不到该书"
            val chapterNo = book.durChapterIndex + 1
            val total = book.totalChapterNum
            val pct = if (total > 0) (chapterNo * 100 / total) else 0
            val title = book.durChapterTitle.orEmpty()
            val titlePart = if (title.isNotBlank()) "《$title》" else ""
            return "《${book.name}》阅读进度:第 $chapterNo 章$titlePart(${pct.coerceIn(0, 100)}%, 全书共 $total 章)"
        }
    }

    /* ------------------------------ 章节标题搜索 ------------------------------ */

    data object SearchBookTool : AgentTool {
        override val spec: AiToolSpec = AiToolSpec(
            name = "SearchBook",
            description = "在指定书籍的目录(全书章节标题)中按关键词模糊搜索,返回命中的章节标题列表(最多 20 条)。",
            parameters = GSON.fromJson(
                "{\"type\":\"object\",\"properties\":{\"bookUrl\":{\"type\":\"string\",\"description\":\"书籍唯一标识,可省略由会话自动推断\"},\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键词(匹配章节标题包含关系)\"}},\"required\":[\"keyword\"]}",
                JsonObject::class.java
            )
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            currentCoroutineContext().ensureActive()
            val keyword = args.str("keyword")?.trim()
            if (keyword.isNullOrEmpty()) return "工具执行失败:缺少 keyword"
            val bookUrl = args.str("bookUrl") ?: ctx.defaultBookUrl
                ?: return "工具执行失败:无法确定书籍,请提供 bookUrl 或绑定拆书档案"
            val book = appDb.bookDao.getBook(bookUrl) ?: return "工具执行失败:书架中找不到该书"
            val hits = appDb.bookChapterDao.getChapterList(book.bookUrl)
                .filter { it.title.contains(keyword, ignoreCase = true) }
                .take(20)
            if (hits.isEmpty()) return "《${book.name}》目录中没有包含「$keyword」的章节"
            return hits.joinToString("\n") { "第 ${it.index + 1} 章 《${it.title}》" }
        }
    }

    /* ------------------------------ 全文语义检索 ------------------------------ */

    data object SemanticSearchTool : AgentTool {
        override val spec: AiToolSpec = AiToolSpec(
            name = "SemanticSearch",
            description = "在已建立语义索引的书籍正文中按含义(而非关键词)检索,返回与查询最相关的原文片段及所在章节。仅对已建立语义索引的书籍有效:索引入口为 AI 配置页的上下文选择器「建立整书语义索引」,或在会话中注入章节上下文时自动建立。",
            parameters = GSON.fromJson(
                "{\"type\":\"object\",\"properties\":{\"bookUrl\":{\"type\":\"string\",\"description\":\"书籍唯一标识,可省略由会话自动推断\"},\"query\":{\"type\":\"string\",\"description\":\"查询意图,用一句话描述想找的内容\"},\"topK\":{\"type\":\"integer\",\"description\":\"返回片段数,默认 5,最大 10\"}},\"required\":[\"query\"]}",
                JsonObject::class.java
            )
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            currentCoroutineContext().ensureActive()
            val query = args.str("query")?.trim()
            if (query.isNullOrEmpty()) return "工具执行失败:缺少 query"
            val bookUrl = args.str("bookUrl") ?: ctx.defaultBookUrl
                ?: return "工具执行失败:无法确定书籍,请提供 bookUrl 或绑定拆书档案"
            val book = appDb.bookDao.getBook(bookUrl) ?: return "工具执行失败:书架中找不到该书"
            val bookId = EmbeddingPipeline.embeddingBookId(book.bookUrl)
            if (!VectorRepository.bookIndexed(bookId)) {
                return "《${book.name}》尚未建立语义索引。请在上下文选择页选择该书后点击「建立整书语义索引」,或在会话中先注入一次该书的章节上下文(会自动建索引),建立后再试。"
            }
            val qv = EmbeddingPipeline.embedQuery(query)
                ?: return "语义检索不可用:向量模型未配置(EMBEDDING 角色)或请求失败,请检查 AI 配置"
            val topK = (args.int("topK") ?: 5).coerceIn(1, 10)
            val hits = VectorRepository.search(bookId, qv, topK)
            if (hits.isEmpty()) return "语义检索未找到相关内容,可换个说法或使用 SearchBook 按章节标题搜索"
            val titles = appDb.bookChapterDao.getChapterList(book.bookUrl)
                .associateBy { it.index }
            return hits.joinToString("\n---\n") { h ->
                val ch = titles[h.chunk.chapterIndex]
                val title = ch?.title ?: "未知章节"
                "第 ${h.chunk.chapterIndex + 1} 章《$title》:${h.chunk.text.take(120)}(相似度 ${"%.2f".format(h.score)})"
            }
        }
    }

    /* ------------------------------ JSON 读取小工具 ------------------------------ */

    private fun JsonObject.str(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive && !e.isJsonNull) e.asString else null
    }

    private fun JsonObject.int(key: String): Int? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive && !e.isJsonNull && e.asJsonPrimitive.isNumber) e.asInt else null
    }
}