package io.legado.app.ui.book.breakdown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BreakdownChapter
import io.legado.app.data.entities.BreakdownSegment
import io.legado.app.data.entities.BreakdownTemplate

class ChapterBreakdownViewModel(application: Application) : AndroidViewModel(application) {

    fun getBreakdown(id: Long): BookBreakdown? = appDb.bookBreakdownDao.get(id)

    fun getBook(bookUrl: String): Book? = appDb.bookDao.getBook(bookUrl)

    fun getChapter(bookUrl: String, index: Int): BookChapter? =
        appDb.bookChapterDao.getChapter(bookUrl, index)

    fun getChapterRecord(id: Long): BreakdownChapter? = appDb.breakdownChapterDao.get(id)

    /**
     * 确保该章存在拆解记录,不存在则创建(未拆)
     */
    fun ensureChapter(breakdownId: Long, chapterIndex: Int, chapterName: String): BreakdownChapter {
        appDb.breakdownChapterDao.getByBreakdownAndIndex(breakdownId, chapterIndex)?.let {
            return it
        }
        val chapter = BreakdownChapter(
            breakdownId = breakdownId,
            chapterIndex = chapterIndex,
            chapterName = chapterName
        )
        val ids = appDb.breakdownChapterDao.upsert(chapter)
        return chapter.copy(id = ids.firstOrNull() ?: 0L)
    }

    fun updateChapter(chapter: BreakdownChapter) = appDb.breakdownChapterDao.update(chapter)

    fun flowSegments(chapterId: Long) = appDb.breakdownSegmentDao.flowByChapter(chapterId)

    fun getSegments(chapterId: Long) = appDb.breakdownSegmentDao.getByChapter(chapterId)

    fun getSegment(id: Long): BreakdownSegment? = appDb.breakdownSegmentDao.get(id)

    fun upsertSegments(vararg segments: BreakdownSegment) =
        appDb.breakdownSegmentDao.upsert(*segments)

    fun deleteSegments(ids: List<Long>) = appDb.breakdownSegmentDao.deleteByIds(ids)

    fun updateLabels(ids: List<Long>, label: String, time: Long) =
        appDb.breakdownSegmentDao.updateLabels(ids, label, time)

    fun getTemplate(templateId: Long): BreakdownTemplate? = appDb.breakdownTemplateDao.get(templateId)

    fun getMaterialsByChapter(bookName: String, bookAuthor: String, chapterIndex: Int) =
        appDb.materialDao.getActiveByChapter(bookName, bookAuthor, chapterIndex)
}