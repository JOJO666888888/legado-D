package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 章节语义分块(AiChatChunk):RAG 向量索引的基本单元。
 *
 * 一本书一个索引(bookId 维度);chunk 不跨段落,保存其在章节净化正文中的
 * [charStart, charEnd) UTF-16 偏移(与文本换行口径一致,可反查原句)。
 * vector 为 float[] 的 BLOB 包装(ByteBuffer 小端),维度由向量模型决定。
 *
 * textVersion 为粗版内容指纹(章节数 xor 书籍 updateTime):不一致时调用方触发重建该书索引。
 */
@Entity(
    tableName = "aiChatChunks",
    indices = [Index(value = ["bookId", "chapterIndex"])]
)
data class AiChatChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long = 0L,
    /** 所属章节索引(与 BookChapter.index 一致) */
    val chapterIndex: Int = 0,
    /** 块在章节净化正文中的起始 UTF-16 偏移(含) */
    val charStart: Int = 0,
    /** 块在章节净化正文中的结束 UTF-16 偏移(不含) */
    val charEnd: Int = 0,
    val text: String = "",
    val vector: ByteArray = ByteArray(0),
    /** 粗版 content 指纹:章节数 xor 书籍 updateTime;变化时重建该 book 索引 */
    val textVersion: Long = 0L,
    val createTime: Long = System.currentTimeMillis()
)