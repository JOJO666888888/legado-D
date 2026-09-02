package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiChatChunk

@Dao
interface AiChatChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(chunks: List<AiChatChunk>)

    @Query("select * from aiChatChunks where bookId = :bookId order by chapterIndex asc, charStart asc")
    fun getByBook(bookId: Long): List<AiChatChunk>

    @Query("select count(*) from aiChatChunks where bookId = :bookId")
    fun countByBook(bookId: Long): Int

    @Query("delete from aiChatChunks where bookId = :bookId")
    fun deleteByBook(bookId: Long)
}