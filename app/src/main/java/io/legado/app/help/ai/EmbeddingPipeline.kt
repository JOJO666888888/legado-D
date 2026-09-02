package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.vector.VectorRepository
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Embedding 流水线(M9):章节 → 切片 → 分批向量化 → SQLite 向量库。
 *
 * 纪律(D6):
 *   - EMBEDDING 角色未配置时返回状态 BLOCKED(不建索引、不报错),UI 提示即可;
 *   - 失败置 FAILED + reason,不阻塞主流程;网络层失败可由上层重试;
 *   - textVersion = 章节数 xor 书籍 updateTime,不一致自动重建(caller 判断)。
 */
object EmbeddingPipeline {

    /** 每批向量化的最大 chunk 数(多数 embedding 接口限 2048 tokens/项,取 ≤32 保守) */
    const val BATCH_SIZE = 32

    /** 用 bookUrl 派生稳定 Long 型 bookId(一书一索引;FNV-1a 64 避免 String.hashCode 碰撞) */
    fun embeddingBookId(bookUrl: String): Long {
        var h = 0xcbf29ce484222325UL
        for (c in bookUrl) {
            h = h xor c.code.toULong()
            h = h * 0x100000001b3UL
        }
        return h.toLong()
    }

    /** 粗版内容指纹:章节数 xor 书籍最新章节时间 */
    fun textVersion(book: Book, chapterCount: Int): Long =
        chapterCount.toLong() xor book.latestChapterTime

    /** 是否无需重建:已索引且指纹一致 */
    fun isFresh(book: Book, chapterCount: Int): Boolean {
        val bookId = embeddingBookId(book.bookUrl)
        if (!VectorRepository.bookIndexed(bookId)) return false
        return VectorRepository.status(bookId).textVersion == textVersion(book, chapterCount)
    }

    /**
     * 建立(或按指纹重建)全书语义索引。
     * @return 成功 null;失败返回可 toast 的错误文案
     */
    suspend fun embedBook(
        book: Book,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): String? {
        val bookId = embeddingBookId(book.bookUrl)
        val conf = AiModelResolver.resolve(AiModelResolver.Role.EMBEDDING)
        if (conf.baseUrl.isBlank() || conf.model.isBlank()) {
            VectorRepository.saveStatus(
                bookId,
                VectorRepository.Status(VectorRepository.State.BLOCKED, 0, "未配置向量模型(EMBEDDING 角色回退主配置也为空)")
            )
            return null
        }
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapters.isEmpty()) {
            VectorRepository.clearStatus(bookId)
            return "书籍《${book.name}》暂无章节"
        }
        // 指纹一致且已有索引 → 跳过
        val version = textVersion(book, chapters.size)
        if (isFresh(book, chapters.size)) return null

        VectorRepository.saveStatus(
            bookId,
            VectorRepository.Status(VectorRepository.State.INDEXING, 0, "", version, 0)
        )
        val entries = mutableListOf<VectorRepository.IndexEntry>()
        var dim = 0
        var done = 0
        try {
            // 阶段 1:切片(池化全部章节正文)
            val pending = mutableListOf<VectorRepository.IndexEntry>()
            chapters.forEach { ch ->
                currentCoroutineContext().ensureActive()
                val content = runCatching { BookHelp.getContent(book, ch) }.getOrNull() ?: ""
                if (content.isBlank()) return@forEach
                ChapterChunker.chunk(content).forEach { (start, piece) ->
                    pending.add(
                        VectorRepository.IndexEntry(
                            chapterIndex = ch.index,
                            charStart = start,
                            charEnd = start + piece.length,
                            text = piece,
                            vector = FloatArray(0)
                        )
                    )
                }
            }
            if (pending.isEmpty()) {
                VectorRepository.clearStatus(bookId)
                return "书籍《${book.name}》正文为空或未缓存"
            }
            // 阶段 2:分批向量化 + 落库
            val total = pending.size
            var idx = 0
            while (idx < total) {
                currentCoroutineContext().ensureActive()
                val batch = pending.subList(idx, minOf(idx + BATCH_SIZE, total))
                val vectors = SseStreamClient.embedTexts(
                    texts = batch.map { it.text },
                    baseUrl = conf.baseUrl,
                    apiKey = conf.apiKey,
                    model = conf.model
                )
                if (dim == 0) dim = vectors.firstOrNull()?.size ?: 0
                batch.forEachIndexed { i, e ->
                    entries.add(e.copy(vector = vectors[i]))
                }
                idx += batch.size
                done = idx
                onProgress(done, total)
                VectorRepository.saveStatus(
                    bookId,
                    VectorRepository.Status(VectorRepository.State.INDEXING, done * 100 / total, "", version, dim)
                )
            }
            VectorRepository.saveBookIndex(bookId, version, entries, dim)
            VectorRepository.saveStatus(
                bookId,
                VectorRepository.Status(VectorRepository.State.READY, 100, "", version, dim)
            )
            return null
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // 取消:保留已写入的索引(整书一致性交给下次指纹重建),状态回退 ready 前值
            VectorRepository.saveStatus(
                bookId,
                VectorRepository.Status(VectorRepository.State.FAILED, done * 100 / maxOf(done, 1), "已取消", version, dim)
            )
            throw ce
        } catch (t: Throwable) {
            VectorRepository.saveStatus(
                bookId,
                VectorRepository.Status(VectorRepository.State.FAILED, done * 100 / maxOf(done, 1), t.message ?: "未知错误", version, dim)
            )
            return if (t.message.isNullOrBlank()) "语义索引建立失败" else "语义索引失败:${t.message}"
        }
    }

    /** 查询文本向量(供语义检索;EMBEDDING 角色) */
    suspend fun embedQuery(query: String): FloatArray? {
        if (query.isBlank()) return null
        val conf = AiModelResolver.resolve(AiModelResolver.Role.EMBEDDING)
        if (conf.baseUrl.isBlank() || conf.model.isBlank()) return null
        return kotlin.runCatching {
            SseStreamClient.embedTexts(listOf(query), conf.baseUrl, conf.apiKey, conf.model).firstOrNull()
        }.getOrNull()
    }
}