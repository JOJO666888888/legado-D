package io.legado.app.ui.book.breakdown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.data.entities.BreakdownTemplate

class BookBreakdownViewModel(application: Application) : AndroidViewModel(application) {

    fun get(id: Long): BookBreakdown? = appDb.bookBreakdownDao.get(id)

    fun flowChapters(breakdownId: Long) = appDb.breakdownChapterDao.flowAllByBreakdown(breakdownId)

    fun getChapterList(bookUrl: String) = appDb.bookChapterDao.getChapterList(bookUrl)

    fun getChapterCount(bookUrl: String) = appDb.bookChapterDao.getChapterCount(bookUrl)

    fun getTemplate(templateId: Long): BreakdownTemplate? = appDb.breakdownTemplateDao.get(templateId)

    fun update(breakdown: BookBreakdown) = appDb.bookBreakdownDao.update(breakdown)
}