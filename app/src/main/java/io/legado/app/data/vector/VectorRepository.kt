package io.legado.app.data.vector

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChatChunk
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SQLite 向量库(D3:不引 ObjectBox/sqldelight,一书一索引;BLOB + 内存余弦)。
 *
 * 存取:
 *   - vector 以 ByteBuffer 小端包装 float[] 落 BLOB(AiChatChunk.vector);
 *   - saveBookIndex 先删后插(整书重建,幂等);
 *   - search 加载该书全部 chunk 后内存余弦 topK(万级 chunk 毫秒级,无需近似索引)。
 *
 * 状态(Prefs,不入备份):
 *   - "al-embedding-{bookId}" → JSON:state / progress / reason / textVersion / dim
 *   state: ready | indexing | blocked | failed
 */
object VectorRepository {

    private const val PREF_PREFIX = "al-embedding-"

    enum class State { READY, INDEXING, BLOCKED, FAILED }

    data class Status(
        val state: State,
        val progress: Int = 0,
        val reason: String = "",
        val textVersion: Long = 0L,
        val dim: Int = 0
    )

    data class IndexedChunk(val chunk: AiChatChunk, val score: Float)

    /* ------------------------------ 编码/解码 ------------------------------ */

    fun encode(vector: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4) { bb.getFloat() }
        return out
    }

    /** 维度对齐:超出截断,不足补 0(首次接不同维度模型时兜底) */
    fun conform(vector: FloatArray, dim: Int): FloatArray {
        if (vector.size == dim) return vector
        return FloatArray(dim) { i -> if (i < vector.size) vector[i] else 0f }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat()
    }

    /* ------------------------------ 索引存取 ------------------------------ */

    /**
     * 整书重建索引(先删后插)。
     * @param entries 每项:(chapterIndex, charStart, charEnd, text, vector)
     */
    fun saveBookIndex(
        bookId: Long,
        textVersion: Long,
        entries: List<IndexEntry>,
        dim: Int
    ) {
        appDb.aiChatChunkDao.deleteByBook(bookId)
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val ready = entries.map { (chapterIndex, charStart, charEnd, text, vec) ->
            AiChatChunk(
                bookId = bookId,
                chapterIndex = chapterIndex,
                charStart = charStart,
                charEnd = charEnd,
                text = text,
                vector = encode(conform(vec, dim.coerceAtLeast(vec.size))),
                textVersion = textVersion,
                createTime = now
            )
        }
        appDb.aiChatChunkDao.insertAll(ready)
    }

    data class IndexEntry(
        val chapterIndex: Int,
        val charStart: Int,
        val charEnd: Int,
        val text: String,
        val vector: FloatArray
    )

    /** 语义检索:全书内存余弦 topK */
    fun search(bookId: Long, queryVec: FloatArray, topK: Int = 5): List<IndexedChunk> {
        val chunks = appDb.aiChatChunkDao.getByBook(bookId)
        if (chunks.isEmpty()) return emptyList()
        return chunks
            .map { c -> IndexedChunk(c, cosine(queryVec, decode(c.vector))) }
            .sortedByDescending { it.score }
            .take(topK)
    }

    fun bookIndexed(bookId: Long): Boolean = appDb.aiChatChunkDao.countByBook(bookId) > 0

    /* ------------------------------ 状态 ------------------------------ */

    fun status(bookId: Long): Status {
        val json = appCtx.getPrefString(PREF_PREFIX + bookId) ?: return Status(State.READY, 0, "未建立")
        return kotlin.runCatching {
            val o = GSON.fromJson(json, com.google.gson.JsonObject::class.java)
            val state = when (o.get("state")?.asString) {
                "ready" -> State.READY
                "indexing" -> State.INDEXING
                "blocked" -> State.BLOCKED
                "failed" -> State.FAILED
                else -> State.READY
            }
            Status(
                state = state,
                progress = o.get("progress")?.asInt ?: 0,
                reason = o.get("reason")?.asString ?: "",
                textVersion = o.get("textVersion")?.asLong ?: 0L,
                dim = o.get("dim")?.asInt ?: 0
            )
        }.getOrDefault(Status(State.READY, 0, "未建立"))
    }

    fun saveStatus(bookId: Long, status: Status) {
        val o = com.google.gson.JsonObject().apply {
            addProperty("state", status.state.name.lowercase())
            addProperty("progress", status.progress)
            addProperty("reason", status.reason)
            addProperty("textVersion", status.textVersion)
            addProperty("dim", status.dim)
        }
        appCtx.putPrefString(PREF_PREFIX + bookId, GSON.toJson(o))
    }

    fun clearStatus(bookId: Long) {
        appCtx.removePref(PREF_PREFIX + bookId)
    }
}