package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.Material
import kotlinx.coroutines.flow.Flow

@Dao
interface MaterialDao {

    @get:Query("select * from materials order by createTime desc")
    val all: List<Material>

    @Query("select * from materials where deletedAt = 0 order by createTime desc")
    fun flowActive(): Flow<List<Material>>

    @Query("select * from materials where deletedAt = 0 order by createTime desc")
    fun getActive(): List<Material>

    @Query("select * from materials where id = :id")
    fun get(id: Long): Material?

    @Query("select * from materials where bookName = :bookName and bookAuthor = :bookAuthor and deletedAt = 0 order by createTime desc")
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<Material>>

    @Query("select * from materials where bookName = :bookName and bookAuthor = :bookAuthor and chapterIndex = :chapterIndex and deletedAt = 0 order by chapterPos asc")
    fun getActiveByChapter(bookName: String, bookAuthor: String, chapterIndex: Int): List<Material>

    @Query("select * from materials where deletedAt = 0 and (content like '%' || :key || '%'" +
        " or note like '%' || :key || '%'" +
        " or bookName like '%' || :key || '%'" +
        " or chapterName like '%' || :key || '%'" +
        " or tags like '%' || :key || '%') order by createTime desc")
    fun flowSearch(key: String): Flow<List<Material>>

    @Query("select count(*) from materials where bookName = :bookName and bookAuthor = :bookAuthor" +
        " and content = :content and deletedAt = 0")
    fun countSame(bookName: String, bookAuthor: String, content: String): Int

    @Query("select * from materials where deletedAt > 0 order by deletedAt desc")
    fun flowTrash(): Flow<List<Material>>

    @get:Query("select * from materials where deletedAt > 0 order by deletedAt desc")
    val trash: List<Material>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg materials: Material)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReturnId(material: Material): Long

    @Update
    fun update(vararg materials: Material)

    @Delete
    fun delete(vararg materials: Material)

    @Query("update materials set deletedAt = :time, updateTime = :time where id in (:ids)")
    fun moveToTrash(ids: List<Long>, time: Long)

    @Query("update materials set deletedAt = 0, updateTime = :time where id in (:ids)")
    fun restore(ids: List<Long>, time: Long)

    @Query("delete from materials where id in (:ids)")
    fun purge(ids: List<Long>)

    @Query("delete from materials where deletedAt > 0")
    fun clearTrash()
}
