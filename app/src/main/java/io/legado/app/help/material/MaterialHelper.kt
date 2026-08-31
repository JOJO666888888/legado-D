package io.legado.app.help.material

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Material
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 素材工具:定位漂移兜底、标签聚合、JSON/MD 编解码、导入去重
 */
object MaterialHelper {

    /**
     * 导入去重策略
     */
    enum class ImportStrategy { SKIP_DUPLICATES, IMPORT_ALL, MERGE }

    data class ImportResult(
        val total: Int,
        val imported: Int,
        val skipped: Int,
        val merged: Int
    )

    private val tagPalette = intArrayOf(
        0xFF7E57C2.toInt(), // 紫
        0xFF26A69A.toInt(), // 青
        0xFFEF6C00.toInt(), // 橙
        0xFF42A5F5.toInt(), // 蓝
        0xFFEC407A.toInt(), // 粉
        0xFF66BB6A.toInt(), // 绿
        0xFFFFA726.toInt(), // 琥珀
        0xFF8D6E63.toInt()  // 棕
    )

    fun tagColor(tag: String): Int =
        tagPalette[abs(tag.hashCode()) % tagPalette.size]

    fun tagColors(): IntArray = tagPalette

    /**
     * 校验并修正素材在章节排版文本中的定位
     * (净化/替换规则变更后偏移漂移的兜底;仅内存修正,不回写数据库)
     *
     * @return 修正后的 [start, end];无法定位返回 null
     */
    fun resolveRange(chapterContent: String, material: Material): IntArray? {
        val start = material.chapterPos
        val end = material.chapterPosEnd
        if (material.content.isEmpty()) return null
        if (start in 0 until end && end <= chapterContent.length) {
            if (chapterContent.substring(start, end) == material.content) {
                return intArrayOf(start, end)
            }
        }
        val index = chapterContent.indexOf(material.content)
        if (index >= 0) {
            return intArrayOf(index, (index + material.content.length).coerceAtMost(chapterContent.length))
        }
        return null
    }

    /**
     * 标签聚合:tag -> 使用数,按数量降序
     */
    fun aggregateTags(materials: List<Material>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (m in materials) {
            for (tag in m.tags) {
                if (tag.isBlank()) continue
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.entries
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    /**
     * 素材变动统一通知(阅读页划线回显刷新)
     */
    fun notifyChanged() {
        postEvent(EventBus.MATERIALS_CHANGED, true)
    }

    /* ---------------------------------- JSON ---------------------------------- */

    fun toJson(list: List<Material>): String = GSON.toJson(list)

    /**
     * 防御式解析:字段缺失/为 null 时取默认值,避免外部文件解析崩溃
     */
    fun fromJson(text: String): List<Material> {
        val result = arrayListOf<Material>()
        runCatching {
            val array = JsonParser.parseString(text).asJsonArray
            for (element in array) {
                if (element.isJsonObject) {
                    result.add(fromJsonObject(element.asJsonObject))
                }
            }
        }
        return result
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString ?: ""

    private fun JsonObject.longValue(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong ?: 0L

    private fun JsonObject.intValue(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong?.toInt() ?: 0

    private fun JsonObject.boolValue(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asBoolean ?: false

    private fun JsonObject.tagList(): List<String> {
        val element = get("tags") ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray
            .filter { it.isJsonPrimitive }
            .map { it.asString }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun fromJsonObject(o: JsonObject): Material = Material(
        id = o.longValue("id"),
        bookName = o.str("bookName"),
        bookAuthor = o.str("bookAuthor"),
        bookUrl = o.str("bookUrl"),
        chapterIndex = o.intValue("chapterIndex"),
        chapterPos = o.intValue("chapterPos"),
        chapterPosEnd = o.intValue("chapterPosEnd"),
        chapterName = o.str("chapterName"),
        content = o.str("content"),
        note = o.str("note"),
        tags = o.tagList(),
        favorite = o.boolValue("favorite"),
        createTime = o.longValue("createTime").takeIf { it > 0 } ?: System.currentTimeMillis(),
        updateTime = o.longValue("updateTime").takeIf { it > 0 } ?: System.currentTimeMillis(),
        deletedAt = o.longValue("deletedAt")
    )

    /* --------------------------------- Markdown -------------------------------- */

    fun toMarkdown(list: List<Material>): String {
        val sb = StringBuilder()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("# 素材库\n\n")
        sb.append("> 导出时间:").append(dateFmt.format(Date())).append('\n')
        sb.append("> 共 ").append(list.size).append(" 条\n\n")
        list.groupBy { it.bookKey() }.forEach { (_, bookMaterials) ->
            val first = bookMaterials.first()
            sb.append("## 《").append(first.bookName).append("》 ")
                .append(first.bookAuthor.ifBlank { "佚名" }).append("\n\n")
            bookMaterials.sortedBy { it.chapterIndex }.forEach { m ->
                sb.append("### ").append(m.chapterName.ifBlank { "未定位章节" }).append('\n')
                // 隐性元数据注释,保证自家导出 MD 可无损回流
                sb.append("<!-- material ")
                    .append(GSON.toJson(mapOf(
                        "bookUrl" to m.bookUrl,
                        "chapterIndex" to m.chapterIndex,
                        "chapterPos" to m.chapterPos,
                        "chapterPosEnd" to m.chapterPosEnd,
                        "createTime" to m.createTime,
                        "favorite" to m.favorite
                    )))
                    .append(" -->\n")
                m.content.split('\n').forEach { line ->
                    sb.append("> ").append(line).append('\n')
                }
                sb.append('\n')
                if (m.note.isNotBlank()) {
                    sb.append("- 批注:").append(m.note.replace("\n", " ")).append('\n')
                }
                if (m.tags.isNotEmpty()) {
                    sb.append("- 标签:").append(m.tags.joinToString(" ") { "#$it" }).append('\n')
                }
                sb.append("- 收录:").append(dateFmt.format(Date(m.createTime))).append("\n\n")
            }
        }
        return sb.toString()
    }

    /**
     * 解析自家导出的 MD(尽力兼容通用格式;定位信息缺失时偏移置 0)
     */
    fun fromMarkdown(text: String): List<Material> {
        val result = arrayListOf<Material>()
        var bookName = ""
        var bookAuthor = ""
        var chapterName = ""
        val contentLines = arrayListOf<String>()
        var note = ""
        val tags = arrayListOf<String>()
        var meta: JsonObject? = null

        fun flush() {
            val contentText = contentLines.joinToString("\n").trim()
            if (contentText.isNotEmpty()) {
                val m = meta
                result.add(
                    Material(
                        bookName = bookName,
                        bookAuthor = bookAuthor,
                        bookUrl = m?.str("bookUrl") ?: "",
                        chapterIndex = m?.intValue("chapterIndex") ?: 0,
                        chapterPos = m?.intValue("chapterPos") ?: 0,
                        chapterPosEnd = m?.intValue("chapterPosEnd") ?: contentText.length,
                        chapterName = chapterName,
                        content = contentText,
                        note = note,
                        tags = tags.distinct(),
                        favorite = m?.boolValue("favorite") ?: false,
                        createTime = m?.longValue("createTime")?.takeIf { it > 0 }
                            ?: System.currentTimeMillis()
                    )
                )
            }
            contentLines.clear()
            note = ""
            tags.clear()
            meta = null
        }

        val bookRegex = Regex("^##\\s*[《\\[]?(.+?)[》\\]]?\\s*(.*)$")
        val tagRegex = Regex("#([^#\\s]+)")
        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd()
            when {
                line.startsWith("## ") -> {
                    flush()
                    bookRegex.find(line.substring(3))?.let {
                        bookName = it.groupValues[1].trim()
                        bookAuthor = it.groupValues[2].trim()
                    }
                    chapterName = ""
                }

                line.startsWith("### ") -> {
                    flush()
                    chapterName = line.substring(4).trim()
                }

                line.startsWith("> ") || line == ">" -> {
                    if (line.length > 2) {
                        contentLines.add(line.substring(2))
                    }
                }

                line.startsWith("<!-- material ") -> {
                    runCatching {
                        meta = JsonParser.parseString(
                            line.removePrefix("<!-- material ").removeSuffix(" -->")
                        ).asJsonObject
                    }
                }

                line.startsWith("- 批注:") || line.startsWith("* 批注:") -> {
                    note = line.substringAfter(":").trim()
                }

                line.startsWith("- 标签:") || line.startsWith("* 标签:") -> {
                    tagRegex.findAll(line.substringAfter(":")).forEach {
                        tags.add(it.groupValues[1])
                    }
                }

                line.startsWith("- 收录:") || line.startsWith("* 收录:") -> {
                    // 收录时间已由 meta 注释承载,忽略
                }
            }
        }
        flush()
        return result
    }

    /* ------------------------------- 标签批量操作 ------------------------------- */

    /**
     * 为指定素材追加标签
     */
    suspend fun addTags(ids: List<Long>, newTags: List<String>) = withContext(IO) {
        val validTags = newTags.filter { it.isNotBlank() }
        if (validTags.isEmpty() || ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        appDb.runInTransaction {
            for (id in ids) {
                val material = appDb.materialDao.get(id) ?: continue
                val tags = (material.tags + validTags).distinct()
                if (tags != material.tags) {
                    appDb.materialDao.update(material.copy(tags = tags, updateTime = now))
                }
            }
        }
        notifyChanged()
    }

    /**
     * 重命名标签(目标已存在时等效合并)
     */
    suspend fun renameTag(oldTag: String, newTag: String) = withContext(IO) {
        if (oldTag == newTag || newTag.isBlank()) return@withContext
        val materials = appDb.materialDao.getActive().filter { it.tags.contains(oldTag) }
        if (materials.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        appDb.runInTransaction {
            for (material in materials) {
                appDb.materialDao.update(
                    material.copy(tags = material.tags.map { if (it == oldTag) newTag else it }.distinct(), updateTime = now)
                )
            }
        }
        notifyChanged()
    }

    /**
     * 从全部素材移除标签(素材本身保留)
     */
    suspend fun removeTag(tag: String) = withContext(IO) {
        val materials = appDb.materialDao.getActive().filter { it.tags.contains(tag) }
        if (materials.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        appDb.runInTransaction {
            for (material in materials) {
                appDb.materialDao.update(
                    material.copy(tags = material.tags - tag, updateTime = now)
                )
            }
        }
        notifyChanged()
    }

    /* ---------------------------------- 导入 ---------------------------------- */

    fun fromBookmark(bookmark: Bookmark): Material = Material(
        bookName = bookmark.bookName,
        bookAuthor = bookmark.bookAuthor,
        chapterIndex = bookmark.chapterIndex,
        chapterPos = bookmark.chapterPos,
        chapterPosEnd = bookmark.chapterPos + bookmark.bookText.length,
        chapterName = bookmark.chapterName,
        content = bookmark.bookText,
        note = bookmark.content,
        tags = listOf("书签")
    )

    /**
     * 将全部书签转换为素材(SKIP_DUPLICATES 去重)
     */
    suspend fun importFromBookmarks(): ImportResult = withContext(IO) {
        val bookmarks = appDb.bookmarkDao.all
        import(bookmarks.map { fromBookmark(it) }, ImportStrategy.SKIP_DUPLICATES)
    }

    /**
     * 按策略导入素材(去重键:书名+作者+归一化原文)
     */
    suspend fun import(list: List<Material>, strategy: ImportStrategy): ImportResult =
        withContext(IO) {
            val now = System.currentTimeMillis()
            val existing = appDb.materialDao.getActive().associateBy { it.dedupKey() }
            var imported = 0
            var skipped = 0
            var merged = 0
            appDb.runInTransaction {
                for (material in list) {
                    if (material.content.isBlank()) {
                        skipped++
                        continue
                    }
                    val dup = existing[material.dedupKey()]
                    if (dup == null || strategy == ImportStrategy.IMPORT_ALL) {
                        appDb.materialDao.insert(
                            material.copy(
                                id = 0,
                                deletedAt = 0,
                                updateTime = now,
                                createTime = material.createTime.takeIf { it > 0 } ?: now
                            )
                        )
                        imported++
                    } else if (strategy == ImportStrategy.MERGE) {
                        val newNote = dup.note.ifEmpty { material.note }
                        val newTags = (dup.tags + material.tags).distinct()
                        if (newNote != dup.note || newTags != dup.tags) {
                            appDb.materialDao.update(
                                dup.copy(note = newNote, tags = newTags, updateTime = now)
                            )
                            merged++
                        } else {
                            skipped++
                        }
                    } else {
                        skipped++
                    }
                }
            }
            if (imported > 0 || merged > 0) {
                notifyChanged()
            }
            ImportResult(list.size, imported, skipped, merged)
        }
}
