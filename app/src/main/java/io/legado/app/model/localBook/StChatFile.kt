package io.legado.app.model.localBook

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.ai.SillyTavernCardParser
import io.legado.app.help.ai.StChatParser
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * SillyTavern 对话书籍(本地生成内容型书籍,与 TextFile/EpubFile 同级接入 LocalBook 分发)。
 *
 * 映射规则: 角色卡 = 一本书, 一场对话(jsonl 存档) = 一卷, 一轮对话 = 一章。
 *
 * 存储结构(应用私有目录,导入时拷贝,自包含):
 *   {externalFiles}/stChat/{cardKey}/chats/{存档文件名}.jsonl   聊天存档副本
 *   {externalFiles}/stChat/{cardKey}/card.{png|json}           角色卡副本(封面/简介来源)
 *
 * 身份与增量同步:
 *   bookUrl  = "st_chat://{md5(角色名)}"            → 同一角色始终对应同一本书
 *   章节 url = "st_chat://{md5(存档文件名)}/{轮次号}" → 重导入时已有章节身份不变,
 *   新增轮次以新 url 追加;阅读进度按旧进度章节的 url 精确回填,不受目录重排影响。
 */
object StChatFile {

    private const val URL_SCHEME = "${BookType.stChatTag}://"

    data class ImportResult(val books: List<Book>, val errorCount: Int)

    /* ------------------------------ 导入 ------------------------------ */

    /**
     * 导入 ST 数据(.jsonl 聊天存档,可混选 .png/.json 角色卡)。
     * 每个角色生成/更新一本书;角色卡提供封面与简介;同名存档重复导入即增量更新。
     */
    suspend fun importStData(context: Context, uris: List<Uri>): ImportResult =
        withContext(Dispatchers.IO) {
            var errorCount = 0
            val charNames = LinkedHashSet<String>()
            for (uri in uris) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    errorCount++
                    continue
                }
                val fileName = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
                val chars = importItem(fileName, bytes)
                if (chars == null) {
                    errorCount++
                } else {
                    charNames.addAll(chars)
                }
            }
            finishImport(charNames, errorCount)
        }

    /**
     * 从 ST 网关在线同步导入(已拉取的存档字节 + 角色卡封面字节)。
     * 走与本地导入完全相同的链路(建书/更新目录/进度保护)。
     */
    suspend fun importFromGateway(
        chatFiles: List<Pair<String, ByteArray>>,
        covers: Map<String, ByteArray> = emptyMap()
    ): ImportResult = withContext(Dispatchers.IO) {
        var errorCount = 0
        val charNames = LinkedHashSet<String>()
        for ((fileName, bytes) in chatFiles) {
            val chars = importItem(fileName, bytes)
            if (chars == null) {
                errorCount++
            } else {
                charNames.addAll(chars)
            }
        }
        // 角色卡封面(gateway 的 PNG 原图): 写入对应书籍的 card.png 供封面与再导入使用
        for ((charName, png) in covers) {
            runCatching {
                val dir = FileUtils.createFolderIfNotExist(
                    cardDir(bookUrlOf(charName)).absolutePath
                )
                File(dir, "card.png").writeBytes(png)
                charNames.add(charName)
            }.onFailure {
                AppLog.put("ST封面保存失败($charName)\n${it.localizedMessage}", it)
            }
        }
        finishImport(charNames, errorCount)
    }

    /**
     * 导入单个条目(.jsonl 存档或 .png/.json 角色卡副本)。
     * @return 涉及的角色名集合;null=该条目无效
     */
    private fun importItem(fileName: String, bytes: ByteArray): Set<String>? {
        return when {
            fileName.endsWith(".jsonl", true) -> {
                val chat = StChatParser.parse(bytes)
                    ?: return null
                if (chat.rounds.isEmpty()) return null
                val dir = FileUtils.createFolderIfNotExist(
                    chatDir(chat.meta.charName).absolutePath
                )
                val saved = runCatching {
                    File(dir, fileName).writeBytes(bytes)
                }.onFailure {
                    AppLog.put("ST存档保存失败\n${it.localizedMessage}", it)
                }.isSuccess
                if (saved) setOf(chat.meta.charName) else null
            }

            fileName.endsWith(".png", true) || fileName.endsWith(".json", true) -> {
                // 角色卡副本: 封面与简介来源;独立于存档导入,等待同名角色存档
                val card = SillyTavernCardParser.parse(bytes) ?: return null
                runCatching {
                    val dir = FileUtils.createFolderIfNotExist(
                        cardDir(bookUrlOf(card.name)).absolutePath
                    )
                    val ext = if (fileName.endsWith(".png", true)) "png" else "json"
                    File(dir, "card.$ext").writeBytes(bytes)
                }.onFailure {
                    AppLog.put("ST角色卡保存失败\n${it.localizedMessage}", it)
                }
                emptySet()
            }

            else -> null
        }
    }

    /** 建书/更新书并广播 */
    private suspend fun finishImport(charNames: Set<String>, errorCountIn: Int): ImportResult {
        var errorCount = errorCountIn
        val books = mutableListOf<Book>()
        for (charName in charNames) {
            runCatching {
                books.add(importBook(charName))
            }.onFailure {
                AppLog.put("ST书籍导入失败($charName)\n${it.localizedMessage}", it)
                errorCount++
            }
        }
        if (books.isNotEmpty()) {
            postEvent(EventBus.AI_AGENT_ST_BOOK_CHANGED, books.size.toString())
        }
        return ImportResult(books, errorCount)
    }

    /** 建书或更新书: 角色卡元数据补全(封面/简介/作者) + 重建目录(进度保护) */
    private fun importBook(charName: String): Book {
        val bookUrl = bookUrlOf(charName)
        val oldBook = appDb.bookDao.getBook(bookUrl)
        val cardFile = findCardFile(bookUrl)
        val card = cardFile?.let {
            runCatching { SillyTavernCardParser.parse(it.readBytes()) }.getOrNull()
        }
        val book = oldBook ?: Book(
            bookUrl = bookUrl,
            origin = BookType.stChatTag,
            originName = charName,
            name = charName,
            author = uniqueAuthor(charName, card?.creator),
            type = BookType.text or BookType.local,
            canUpdate = false,
            order = appDb.bookDao.minOrder - 1
        )
        // 元数据补全: 已有书籍只补空缺,不覆盖用户可能调整过的字段
        if (card != null) {
            if (card.description.isNotBlank() && book.intro.isNullOrBlank()) {
                book.intro = card.description.take(300)
            }
            if (book.coverUrl.isNullOrEmpty() && cardFile != null) {
                runCatching {
                    val coverPath = LocalBook.getCoverPath(book)
                    FileUtils.createFileIfNotExist(coverPath).writeBytes(cardFile.readBytes())
                    book.coverUrl = coverPath
                }.onFailure {
                    AppLog.put("ST封面保存失败\n${it.localizedMessage}", it)
                }
            }
        }
        if (book.intro.isNullOrBlank()) {
            book.intro = "SillyTavern 角色对话存档"
        }
        if (oldBook == null) {
            appDb.bookDao.insert(book)
        }
        rebuildChapters(book)
        return book
    }

    /** (name, author) 唯一索引防冲突: 与他书重名时为作者追加序号 */
    private fun uniqueAuthor(name: String, creator: String?): String {
        val base = creator?.takeIf { it.isNotBlank() } ?: "SillyTavern"
        if (!appDb.bookDao.has(name, base)) return base
        var index = 2
        while (appDb.bookDao.has(name, "$base($index)")) index++
        return "$base($index)"
    }

    /** 重建目录并按旧进度章节的 url 回填阅读进度(增量导入不丢进度) */
    private fun rebuildChapters(book: Book) {
        val oldChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val oldDurUrl = oldChapters.getOrNull(book.durChapterIndex)?.url
        val list = getChapterList(book)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*list.toTypedArray())
        if (oldDurUrl != null) {
            val newIndex = list.indexOfFirst { it.url == oldDurUrl }
            if (newIndex >= 0) {
                book.durChapterIndex = newIndex
            }
        }
        // 进度章节不允许落在卷条目上(卷无正文),向后找最近的正文章节
        var dur = book.durChapterIndex.coerceIn(0, list.lastIndex)
        if (list[dur].isVolume) {
            dur = list.drop(dur + 1).indexOfFirst { !it.isVolume }
                .let { if (it >= 0) dur + 1 + it else list.indexOfLast { !it.isVolume } }
                .coerceAtLeast(0)
        }
        book.durChapterIndex = dur
        book.durChapterTitle = list.getOrNull(dur)?.title
        appDb.bookDao.update(book)
    }

    /* ------------------------------ 目录与正文 ------------------------------ */

    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val list = arrayListOf<BookChapter>()
        var totalChars = 0
        for (file in chatFiles(book.bookUrl)) {
            val chat = parseCached(file) ?: continue
            val chatKey = MD5Utils.md5Encode16(file.name)
            // 卷条目(一场对话)
            list.add(
                BookChapter(
                    url = "$URL_SCHEME$chatKey",
                    title = file.nameWithoutExtension.ifBlank { "新对话" },
                    isVolume = true,
                    baseUrl = book.bookUrl,
                    bookUrl = book.bookUrl
                )
            )
            // 轮条目(一轮一章)
            chat.rounds.forEachIndexed { roundIndex, round ->
                totalChars += round.userMessage?.mes?.length ?: 0
                round.charMessages.forEach { totalChars += it.mes.length }
                list.add(
                    BookChapter(
                        url = "$URL_SCHEME$chatKey/$roundIndex",
                        title = roundTitleOf(round, roundIndex),
                        baseUrl = book.bookUrl,
                        bookUrl = book.bookUrl
                    )
                )
            }
        }
        if (list.none { !it.isVolume }) {
            throw TocEmptyException("SillyTavern 存档为空")
        }
        list.forEachIndexed { index, chapter -> chapter.index = index }
        book.durChapterTitle = list.getOrNull(book.durChapterIndex)?.title ?: list.first().title
        book.latestChapterTitle = list.last { !it.isVolume }.title
        book.totalChapterNum = list.size
        book.wordCount = StringUtils.wordCountFormat(totalChars)
        book.latestChapterTime = System.currentTimeMillis()
        return list
    }

    fun getContent(book: Book, chapter: BookChapter): String? {
        if (chapter.isVolume) return ""
        val segments = chapter.url.removePrefix(URL_SCHEME).split("/")
        if (segments.size != 2) return null
        val chatKey = segments[0]
        val roundIndex = segments[1].toIntOrNull() ?: return null
        val file = chatFiles(book.bookUrl).firstOrNull {
            MD5Utils.md5Encode16(it.name) == chatKey
        } ?: return null
        val chat = parseCached(file) ?: return null
        val round = chat.rounds.getOrNull(roundIndex) ?: return null
        return renderRound(round)
    }

    /** 删除该书的源数据(应用内拷贝的存档与角色卡) */
    fun deleteSource(book: Book) {
        runCatching {
            cardDir(book.bookUrl).takeIf { it.exists() }?.deleteRecursively()
        }.onFailure {
            AppLog.put("删除ST对话源失败\n${it.localizedMessage}", it)
        }
    }

    /* ------------------------------ 内部工具 ------------------------------ */

    private fun bookUrlOf(charName: String): String = "$URL_SCHEME${MD5Utils.md5Encode16(charName)}"

    private fun cardDir(bookUrl: String): File =
        File(File(appCtx.externalFiles, "stChat"), bookUrl.removePrefix(URL_SCHEME))

    private fun chatDir(charName: String): File =
        File(cardDir(bookUrlOf(charName)), "chats")

    private fun chatDirOfUrl(bookUrl: String): File = File(cardDir(bookUrl), "chats")

    private fun chatFiles(bookUrl: String): List<File> =
        chatDirOfUrl(bookUrl).listFiles { file -> file.name.endsWith(".jsonl", true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun findCardFile(bookUrl: String): File? {
        val dir = cardDir(bookUrl)
        return dir.listFiles()?.firstOrNull { it.name == "card.png" || it.name == "card.json" }
    }

    private fun roundTitleOf(round: StChatParser.StRound, roundIndex: Int): String {
        val number = roundIndex + 1
        val head = (round.userMessage?.mes ?: round.charMessages.firstOrNull()?.mes.orEmpty())
            .lineSequence().firstOrNull()?.trim().orEmpty()
            .replace("\\s+".toRegex(), " ")
        return if (head.isEmpty()) "第${number}轮" else "第${number}轮 ${head.take(16)}"
    }

    private fun renderRound(round: StChatParser.StRound): String {
        val sb = StringBuilder()
        round.userMessage?.let { msg ->
            sb.append("【").append(msg.name).append("】\n")
                .append(msg.mes.trim()).append("\n\n")
        }
        round.charMessages.forEachIndexed { index, msg ->
            sb.append("【").append(msg.name).append("】\n").append(msg.mes.trim())
            if (index != round.charMessages.lastIndex) sb.append("\n\n")
        }
        return sb.toString()
    }

    /** 单条内存缓存: 同一存档文件按 mtime 失效,避免每次翻章都重解析整个 jsonl */
    private class ChatCache(
        val file: File,
        val lastModified: Long,
        val chat: StChatParser.StChat
    )

    @Volatile
    private var cacheEntry: ChatCache? = null

    @Synchronized
    private fun parseCached(file: File): StChatParser.StChat? {
        val mtime = file.lastModified()
        cacheEntry?.let {
            if (it.file == file && it.lastModified == mtime) return it.chat
        }
        val chat = runCatching { StChatParser.parse(file.readBytes()) }.getOrNull() ?: return null
        cacheEntry = ChatCache(file, mtime, chat)
        return chat
    }
}
