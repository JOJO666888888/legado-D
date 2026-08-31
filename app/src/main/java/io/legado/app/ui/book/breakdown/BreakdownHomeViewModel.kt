package io.legado.app.ui.book.breakdown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.data.entities.BreakdownTemplate

class BreakdownHomeViewModel(application: Application) : AndroidViewModel(application) {

    fun flowAll() = appDb.bookBreakdownDao.flowWithProgress()

    fun flowTrash() = appDb.bookBreakdownDao.flowTrash()

    fun getActiveByBook(name: String, author: String) =
        appDb.bookBreakdownDao.getActiveByBook(name, author)

    fun getTemplates(): List<BreakdownTemplate> = appDb.breakdownTemplateDao.all

    fun insert(breakdown: BookBreakdown): Long = appDb.bookBreakdownDao.insert(breakdown).firstOrNull() ?: 0L

    fun get(id: Long): BookBreakdown? = appDb.bookBreakdownDao.get(id)
}