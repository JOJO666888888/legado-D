package io.legado.app.help.breakdown

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.data.entities.BreakdownChapter
import io.legado.app.data.entities.BreakdownSegment
import io.legado.app.data.entities.BreakdownTemplate
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 拆书工具:行号↔偏移互算、净化文本获取、引文锚点校验、内置模板、JSON/CSV/MD 编解码。
 *
 * 行号口径(与阅读页一致):
 * - 拆解输入的「行」= 净化后章节文本行(ContentProcessor.getContent 输出,1 起);
 * - 连续文本 = 各行以 `\n` 连接(换行计 1),与 TextChapter.getContent() 口径一致
 *   (searchResultPositions / 素材偏移同源,可对拍验证)。
 */
object BreakdownHelper {

    /** 拆解段落状态 */
    const val STATUS_NONE = 0
    const val STATUS_DRAFT = 1
    const val STATUS_CONFIRMED = 2

    /* ------------------------------ 标签色板 ------------------------------ */
    // 拆解标签用独立色板,与素材自由标签区分(结构性语义色)
    private val tagPalette = intArrayOf(
        0xFF5C6BC0.toInt(), // 靛蓝
        0xFF26A69A.toInt(), // 青
        0xFFEF6C00.toInt(), // 橙
        0xFF42A5F5.toInt(), // 蓝
        0xFFEC407A.toInt(), // 粉
        0xFF66BB6A.toInt(), // 绿
        0xFFFFA726.toInt(), // 琥珀
        0xFF8D6E63.toInt(), // 棕
        0xFFAB47BC.toInt(), // 紫
        0xFF26C6DA.toInt()  // 天青
    )

    fun tagColor(tag: String): Int =
        tagPalette[abs(tag.hashCode()) % tagPalette.size]

    fun tagColors(): IntArray = tagPalette

    fun notifyChanged() {
        postEvent(EventBus.BREAKDOWNS_CHANGED, true)
    }

    fun statusText(status: Int, aiModel: String): String = when (status) {
        STATUS_CONFIRMED -> "已确认"
        STATUS_DRAFT -> if (aiModel.isBlank()) "草稿" else "AI草稿"
        else -> "未拆"
    }

    /* ---------------------------- 净化文本获取 ---------------------------- */

    /**
     * 获取净化后章节文本行数组(与阅读页章节定位同源)。
     * 依赖章节原文缓存;未缓存(网络书)返回 null,由调用方提示先缓存章节。
     */
    fun getPurifiedLines(book: Book, chapter: io.legado.app.data.entities.BookChapter): Array<String>? {
        val raw = BookHelp.getContent(book, chapter) ?: return null
        val bookContent = ContentProcessor.get(book).getContent(book, chapter, raw)
        return bookContent.textList.toTypedArray()
    }

    /**
     * 净化后连续文本(行以 \n 连接,换行计 1)
     */
    fun getPurifiedContent(book: Book, chapter: io.legado.app.data.entities.BookChapter): String? {
        return getPurifiedLines(book, chapter)?.joinToString("\n")
    }

    /**
     * 预编号文本:每行前缀 `N|`(1 起),供 AI 引用行号。截断超长章节并返回实际行数。
     */
    fun buildNumberedContent(lines: Array<String>, maxSendChars: Int = Int.MAX_VALUE): Pair<String, Int> {
        val sb = StringBuilder()
        var sentLines = 0
        for ((i, line) in lines.withIndex()) {
            val prefix = "${i + 1}|"
            if (sb.isNotEmpty()) {
                if (sb.length + 1 + prefix.length + line.length > maxSendChars) break
                sb.append('\n')
            } else if (prefix.length + line.length > maxSendChars) {
                break
            }
            sb.append(prefix).append(line)
            sentLines = i + 1
        }
        return sb.toString() to sentLines
    }

    /** 便捷重载:直接给原始章节文本,内部净化 + 编号,返回 (编号文本, 已编号末行)。 */
    fun buildNumberedContent(rawChapterText: String): String {
        val lines = rawChapterText.lineSequence().filter { it.isNotBlank() }.toList().toTypedArray()
        return buildNumberedContent(lines, Int.MAX_VALUE).first
    }

    /**
     * AI 拆书结果落库(草稿态,不覆盖人工已确认数据)。
     *
     * @return 成功返回 null,失败返回错误文案(方便上层写 KIND_ERROR banner)。
     */
    suspend fun saveAiChapter(
        bdId: Long,
        chapterIndex: Int,
        chapterName: String,
        numberedContent: String,
        result: BreakdownAiRunner.AiChapterResult
    ): String? = withContext(IO) {
        try {
            val record = appDb.breakdownChapterDao.getByBreakdownAndIndex(bdId, chapterIndex)
            val status = record?.status ?: STATUS_NONE
            if (status == STATUS_CONFIRMED) {
                // 红线 R:AI 结果不覆盖人工已确认数据
                return@withContext "本章已确认,不写入 AI 草稿(如需重拆请用户在章节页二次确认)"
            }
            val recordId = if (record != null) {
                record.id
            } else {
                appDb.breakdownChapterDao.upsert(
                    BreakdownChapter(
                        breakdownId = bdId,
                        chapterIndex = chapterIndex,
                        chapterName = chapterName
                    )
                ).firstOrNull() ?: return@withContext "章记录创建失败"
            }
            val lines = numberedContent.lineSequence().filter { it.isNotBlank() }.toList().toTypedArray()
            val finalized = BreakdownAiRunner.finalizeSegments(result.segments, lines)
            appDb.breakdownSegmentDao.deleteByChapter(recordId)
            val now = System.currentTimeMillis()
            finalized.forEachIndexed { i, seg ->
                val s = refreshSegmentPos(
                    BreakdownSegment(
                        chapterId = recordId,
                        sortOrder = i,
                        startLine = seg.startLine,
                        endLine = seg.endLine,
                        label = seg.label,
                        contentSummary = seg.contentSummary,
                        rhythmNote = seg.rhythmNote,
                        highlights = seg.highlights.joinToString("\n"),
                        needCheck = seg.needCheck,
                        createTime = now,
                        updateTime = now
                    ),
                    lines
                )
                appDb.breakdownSegmentDao.upsert(s)
            }
            val updated = appDb.breakdownChapterDao.get(recordId)!!.copy(
                summary = result.chapterSummary,
                status = STATUS_DRAFT,
                aiModel = io.legado.app.help.config.AppConfig.aiModel,
                updateTime = now
            )
            appDb.breakdownChapterDao.update(updated)
            appDb.bookBreakdownDao.get(bdId)?.let { bd ->
                appDb.bookBreakdownDao.update(bd.copy(updateTime = now))
            }
            notifyChanged()
            null
        } catch (e: Exception) {
            e.message ?: "写入拆书草稿失败"
        }
    }

    /* --------------------------- 行号↔偏移互算 --------------------------- */

    /**
     * 第 [line] 行(1 起)的起始偏移:前 line-1 行各贡献 len+1
     */
    fun lineStartPos(lines: Array<String>, line: Int): Int {
        var pos = 0
        val n = (line - 1).coerceIn(0, lines.size)
        for (i in 0 until n) {
            pos += lines[i].length + 1
        }
        return pos
    }

    /**
     * 第 [endLine] 行(1 起)结束偏移(不含换行):前 endLine 行各贡献 len+1
     */
    fun lineEndPos(lines: Array<String>, endLine: Int): Int {
        var pos = 0
        val n = endLine.coerceIn(0, lines.size)
        for (i in 0 until n) {
            pos += lines[i].length + 1
        }
        return pos
    }

    /**
     * 区间文本(含两端端点行)
     */
    fun rangeText(lines: Array<String>, startLine: Int, endLine: Int): String {
        val start = startLine.coerceIn(1, lines.size + 1)
        val end = endLine.coerceIn(start.coerceAtLeast(1), lines.size)
        if (start > end) return ""
        return (start - 1 until end).joinToString(separator = "\n") { lines[it] }
    }

    /**
     * 偏移反算行号(1 起;换行计 1)。返回该偏移所属行,越界钳制。
     */
    fun posToLine(lines: Array<String>, pos: Int): Int {
        var cur = 0
        for ((i, line) in lines.withIndex()) {
            if (pos < cur + line.length + 1) {
                return i + 1
            }
            cur += line.length + 1
        }
        return lines.size
    }

    /**
     * 重新计算某段的全部换算字段(行号 ↔ 偏移)
     */
    fun refreshSegmentPos(
        segment: BreakdownSegment,
        lines: Array<String>
    ): BreakdownSegment {
        val startLine = segment.startLine.coerceIn(1, lines.size)
        val endLine = segment.endLine.coerceIn(startLine, lines.size)
        return segment.copy(
            startLine = startLine,
            endLine = endLine,
            startPos = lineStartPos(lines, startLine),
            endPos = lineEndPos(lines, endLine)
        )
    }

    /**
     * 按偏移区间反算行号区间(素材转段落用)
     */
    fun posRangeToLines(
        lines: Array<String>,
        startPos: Int,
        endPos: Int
    ): Pair<Int, Int> {
        val startLine = posToLine(lines, startPos)
        // endPos 指向下一行起始时,归并到上一行(区间含端点)
        var endLine = posToLine(lines, (endPos - 1).coerceAtLeast(0))
        if (endLine < startLine) endLine = startLine
        return startLine to endLine
    }

    /* ---------------------------- 引文锚点校验 ---------------------------- */

    /**
     * 归一化:去除全部空白与常见中文/英文标点,用于宽容比对
     */
    fun normalizeQuote(text: String): String =
        text.replace(Regex("[\\s\\p{P}\\p{S}：；，。！？、（）【】《》“”‘’…—·,.:;!?()·/\\-]"), "")

    /**
     * 引文锚点校验:AI 返回 [quote] 与 [startLine, endLine] 区间,比对原文;
     * 不符则全文重定位并重算区间;仍失败返回 needCheck=true。
     *
     * @return 修正后的区间,以及是否需人工核对
     */
    fun verifyAnchor(
        lines: Array<String>,
        startLine: Int,
        endLine: Int,
        quote: String
    ): Triple<Int, Int, Boolean> {
        val normQuote = normalizeQuote(quote)
        if (normQuote.isEmpty()) return Triple(startLine.coerceIn(1, lines.size), endLine.coerceIn(startLine.coerceIn(1, lines.size), lines.size), false)
        val rangeText = rangeText(lines, startLine, endLine)
        if (normalizeQuote(rangeText).contains(normQuote) ||
            normQuote.contains(normalizeQuote(rangeText))
        ) {
            return Triple(
                startLine.coerceIn(1, lines.size),
                endLine.coerceIn(startLine.coerceIn(1, lines.size), lines.size),
                false
            )
        }
        // 全文重定位(用原文 indexOf,容忍行间差异)
        val fullText = lines.joinToString("\n")
        val idx = fullText.indexOf(quote)
        if (idx >= 0) {
            val newStart = posToLine(lines, idx)
            val newEnd = posToLine(lines, (idx + quote.length - 1).coerceAtLeast(0))
            return Triple(newStart, newEnd, false)
        }
        return Triple(startLine, endLine, true)
    }

    /**
     * 偏移区间漂移兜底(仿 MaterialHelper.resolveRange):
     * 校验 [start, end) 子串与期望文本一致,不一致则 indexOf 重定位。
     */
    fun resolveRange(chapterContent: String, expect: String, start: Int, end: Int): IntArray? {
        if (expect.isEmpty()) return null
        if (start in 0 until end && end <= chapterContent.length) {
            if (chapterContent.substring(start, end) == expect) {
                return intArrayOf(start, end)
            }
        }
        val index = chapterContent.indexOf(expect)
        if (index >= 0) {
            return intArrayOf(index, (index + expect.length).coerceAtMost(chapterContent.length))
        }
        return null
    }

    /* ---------------------------- 内置模板初始化 ---------------------------- */

    val builtinTemplateName = "青山式拆解"

    /**
     * 内置「青山式拆解」模板(只读,可复制出副本)
     */
    fun builtinTemplate(): BreakdownTemplate = BreakdownTemplate(
        id = 0,
        name = builtinTemplateName,
        segmentLabels = listOf(
            "渲染", "代入感", "塑造人设", "铺垫危机", "期待感",
            "爽点释放", "世界观展开", "冲突升级", "解决危机",
            "猎枪理论", "结尾悬念", "过渡衔接", "伏笔回收", "情绪调动"
        ),
        aiPromptExtra = """
            拆解方法论(基于《青山》式拆解规范,供每章拆解参考):
            1. 题材对标:先定位本书题材与头部对标书,拆出版本差异与核心卖点。
            2. 书名公式:题材关键词+语气词+人物要素词;简介四要素=悬念+身份+冲突+期待。
            3. 开头节奏:前 300 字内制造悬念,前 500 字内抛出核心危机。
            4. 章节节奏:危机递进→给希望→铺垫世界观→系统登场/金手指→猎枪理论(前文伏笔在此开火)→结尾悬念。
            5. 多章循环:铺垫危机→解决方案→爽点释放与收获,循环推进。
            6. 每段输出:行号区间+功能标签+内容简述+节奏拆解+亮点爆点。
            7. 亮点爆点要附原文引用片段,供机械对齐校验。
        """.trimIndent(),
        isBuiltin = true,
        config = """{"fields":{"category":true,"achievement":true,"titleFormula":true,"benchmarks":true,"overallNote":true}}""",
        createTime = System.currentTimeMillis(),
        updateTime = System.currentTimeMillis()
    )

    /**
     * 初始化内置模板:仅当同名模板不存在时插入(备份恢复/重复打开不覆盖用户数据)
     */
    suspend fun ensureBuiltinTemplate() = withContext(IO) {
        if (appDb.breakdownTemplateDao.getByName(builtinTemplateName) == null) {
            appDb.breakdownTemplateDao.insert(builtinTemplate())
        }
    }

    /* --------------------------------- JSON 编解码 --------------------------------- */

    fun templateToJson(list: List<BreakdownTemplate>): String = GSON.toJson(list)
    fun breakdownToJson(list: List<BookBreakdown>): String = GSON.toJson(list)
    fun chapterToJson(list: List<BreakdownChapter>): String = GSON.toJson(list)
    fun segmentToJson(list: List<BreakdownSegment>): String = GSON.toJson(list)

    data class ImportBundle(
        val templates: List<BreakdownTemplate>,
        val breakdowns: List<BookBreakdown>,
        val chapters: List<BreakdownChapter>,
        val segments: List<BreakdownSegment>
    )

    fun fromJsonObjectTemplate(o: JsonObject): BreakdownTemplate = BreakdownTemplate(
        id = o.longValue("id"),
        name = o.str("name"),
        segmentLabels = o.stringList("segmentLabels"),
        aiPromptExtra = o.str("aiPromptExtra"),
        isBuiltin = o.boolValue("isBuiltin"),
        config = o.str("config").ifEmpty { "{}" },
        createTime = o.longValue("createTime").takeIf { it > 0 } ?: System.currentTimeMillis(),
        updateTime = o.longValue("updateTime").takeIf { it > 0 } ?: System.currentTimeMillis()
    )

    fun fromJsonObjectBreakdown(o: JsonObject): BookBreakdown = BookBreakdown(
        id = o.longValue("id"),
        bookName = o.str("bookName"),
        bookAuthor = o.str("bookAuthor"),
        bookUrl = o.str("bookUrl"),
        templateId = o.longValue("templateId"),
        category = o.str("category"),
        achievement = o.str("achievement"),
        titleFormula = o.str("titleFormula"),
        benchmarks = o.stringList("benchmarks"),
        overallNote = o.str("overallNote"),
        deletedAt = o.longValue("deletedAt"),
        createTime = o.longValue("createTime").takeIf { it > 0 } ?: System.currentTimeMillis(),
        updateTime = o.longValue("updateTime").takeIf { it > 0 } ?: System.currentTimeMillis()
    )

    fun fromJsonObjectChapter(o: JsonObject): BreakdownChapter = BreakdownChapter(
        id = o.longValue("id"),
        breakdownId = o.longValue("breakdownId"),
        chapterIndex = o.intValue("chapterIndex"),
        chapterName = o.str("chapterName"),
        summary = o.str("summary"),
        status = o.intValue("status"),
        aiModel = o.str("aiModel"),
        createTime = o.longValue("createTime").takeIf { it > 0 } ?: System.currentTimeMillis(),
        updateTime = o.longValue("updateTime").takeIf { it > 0 } ?: System.currentTimeMillis()
    )

    fun fromJsonObjectSegment(o: JsonObject): BreakdownSegment = BreakdownSegment(
        id = o.longValue("id"),
        chapterId = o.longValue("chapterId"),
        sortOrder = o.intValue("sortOrder"),
        startLine = o.intValue("startLine"),
        endLine = o.intValue("endLine"),
        startPos = o.intValue("startPos"),
        endPos = o.intValue("endPos"),
        label = o.str("label"),
        contentSummary = o.str("contentSummary"),
        rhythmNote = o.str("rhythmNote"),
        highlights = o.str("highlights"),
        needCheck = o.boolValue("needCheck"),
        createTime = o.longValue("createTime").takeIf { it > 0 } ?: System.currentTimeMillis(),
        updateTime = o.longValue("updateTime").takeIf { it > 0 } ?: System.currentTimeMillis()
    )

    /**
     * 解析自家导出的完整 JSON(四表数组,{...} 单对象或 [..] 数组或 {templates:[]..} 均兼容)
     */
    fun parseImportBundle(text: String): ImportBundle? = runCatching {
        val root = JsonParser.parseString(text)
        fun <T> parseList(key: String?, parser: (JsonObject) -> T): List<T> {
            val arr = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.has(key) -> {
                    val e = root.asJsonObject.get(key)
                    if (e.isJsonArray) e.asJsonArray else return emptyList()
                }
                else -> return emptyList()
            }
            return arr.filter { it.isJsonObject }.map { parser(it.asJsonObject) }
        }
        ImportBundle(
            templates = parseList("templates", ::fromJsonObjectTemplate),
            breakdowns = parseList("breakdowns", ::fromJsonObjectBreakdown),
            chapters = parseList("chapters", ::fromJsonObjectChapter),
            segments = parseList("segments", ::fromJsonObjectSegment)
        )
    }.getOrNull()

    /* --------------------------------- CSV(BOM) --------------------------------- */

    private fun csvEscape(field: String): String {
        if (field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')) {
            return "\"" + field.replace("\"", "\"\"") + "\""
        }
        return field
    }

    fun csvRow(vararg fields: String): String = fields.joinToString(",") { csvEscape(it) }

    /**
     * 导出 CSV(UTF-8 BOM,Excel/WPS 直开):档案头块 + 每章表头行 + 段落行 + 章总结行
     */
    fun toCsv(
        template: BreakdownTemplate?,
        breakdown: BookBreakdown,
        chapters: List<BreakdownChapter>,
        segments: Map<Long, List<BreakdownSegment>>
    ): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // UTF-8 BOM
        sb.append(csvRow("拆书导出", "《${breakdown.bookName}》")).append("\r\n")
        sb.append(csvRow("模板", template?.name ?: "", "", "", "", "", "")).append("\r\n")
        sb.append(csvRow("品类", breakdown.category)).append("\r\n")
        sb.append(csvRow("成绩", breakdown.achievement)).append("\r\n")
        sb.append(csvRow("书名公式", breakdown.titleFormula)).append("\r\n")
        sb.append(csvRow("对标书", breakdown.benchmarks.joinToString(" / "))).append("\r\n")
        sb.append(csvRow("整体总评", breakdown.overallNote)).append("\r\n")
        sb.append("\r\n")
        for (chapter in chapters) {
            sb.append(csvRow("章", chapter.chapterName, BreakdownHelper.statusText(chapter.status, chapter.aiModel), "", "", "", "")).append("\r\n")
            sb.append(csvRow("行号区间", "功能标签", "内容简述", "节奏拆解", "亮点爆点", "", "")).append("\r\n")
            segments[chapter.id].orEmpty().sortedBy { it.sortOrder }.forEach { seg ->
                sb.append(csvRow(
                    "${seg.startLine}-${seg.endLine}",
                    seg.label,
                    seg.contentSummary,
                    seg.rhythmNote,
                    seg.highlights,
                    if (seg.needCheck) "需人工核对" else "",
                    ""
                )).append("\r\n")
            }
            sb.append(csvRow("章节总结", chapter.summary, "", "", "", "", "")).append("\r\n")
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /* --------------------------------- Markdown --------------------------------- */

    fun toMarkdown(
        breakdown: BookBreakdown,
        chapters: List<BreakdownChapter>,
        segments: Map<Long, List<BreakdownSegment>>
    ): String {
        val sb = StringBuilder()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("# 拆书笔记:《").append(breakdown.bookName).append("》\n\n")
        if (breakdown.bookAuthor.isNotBlank()) {
            sb.append("- 作者:").append(breakdown.bookAuthor).append('\n')
        }
        if (breakdown.category.isNotBlank()) {
            sb.append("- 品类:").append(breakdown.category).append('\n')
        }
        if (breakdown.achievement.isNotBlank()) {
            sb.append("- 成绩:").append(breakdown.achievement).append('\n')
        }
        if (breakdown.titleFormula.isNotBlank()) {
            sb.append("- 书名公式:").append(breakdown.titleFormula).append('\n')
        }
        if (breakdown.benchmarks.isNotEmpty()) {
            sb.append("- 对标书:").append(breakdown.benchmarks.joinToString("、")).append('\n')
        }
        if (breakdown.overallNote.isNotBlank()) {
            sb.append("\n> 整体总评:").append(breakdown.overallNote.replace('\n', ' ')).append('\n')
        }
        sb.append("\n> 导出时间:").append(dateFmt.format(Date())).append('\n')
        sb.append("> 共 ").append(chapters.size).append(" 章\n\n")
        for (chapter in chapters) {
            sb.append("## ").append(chapter.chapterName)
            sb.append("  [").append(statusText(chapter.status, chapter.aiModel)).append("]\n\n")
            segments[chapter.id].orEmpty().sortedBy { it.sortOrder }.forEach { seg ->
                sb.append("### 段落 ").append(seg.startLine).append('-').append(seg.endLine)
                if (seg.label.isNotBlank()) {
                    sb.append(" · #").append(seg.label)
                }
                if (seg.needCheck) {
                    sb.append(" · 需人工核对")
                }
                sb.append("\n\n")
                if (seg.contentSummary.isNotBlank()) {
                    sb.append("**内容简述:**").append(seg.contentSummary).append("\n\n")
                }
                if (seg.rhythmNote.isNotBlank()) {
                    sb.append("**节奏拆解:**").append(seg.rhythmNote).append("\n\n")
                }
                if (seg.highlights.isNotBlank()) {
                    sb.append("**亮点爆点:**\n")
                    seg.highlights.lines().forEach { line ->
                        if (line.isNotBlank()) {
                            sb.append("> ").append(line).append('\n')
                        }
                    }
                    sb.append('\n')
                }
            }
            if (chapter.summary.isNotBlank()) {
                sb.append("**剧情+节奏总结:**").append(chapter.summary).append("\n\n---\n\n")
            } else {
                sb.append("---\n\n")
            }
        }
        return sb.toString()
    }

    /* ------------------------------ 内部小工具 ------------------------------ */

    /**
     * AI 拆解本章并落库(草稿态)。单章/批量共用;成功返回 null,失败返回错误文案。
     *
     * 规则:
     * - 覆盖策略由调用方决定(已确认章需用户二次确认);
     * - 无论结果如何,AI 产物一律 status=DRAFT 且 aiModel 记录来源;
     * - 校验失败段 needCheck=true 保留,不覆盖人工已确认数据。
     */
    suspend fun aiBreakdownChapter(
        breakdownId: Long,
        chapterIndex: Int
    ): String? = withContext(IO) {
        try {
            val bd = appDb.bookBreakdownDao.get(breakdownId) ?: return@withContext "档案不存在"
            val book = bd.bookUrl.takeIf { it.isNotBlank() }
                ?.let { appDb.bookDao.getBook(it) } ?: return@withContext "书未关联书架"
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return@withContext "章节不存在"
            val lines = getPurifiedLines(book, chapter) ?: return@withContext "章节未缓存,请先缓存原文"

            val template = bd.templateId.takeIf { it > 0 }
                ?.let { appDb.breakdownTemplateDao.get(it) }
            val (numbered, _) = buildNumberedContent(lines, io.legado.app.help.config.AppConfig.aiMaxSendChars)

            val raw = BreakdownAiRunner.breakdownChapter(
                numberedContent = numbered,
                chapterTitle = chapter.title,
                templateLabels = template?.segmentLabels.orEmpty(),
                aiPromptExtra = template?.aiPromptExtra.orEmpty(),
                baseUrl = io.legado.app.help.config.AppConfig.aiBaseUrl,
                apiKey = io.legado.app.help.config.AppConfig.aiApiKey,
                model = io.legado.app.help.config.AppConfig.aiModel
            )
            val finalized = BreakdownAiRunner.finalizeSegments(raw.segments, lines)

            // 确保章记录存在
            var record = appDb.breakdownChapterDao.getByBreakdownAndIndex(breakdownId, chapterIndex)
            if (record == null) {
                val id = appDb.breakdownChapterDao.upsert(
                    BreakdownChapter(
                        breakdownId = breakdownId,
                        chapterIndex = chapterIndex,
                        chapterName = chapter.title
                    )
                ).firstOrNull() ?: -1L
                record = appDb.breakdownChapterDao.get(id)
            }
            record ?: return@withContext "章记录创建失败"

            // 替换本章旧段落,写入草稿
            appDb.breakdownSegmentDao.deleteByChapter(record.id)
            val now = System.currentTimeMillis()
            finalized.forEachIndexed { i, seg ->
                var s = BreakdownSegment(
                    chapterId = record.id,
                    sortOrder = i,
                    startLine = seg.startLine,
                    endLine = seg.endLine,
                    label = seg.label,
                    contentSummary = seg.contentSummary,
                    rhythmNote = seg.rhythmNote,
                    highlights = seg.highlights.joinToString("\n"),
                    needCheck = seg.needCheck,
                    createTime = now,
                    updateTime = now
                )
                s = refreshSegmentPos(s, lines)
                appDb.breakdownSegmentDao.upsert(s)
            }
            val updated = record.copy(
                summary = raw.chapterSummary,
                status = STATUS_DRAFT,
                aiModel = io.legado.app.help.config.AppConfig.aiModel,
                updateTime = now
            )
            appDb.breakdownChapterDao.update(updated)
            appDb.bookBreakdownDao.update(
                bd.copy(updateTime = now)
            )
            notifyChanged()
            null
        } catch (e: BreakdownAiRunner.AiException) {
            e.message ?: "AI 拆解失败"
        } catch (e: Exception) {
            e.message ?: "AI 拆解失败"
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString ?: ""

    private fun JsonObject.longValue(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong ?: 0L

    private fun JsonObject.intValue(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong?.toInt() ?: 0

    private fun JsonObject.boolValue(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asBoolean ?: false

    private fun JsonObject.stringList(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray
            .filter { it.isJsonPrimitive }
            .map { it.asString }
            .filter { it.isNotBlank() }
            .distinct()
    }
}