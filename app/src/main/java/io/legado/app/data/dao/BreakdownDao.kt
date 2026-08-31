package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.data.entities.BreakdownChapter
import io.legado.app.data.entities.BreakdownSegment
import io.legado.app.data.entities.BreakdownTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface BreakdownTemplateDao {

    @get:Query("select * from breakdownTemplates order by isBuiltin desc, updateTime desc")
    val all: List<BreakdownTemplate>

    @Query("select * from breakdownTemplates order by isBuiltin desc, updateTime desc")
    fun flowAll(): Flow<List<BreakdownTemplate>>

    @Query("select * from breakdownTemplates where id = :id")
    fun get(id: Long): BreakdownTemplate?

    @Query("select * from breakdownTemplates where name = :name")
    fun getByName(name: String): BreakdownTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg templates: BreakdownTemplate): List<Long>

    @Update
    fun update(template: BreakdownTemplate)

    @Delete
    fun delete(template: BreakdownTemplate)
}

/**
 * 档案 + 章进度统计(已拆/草稿)
 */
data class BreakdownWithProgress(
    @Embedded val breakdown: BookBreakdown,
    @Relation(parentColumn = "id", entityColumn = "breakdownId")
    val chapters: List<BreakdownChapter>
) {
    val chapterCount: Int get() = chapters.size
    val confirmedCount: Int get() = chapters.count { it.status == 2 }
    val draftCount: Int get() = chapters.count { it.status == 1 }
}

data class BreakdownChapterWithSegments(
    @Embedded val chapter: BreakdownChapter,
    @Relation(parentColumn = "id", entityColumn = "chapterId")
    val segments: List<BreakdownSegment>
) {
    val segmentCount: Int get() = segments.size
}

@Dao
interface BookBreakdownDao {

    @get:Query("select * from breakdowns order by updateTime desc")
    val all: List<BookBreakdown>

    @Transaction
    @Query("select * from breakdowns where deletedAt = 0 order by updateTime desc")
    fun flowWithProgress(): Flow<List<BreakdownWithProgress>>

    @Query("select * from breakdowns where id = :id")
    fun get(id: Long): BookBreakdown?

    @Query("select * from breakdowns where bookName = :bookName and bookAuthor = :bookAuthor and deletedAt = 0")
    fun getActiveByBook(bookName: String, bookAuthor: String): BookBreakdown?

    @Query("select * from breakdowns where bookName = :bookName and bookAuthor = :bookAuthor")
    fun getByBook(bookName: String, bookAuthor: String): BookBreakdown?

    @Query("select * from breakdowns where bookUrl = :bookUrl and deletedAt = 0 limit 1")
    fun getByUrl(bookUrl: String): BookBreakdown?

    @Query("select * from breakdowns where bookUrl = :bookUrl and deletedAt = 0 order by updateTime desc")
    fun flowByBookUrl(bookUrl: String): Flow<List<BookBreakdown>>

    @Query("select * from breakdowns where deletedAt > 0 order by deletedAt desc")
    fun flowTrash(): Flow<List<BookBreakdown>>

    @get:Query("select * from breakdowns where deletedAt > 0 order by deletedAt desc")
    val trash: List<BookBreakdown>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg breakdowns: BookBreakdown): List<Long>

    @Update
    fun update(breakdown: BookBreakdown)

    @Query("update breakdowns set deletedAt = :time, updateTime = :time where id = :id")
    fun softDelete(id: Long, time: Long)

    @Query("update breakdowns set deletedAt = 0, updateTime = :time where id = :id")
    fun restore(id: Long, time: Long)

    @Query("update breakdowns set deletedAt = 0, updateTime = :time where deletedAt > 0")
    fun restoreAll(time: Long)

    @Query("delete from breakdowns where id = :id")
    fun purge(id: Long)

    @Query("delete from breakdowns where deletedAt > 0")
    fun clearTrash()

    /**
     * 彻底删除档案时级联删除章节与段落
     */
    @Query("delete from breakdownChapters where breakdownId = :breakdownId")
    fun purgeChapters(breakdownId: Long)

    @Query("delete from breakdownSegments where chapterId in (select id from breakdownChapters where breakdownId = :breakdownId)")
    fun purgeSegments(breakdownId: Long)
}

@Dao
interface BreakdownChapterDao {

    @get:Query("select * from breakdownChapters order by breakdownId asc, chapterIndex asc")
    val all: List<BreakdownChapter>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(vararg chapters: BreakdownChapter): List<Long>

    @Query("select * from breakdownChapters where id = :id")
    fun get(id: Long): BreakdownChapter?

    @Query("select * from breakdownChapters where breakdownId = :breakdownId order by chapterIndex asc")
    fun getByBreakdown(breakdownId: Long): List<BreakdownChapter>

    @Query("select * from breakdownChapters where breakdownId = :breakdownId order by chapterIndex asc")
    fun flowByBreakdown(breakdownId: Long): Flow<List<BreakdownChapter>>

    @Query("select * from breakdownChapters where breakdownId = :breakdownId and chapterIndex = :chapterIndex")
    fun getByBreakdownAndIndex(breakdownId: Long, chapterIndex: Int): BreakdownChapter?

    @Transaction
    @Query("select * from breakdownChapters where breakdownId = :breakdownId order by chapterIndex asc")
    fun flowAllByBreakdown(breakdownId: Long): Flow<List<BreakdownChapterWithSegments>>

    @Query("select count(*) from breakdownChapters where breakdownId = :breakdownId and status = :status")
    fun countByStatus(breakdownId: Long, status: Int): Int

    @Update
    fun update(vararg chapters: BreakdownChapter)

    @Query("delete from breakdownChapters where breakdownId = :breakdownId")
    fun deleteByBreakdown(breakdownId: Long)
}

@Dao
interface BreakdownSegmentDao {

    @get:Query("select * from breakdownSegments order by chapterId asc, sortOrder asc")
    val all: List<BreakdownSegment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(vararg segments: BreakdownSegment): List<Long>

    @Query("select * from breakdownSegments where id = :id")
    fun get(id: Long): BreakdownSegment?

    @Query("select * from breakdownSegments where chapterId = :chapterId order by sortOrder asc")
    fun flowByChapter(chapterId: Long): Flow<List<BreakdownSegment>>

    @Query("select * from breakdownSegments where chapterId = :chapterId order by sortOrder asc")
    fun getByChapter(chapterId: Long): List<BreakdownSegment>

    @Query("select * from breakdownSegments where chapterId in (select id from breakdownChapters where breakdownId = :breakdownId) order by sortOrder asc")
    fun getByBreakdown(breakdownId: Long): List<BreakdownSegment>

    @Update
    fun update(vararg segments: BreakdownSegment)

    @Query("delete from breakdownSegments where id in (:ids)")
    fun deleteByIds(ids: List<Long>)

    @Query("delete from breakdownSegments where chapterId = :chapterId")
    fun deleteByChapter(chapterId: Long)

    @Query("update breakdownSegments set label = :label, updateTime = :time where id in (:ids)")
    fun updateLabels(ids: List<Long>, label: String, time: Long)
}